package eu.kanade.tachiyomi.animeextension.es.animeav1

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.network.get
import keiyoushi.utils.decodeHex
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// uns.bio player ("UPNShare"). The embed no longer serves the stream itself: it calls
// `/api/public/resolve?file1=<id>` which redirects to a TurboEmbed player page
// (`https://<host>/<file2>`). That page fetches a signed `/api/stream/tracking?c=<enc>`
// endpoint whose `x-tracking` response header carries an AES-CBC payload listing the
// stream endpoints (`tiktok`/`hls`/`direct`). The AES key rotates daily and the IV is
// derived from the player host, so everything is rebuilt on every call.
class UnsExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    suspend fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val hosterUrl = url.toHttpUrl()
        val file1 = hosterUrl.fragment?.takeIf(String::isNotEmpty) ?: return emptyList()

        val resolveUrl = hosterUrl.newBuilder()
            .encodedPath("/api/public/resolve")
            .addQueryParameter("file1", file1)
            .build()
        val resolve = client.get(resolveUrl, headers, cacheControl = CacheControl.FORCE_NETWORK, ensureSuccess = false)
            .parseAs<Resolve>()
            .takeIf { it.ok }
            ?: return emptyList()
        val playerUrl = resolve.url?.toHttpUrlOrNull() ?: return emptyList()
        val origin = playerUrl.newBuilder()
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()
            .toString()
        // The player signs the last segment of its own path (pathname.split("/").pop()).
        val slug = playerUrl.pathSegments.lastOrNull().orEmpty()
        if (slug.isEmpty()) return emptyList()

        val playerHeaders = headers.newBuilder()
            .set("Referer", origin)
            .build()

        val crypto = TurboEmbedCrypto(playerUrl.host)
        val trackingUrl = playerUrl.newBuilder()
            .encodedPath("/api/stream/tracking")
            .addQueryParameter("c", crypto.encryptHex(slug))
            .build()
        val payload = client.get(trackingUrl, playerHeaders, cacheControl = CacheControl.FORCE_NETWORK, ensureSuccess = false)
            .use { it.headers["x-tracking"] }
            ?.takeIf(String::isNotEmpty)
            ?.let(crypto::decryptHex)
            ?: return emptyList()
        val stream = payload.parseAs<Tracking>().stream ?: return emptyList()

        return stream.sources().flatMap { (label, file) ->
            runCatching {
                playlistUtils.extractFromHls(
                    file,
                    referer = origin,
                    masterHeaders = playerHeaders,
                    videoHeaders = playerHeaders,
                    videoNameGen = { quality -> "${prefix}$label - $quality" },
                )
            }.getOrDefault(emptyList())
        }
    }

    @Serializable
    private class Resolve(val ok: Boolean = false, val url: String? = null)

    @Serializable
    private class Tracking(val stream: Stream? = null)

    @Serializable
    private class Stream(val tiktok: String? = null, val hls: String? = null, val direct: String? = null) {
        // The player probes them in this order and falls through on error.
        fun sources(): List<Pair<String, String>> = buildList {
            tiktok?.takeIf(String::isNotBlank)?.let { add("UPNShare TikTok" to it) }
            hls?.takeIf(String::isNotBlank)?.let { add("UPNShare" to it) }
            direct?.takeIf(String::isNotBlank)?.let { add("UPNShare Direct" to it) }
        }
    }
}

// Mirrors the TurboEmbed player's crypto (deobfuscated from its bundle): an AES-128-CBC key
// built from the hex codepoints of a fixed emoji string plus the current UTC day, and an IV
// built from a second emoji string plus the UTF-8 hex of the player host. Both are cut or
// zero-padded to 32 hex characters (16 bytes).
private class TurboEmbedCrypto(private val host: String) {

    fun encryptHex(plain: String): String = cipher(Cipher.ENCRYPT_MODE)
        .doFinal(plain.toByteArray(Charsets.UTF_8))
        .toHexString()

    fun decryptHex(payload: String): String = String(
        cipher(Cipher.DECRYPT_MODE).doFinal(payload.decodeHex()),
        Charsets.UTF_8,
    )

    private fun cipher(mode: Int): Cipher {
        val key = buildString {
            KEY_CHARS.forEach { append(it.codePointAt(0).toString(16)) }
            append((System.currentTimeMillis() / 86_400_000L).toString(16))
        }.take(32).padEnd(32, '0')

        val iv = buildString {
            SALT_CHARS.forEach { append(it.codePointAt(0).toString(16)) }
            append(host.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) })
        }.take(32).padEnd(32, '0')

        return Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(mode, SecretKeySpec(key.decodeHex(), "AES"), IvParameterSpec(iv.decodeHex()))
        }
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private val KEY_CHARS = listOf("𝕊", "𝔼", "𝕮", "𝚁", "𝔼", "𝚃", "𝓚", "𝓔", "𝓨", "𝟙", "𝟚", "𝟛")
        private val SALT_CHARS = listOf("🔐", "🗝️", "🔑", "🚪", "🛡️", "⚡")
    }
}
