package eu.kanade.tachiyomi.animeextension.es.animebum

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.luluextractor.LuluExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import keiyoushi.annotation.Source
import keiyoushi.utils.bodyString
import keiyoushi.utils.decodeHexToString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.useAsJsoup
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.Locale

/**
 * AnimeBum, the Spanish anime site.
 *
 * The old domain (animebum.net) was taken offline and its DNS now points at a parking
 * service, so the source moved to animebum.org, which serves the same catalogue with
 * Spanish and Latin American subtitles.
 *
 * The site runs a small custom CMS, not WordPress:
 *  - the catalogue lives in `/animes`, paginated with `?pag=` and filtered with
 *    `?tipo=`, `?estado=` and `?orden=`;
 *  - the episode list is not in the markup: JavaScript renders it from a `var eps = [...]`
 *    array that sits next to the slug in a `data-sl` script tag;
 *  - the player options are not in the markup either: the episode page only carries an
 *    id in `ul.opt[data-encrypt]`, and the options come from a POST to `/bum`
 *    (`acc=opt`, `i=<id>`) that answers with the iframe urls hex-encoded.
 */
@Source
abstract class AnimeBum :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/animes?pag=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val animes = document.select(ITEM_SELECTOR).map { element ->
            SAnime.create().apply {
                title = element.selectFirst("figure.i a span")?.text() ?: element.selectFirst("figure.i img")?.attr("alt").orEmpty()
                val slug = element.selectFirst("figure.i a")?.attr("href")?.substringAfter("/anime/", "").orEmpty()
                setUrlWithoutDomain("$baseUrl/anime/$slug")
                thumbnail_url = element.selectFirst("figure.i img")?.attr("abs:data-src") ?: element.selectFirst("figure.i img")?.attr("abs:src")
            }
        }
        return AnimesPage(animes, hasNextPage(document, response))
    }

    // The catalogue is paginated with numbered links instead of a "next" link, so the
    // page after the current one is the one to look for.
    private fun hasNextPage(document: Document, response: Response): Boolean {
        val currentPage = response.request.url.queryParameter("pag")?.toIntOrNull() ?: 1
        return document.select("a[href]").any { element ->
            element.attr("href").substringAfter("pag=", "").substringBefore("&").toIntOrNull() == currentPage + 1
        }
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/animes?estado=estreno&orden=desc&pag=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = buildList<Pair<String, String>> {
            if (query.isNotBlank()) {
                add("buscar" to query)
            } else {
                addAll(filterParams(filters))
            }
            add("pag" to page.toString())
        }
        val queryString = params.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
        return GET("$baseUrl/animes?$queryString", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1 strong")?.text().orEmpty()
            description = document.selectFirst("div.tx p")?.text()
            genre = document.select("ul.gn a").joinToString(", ") { it.text() }
            thumbnail_url = document.selectFirst("figure.i img")?.attr("abs:data-src") ?: document.selectFirst("figure.i img")?.attr("abs:src")
            status = when (document.selectFirst("ul.dt span.status")?.text()?.trim()) {
                "En emision" -> SAnime.ONGOING
                "Finalizado" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        val script = document.selectFirst("script[data-sl]") ?: return emptyList()
        val slug = script.attr("data-sl")
        val firstEpisode = script.attr("data-zr").toIntOrNull() ?: 1
        val lastEpisode = script.attr("data-ep").toIntOrNull() ?: 0

        val numbers = EPISODE_NUMBERS_REGEX.find(script.data())?.groupValues?.get(1)?.let { body -> EPISODE_NUMBER_REGEX.findAll(body).mapNotNull { it.groupValues[1].toIntOrNull() }.toList() }.orEmpty()

        return numbers
            .filter { it in firstEpisode..lastEpisode }
            .map { number ->
                SEpisode.create().apply {
                    name = "Episodio $number"
                    episode_number = number.toFloat()
                    setUrlWithoutDomain("$baseUrl/ver/$slug-$number")
                }
            }
            .sortedByDescending { it.episode_number }
    }

    private fun rememberServers(names: Set<String>) {
        val labels = names.map { it.trim().replaceFirstChar { c -> c.uppercaseChar() } }.filter { it.isNotBlank() }.toSet()
        if (labels.isEmpty()) return
        val stored = preferences.getStringSet(PREF_DISCOVERED_SERVERS_KEY, emptySet<String>()).orEmpty()
        if (stored.containsAll(labels)) return
        preferences.edit().putStringSet(PREF_DISCOVERED_SERVERS_KEY, stored + labels).apply()
    }

    private fun discoveredServers(): Set<String> {
        val stored = preferences.getStringSet(PREF_DISCOVERED_SERVERS_KEY, emptySet<String>()).orEmpty()
        return stored.ifEmpty { SERVER_LIST.toSet() }
    }

    // ============================ Video Links =============================
    override fun hosterListParse(response: Response): List<Hoster> {
        val document = response.useAsJsoup()
        val id = document.selectFirst("ul.opt[data-encrypt]")?.attr("data-encrypt").orEmpty()
        if (id.isBlank()) return emptyList()

        val body = FormBody.Builder()
            .add("acc", "opt")
            .add("i", id)
            .build()
        // The endpoint only answers when the request looks like the site's own ajax
        // call: it needs both the xmlhttprequest marker and the episode page as referer,
        // otherwise it replies with an empty body.
        val postHeaders = headers.newBuilder()
            .set("Referer", response.request.url.toString())
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
        val html = runCatching { client.newCall(POST("$baseUrl/bum", postHeaders, body)).execute().bodyString() }.getOrNull() ?: return emptyList()

        val seen = mutableSetOf<String>()
        val hosters = OPTION_TAG_REGEX.findAll(html).mapNotNull { match ->
            val tag = match.value
            val hex = ENCRYPT_REGEX.find(tag)?.groupValues?.get(1) ?: return@mapNotNull null
            val url = runCatching { hex.decodeHexToString() }.getOrNull() ?: return@mapNotNull null
            if (url.toHttpUrlOrNull() == null) return@mapNotNull null
            val name = TITLE_REGEX.find(tag)?.groupValues?.get(1)?.removePrefix("Opción ")?.trim()?.takeIf { it.isNotBlank() } ?: "Servidor"
            seen += name
            Hoster(hosterUrl = url, hosterName = name)
        }.distinctBy { it.hosterUrl }.toList()
        rememberServers(seen)
        return hosters
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val url = hoster.hosterUrl
        val host = url.toHttpUrlOrNull()?.host?.lowercase(Locale.ROOT).orEmpty()
        val name = hoster.hosterName
        return when {
            // The "filemoon" options are served by the Byse network.
            "bysesukior" in host -> filemoonExtractor.videosFromUrl(url, "$name ", headers)
            "dooodster" in host || "playmogo" in host -> doodExtractor.videosFromUrl(url, quality = "$name ")
            "luluvdo" in host -> luluExtractor.videosFromUrl(url, "$name ")
            "filelions" in host -> vidHideExtractor.videosFromUrl(url) { quality -> "$name $quality" }
            "streamwish" in host -> streamWishExtractor.videosFromUrl(url) { quality -> "$name $quality" }
            "voe" in host -> voeExtractor.videosFromUrl(url, "$name ")
            "uqload" in host -> uqloadExtractor.videosFromUrl(url, prefix = "$name ")
            "mp4upload" in host -> mp4uploadExtractor.videosFromUrl(url, headers, prefix = "$name ")
            "mixdrop" in host || "mxdrop" in host -> mixDropExtractor.videosFromUrl(url, prefix = "$name ")
            else -> universalExtractor.videosFromUrl(url, headers, prefix = "$name ")
        }
    }

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val server = preferredServer()
        return sortedWith(
            compareByDescending<Hoster> { it.hosterName.contains(server, true) }
                .thenBy { it.hosterName },
        )
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferredServer()
        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(server, true) }
                .thenByDescending { it.videoTitle.contains(quality, true) }
                .thenByDescending { QUALITY_REGEX.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
        )
    }

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La búsqueda por texto ignora el filtro"),
        TypeFilter(),
        StatusFilter(),
        OrderFilter(),
    )

    private fun filterParams(filters: AnimeFilterList): List<Pair<String, String>> = buildList {
        filters.filterIsInstance<ParamFilter>().forEach { filter ->
            val value = filter.value()
            if (value.isNotBlank()) add(filter.param to value)
        }
    }

    private open class ParamFilter(displayName: String, val param: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun value(): String = vals[state].second
    }

    private class TypeFilter :
        ParamFilter(
            "Tipo",
            "tipo",
            arrayOf(
                "Todos" to "",
                "Anime" to "anime",
                "Película" to "pelicula",
                "Especial" to "especial",
                "OVA" to "ova",
                "ONA" to "ona",
                "Corto" to "corto",
                "Donghua" to "donghua",
            ),
        )

    private class StatusFilter :
        ParamFilter(
            "Estado",
            "estado",
            arrayOf(
                "Todos" to "",
                "Estreno" to "estreno",
                "Finalizado" to "finalizado",
            ),
        )

    private class OrderFilter :
        ParamFilter(
            "Orden",
            "orden",
            arrayOf(
                "Descendente" to "desc",
                "Ascendente" to "asc",
            ),
        )

    // ============================ Preferences =============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val servers = (discoveredServers() + PREF_SERVER_DEFAULT).sorted().toTypedArray()
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = servers
            entryValues = servers
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)
    }

    // A value stored by an older version may name a server the site does not offer;
    // fall back to the default instead of sorting against a dead name.
    private fun preferredServer(): String = preferences.getString(PREF_SERVER_KEY, null)
        ?.takeIf { it in discoveredServers() }
        ?: PREF_SERVER_DEFAULT

    // ============================ Extractors ==============================
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val luluExtractor by lazy { LuluExtractor(client, headers) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Voe"
        private const val PREF_DISCOVERED_SERVERS_KEY = "discovered_servers"

        // Used until the site's real server names have been observed.
        private val SERVER_LIST = arrayOf(
            "Voe",
            "Filemoon",
            "Doodstream",
            "Lulustream",
            "VidHide",
            "StreamWish",
            "Uqload",
            "Mp4Upload",
            "MixDrop",
        )

        private const val ITEM_SELECTOR = "article.li"

        private val EPISODE_NUMBERS_REGEX = Regex("""var eps = \[([^]]*)]""")
        private val EPISODE_NUMBER_REGEX = Regex("""(\d+)""")
        private val OPTION_TAG_REGEX = Regex("""<li[^>]*>""")
        private val ENCRYPT_REGEX = Regex("encrypt=\"([0-9a-fA-F]+)\"")
        private val TITLE_REGEX = Regex("title=\"([^\"]*)\"")
        private val QUALITY_REGEX = Regex("""(\d+)p""")
    }
}
