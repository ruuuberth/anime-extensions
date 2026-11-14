# AGENTS.md

@CONTRIBUTING.md

> **Import note:** `@CONTRIBUTING.md` is expanded automatically by agents that support `@`-imports (Claude Code, Gemini CLI, OpenCode, Pi, etc. – see [agents.md#faq](https://agents.md) and [pi#6291](https://github.com/earendil-works/pi/issues/6291)). If your harness does not expand it, read `CONTRIBUTING.md` directly – it is the source of truth. This file is a concise, agent-oriented summary.

## Project

This checkout is a **fork**: `ruuuberth/anime-extensions` (`origin`) of `yuzono/anime-extensions`, which is itself an
anime-flavoured copy of the manga `keiyoushi/extensions-source`. Kotlin + Jsoup/OkHttp scrapers: one Gradle module per
extension (`src/<lang>/<source>`), shared CMS logic as themes (`lib-multisrc/<theme>`), shared helpers in `lib/` and
`core` (`keiyoushi.utils`, `keiyoushi.network`). **This fork publishes its own repository**, `ruuuberth/anime-repo`
(branch `repo`), so extensions are built and released without depending on an upstream maintainer. The clients are
Anikku/Animiru (Aniyomi forks).

- Default branch is **`master`**, not `main`. Upstream remotes `yuzono` and `@secozzi` are **not configured in this
  clone** – add them when a task needs them:
  ```bash
  git remote add yuzono https://github.com/yuzono/tachiyomi-extensions
  git remote add @secozzi https://github.com/Secozzi/aniyomi-extensions
  ```
- Git identity for this repo is already set to `ruuuberth <77597261+ruuuberth@users.noreply.github.com>` (repo-local).
  Commit as that account; never adopt another author's identity.

## Environment (this workspace)

- **Termux/Linux with no Android SDK** (`ANDROID_HOME` unset, no `local.properties`) and the Gradle wrapper cannot even
  bootstrap: downloading `gradle-9.7.1-bin.zip` times out. **Don't plan on running `./gradlew` here** – verification
  goes through GitHub Actions. Say plainly in your report what could not be run.
- Java 21 is installed; CI uses Java 25, so even with the SDK a local build wouldn't match CI.
- No `gpg` binary and no `commit.gpgsign` config: commits can't be GPG-signed here, so the cherry-pick skill's
  "signature must show `G`" check is not satisfiable in this workspace.
- `gh` is authenticated as `ruuuberth`; use it for PRs and to read CI runs.

## Commands

**Always scope a task to a module.** The build has ~337 modules (250 extensions + 76 libs + 9 themes + `core` +
`compiler`); an unscoped `./gradlew <task>` configures every one of them.

- Verify a change (fast – no APK link/package step): `./gradlew :src:<lang>:<source>:compileDebugKotlin`
  (e.g. `./gradlew :src:en:anizone:compileDebugKotlin`, `./gradlew :lib-multisrc:anikototheme:compileDebugKotlin`)
- Build an installable APK: `./gradlew :src:<lang>:<source>:assembleDebug`
- Format (local only – **CI does not run Spotless**): `./gradlew :<module>:spotlessCheck` / `:<module>:spotlessApply`
  (ktlint 1.8.0; Spotless overrides `max_line_length`, so it never wraps long lines)
- Scaffold a new extension instead of writing files by hand:
  `python ext-bootstrap.py -n "My Source" -l en -u https://mysource.com` (add `-m <theme>` for a themed one)
- Build all: `./gradlew assembleDebug` – avoid; narrow `settings.gradle.kts` instead (see below)

Module paths are colon-separated and mirror the directory layout: `:src:en:miruro`,
`:lib-multisrc:anikototheme`, `:core`.

### Tests

The repo's only unit tests are in `core/src/test/kotlin/keiyoushi/utils/`, `lib/unpacker/test/kotlin/`
and `lib/megaextractor/test/`. Extensions have none – they are verified by compiling and running
against the live site (`CONTRIBUTING.md#submitting-the-changes` ~ `1768`).

```bash
./gradlew --continue :core:testDebugUnitTest :lib:unpacker:testDebugUnitTest :lib:megaextractor:testDebugUnitTest
./gradlew :core:testDebugUnitTest --tests 'keiyoushi.utils.UrlUtilsTest'
```

### Loading a subset of modules

`settings.gradle.kts` calls `loadAllIndividualExtensions()`, which includes every extension under
`src/`. To work on a few, comment it out and list them instead – do not commit the narrowed file:

```kotlin
// loadAllIndividualExtensions()
loadIndividualExtension("en", "miruro")
```

`lib/` and `lib-multisrc/` are always loaded wholesale.

## Structure

- `src/<lang>/<source>/build.gradle.kts` – extension metadata in the `yuzono { }` block (`name`, `versionCode`,
  `contentWarning`, `libVersion`, optional `theme`, `torrent`, `deeplink { }`) and one `source { }` block per source
  (`lang`, `baseUrl` – static `baseUrl = "…"`, `baseUrl { mirrors("…", …) }` (or `mirrors("label" to "…", …)` for
  labeled entries) or `baseUrl { custom("…") }` – and optional `name`, `id`, `versionId`).
  - The source class is `@Source abstract class` with no `lang`/`id`/`versionId` overrides, which KSP rejects; it may
    override `name`, or `baseUrl` over a static `baseUrl`, and codegen then skips that property, but this is
    discouraged. KSP generates `ExtensionGenerated` from the blocks (`CONTRIBUTING.md#source-declaration`).
  - Every extension needs at least one `source { }` block. Only `src/all/jellyfin` still sets the legacy `className`,
    which loads a hand-written class (its factory) instead: nothing is generated, and its `source { }` blocks only
    describe the sources for the repo index, so keep them in step with the class.
  - `torrent = true` marks torrent sources (7 modules today under `src/all`); it is anime-only, with no manga
    counterpart.
- `src/<lang>/<source>/src/eu/kanade/tachiyomi/animeextension/<lang>/<source>/` – source code (package must match)
- `lib-multisrc/<theme>/build.gradle.kts` – theme base (`alias(kei.plugins.multisrc)`, `yuzono { baseVersionCode; libVersion; deeplink { } }`)
- `lib-multisrc/<theme>/src/.../multisrc/<theme>/` – abstract theme class `extends AnimeHttpSource`
- `lib/` – reusable libs (`lib-cryptoaes`, `lib-playlistutils`, extractors, etc.)
- `core` – `keiyoushi.utils` (`parseAs`, `toJsonRequestBody`, `tryParse`, `extractNextJs`, `absUrl`) and
  `keiyoushi.network` (`rateLimit`, `addCookie`, `OkHttpClient.get`/`post`) – use these, no custom JSON/regex/date/interceptor helpers

## Conventions

See `CONTRIBUTING.md` for full rules. Critical for agents:

- Check `lib/` and `core` (`keiyoushi.utils` for parsing/prefs, `keiyoushi.network` for clients) first – reuse existing libs/helpers instead of custom boilerplate (`parseAs`/`toJsonRequestBody`/`tryParse`/`extractNextJs`/`absUrl`, `rateLimit`/`addCookie`, `lib-cryptoaes`/etc.). See `CONTRIBUTING.md#core-dependencies`.
- Kotlin + Android; web scraping via CSS selectors, OkHttp, Jsoup.
- Do not use `data class` for `@Serializable` DTOs unless needed; camelCase fields, `@SerialName` only when JSON key differs.
- Use `response.parseAs<T>()`, `response.asJsoup()`, `SimpleDateFormat(...).tryParse()`, `element.absUrl("href")` + `setUrlWithoutDomain()`.
- No hardcoded `User-Agent`, no `Thread.sleep()`, no manual Cloudflare checks, no `buildJsonObject` for requests.
- Preserve `id` when renaming `name`/`lang`; keep package name stable.
- New or migrated extensions target extensions-lib 16: extend `AnimeHttpSource` directly (not the `keiyoushi.utils.AnimeHttpLegacySource`/`ParsedAnimeHttpLegacySource` shims), implement hosters (`getHosterList`/`getVideoList(hoster)`), use the named `Video(videoUrl =, videoTitle =, ...)` constructor, and set `libVersion = "16"` in `build.gradle.kts`. See `CONTRIBUTING.md#extensions-lib-16`.

### `keiyoushi.network` client helpers

- `rateLimit(permits, period, interval) { url -> Boolean }` replaces the deprecated
  `eu.kanade.tachiyomi.network.interceptor.rateLimit`/`rateLimitHost`. The predicate receives an
  `HttpUrl`, so compare against a **host** (`it.host == "api.example.com"`), never a full URL.
- `addCookie(...)` replaces the removed `lib-cookieinterceptor`.
- `OkHttpClient.get`/`post`/`put`/`head` are suspend helpers; `get`/`head` default to a 10-minute
  `CacheControl`, and all default to `ensureSuccess = true` (throws on non-2xx).
- Many overloads use Kotlin **context parameters** (`context(source: AnimeHttpSource)`). Inside a
  class that *is* an `AnimeHttpSource` the implicit receiver satisfies them, so `client.get(url)` and
  `.addCookie("k" to "v")` work with no `with(...)` wrapper and pick up `headers`/`baseUrl`.
- Mirror lists and user-editable base URLs belong in `source { baseUrl { mirrors(…) } }` /
  `source { baseUrl { custom(…) } }`, not in hand-written preferences. The generated `baseUrl` reads that setting on
  every access, so a value read once – in a property initializer or `init` block, including `headers`
  that add a `Referer` – keeps the old mirror until the app restarts. Build what depends on it at use (a
  getter), as `docHeaders` does in `lib-multisrc/anikototheme/.../AnikotoTheme.kt`, so a mirror change
  applies at once.
- A source that owns a preference-backed `baseUrl` itself (`override var baseUrl` over a static
  `baseUrl` in its `source { }` block, e.g. to follow a domain redirect) must rebuild everything derived
  from it when it changes.
  Use `keiyoushi.utils.LazyMutable` (a read/write lazy delegate) and reassign it in the setter – a plain
  `by lazy` keeps serving the stale value. See `src/fr/animesama`.
- Do not eagerly initialize `client` in a theme base class: the initializer runs before subclass
  constructors, so an overridden `open val rateLimit` reads `0`.

## Referring to commits

When you mention a commit while talking to the user (chat replies, summaries, reports), give its short hash followed by
its subject in parentheses, with no quotes, like `git log --format=reference`. Shorten long subjects with `…`, and drop
a trailing PR reference such as `(#17034)` so the parentheses don't nest: `d239a86987` (small cleanup in build logic),
`8573282cf4` (Set output apk filename with variant api…). A bare hash can't be recognized at a glance. This is about
conversation only; it doesn't apply to commit messages or PR descriptions.

## Porting upstream changes

Build logic, CI, `core` and docs are mostly cherry-picked from the manga repos: `yuzono/tachiyomi-extensions` (a copy of
`keiyoushi/extensions-source`) and `Secozzi/aniyomi-extensions` – see the `cherrypick-log` skill. Pick with
`git cherry-pick -x` so the message keeps its `(cherry picked from commit …)` line, and qualify the upstream PR reference
in the subject – `(keiyoushi/extensions-source#16992)`, not a bare `(#16992)`, which GitHub would link to this repo's own
PR. Keep upstream's code and layout so later picks still apply, and change only what is manga- or upstream-specific:

- **Manga → anime:** `eu.kanade.tachiyomi.extension` → `eu.kanade.tachiyomi.animeextension` (package, namespace,
  `tachiyomi.animeextension.*` meta-data, icon names); `Source`/`HttpSource`/`SManga`/`SChapter`/`fetchSearchManga`
  → `AnimeSource`/`AnimeHttpSource`/`SAnime`/`SEpisode`/`getSearchAnime`; intent action `eu.kanade.tachiyomi.SEARCH`
  → `eu.kanade.tachiyomi.ANIMESEARCH`; manga wording in docs → anime/episode wording.
- **keiyoushi/yuzono manga → Aniyomi:** `Tachiyomi: <name>` / `tachiyomi-<lang>.<ext>-v….apk` → `Aniyomi: <name>` /
  `aniyomi-…`; keiyoushi or manga-repo URLs, store name, badge and signing key → this repo's (`yuzono/anime-repo`,
  `Yūzōnō`); the Gradle DSL block is `yuzono { }` here, `keiyoushi { }` upstream.
- **Versions:** `libVersion` is `"14"`/`"16"` (versionName `<lib>.<code>`), not upstream's `"1.4"`; drop KMK-only
  fields (`kmkVersionCode`, `// KMK -->` blocks).
- **Index schema:** `.github/scripts/index_pb2.py` follows the Aniyomi spec (`aniyomiorg/extensions-lib`
  `index/index.proto`), not Mihon's – `Extension.isTorrent = 8`, `sources = 9`, `Source.message = 6`. Keep these
  numbers when a pick regenerates the bindings.
- **CI:** `build_pull_request.yml` has an anime-only `publish` job ("Check extension repo") that runs `publish-repo.py`
  the same way `build_push.yml` does. Upstream has no such job, so mirror into it any dependency or argument change a
  pick makes to `build_push.yml`.
- **Publish targets:** upstream publishes to `yuzono/aniyomi-repo`, whose `sync.yml` copies it to `yuzono/anime-repo`;
  **this fork publishes directly to `ruuuberth/anime-repo` (branch `repo`)**, and the PR check reads that same repo.
  Keep both when a pick touches repository targets, and don't let a pick reintroduce upstream's hardcoded URLs:
  `publish-repo.py` takes them from `EXTENSION_REPO` / `APK_BASE_URL` / `ICON_BASE_URL`.

**Torrent is the exception:** it is anime-only and has no manga counterpart, so no upstream change carries it – keep it
when resolving conflicts and add it to any rewritten script. `torrent = true` in `yuzono { }` → the
`tachiyomi.animeextension.torrent` meta-data (Anikku's `ExtensionLoader`), and `isTorrent` in `keiyoushi-source-info.json`
→ `torrent` in `index.min.json` (Anikku's `ExtensionApi`) and `isTorrent` in `index.json`/`index.pb`.

**A pick commit holds only that upstream change**, plus its `O` row in `cherrypick_log.md`. Uncommitted changes that
are already in the tree when you pick, such as notes added to `cherrypick_log.md` or work on another file, stay out of
it, staged or not:
1. Read the commit's row first, Notes included. Then stash those changes with
   `git stash push -m "unrelated to <hash>"`. `git cherry-pick` refuses to start with staged changes, and a dirty
   `cherrypick_log.md` would otherwise be committed along with the row.
2. Stage only the pick's own files: `git add <path>` for each resolved file. Never use `git add -A`, `git add -u` or
   `git commit -a` during a pick.
3. After the commit, or as soon as you stop without one, restore your changes with `git stash pop`. If the pick is
   still in progress, run `git cherry-pick --abort` first: `git stash pop` fails while it has unresolved conflicts.
   If the pop conflicts, keep both sides, leave the result unstaged and `git stash drop`. A note on the picked row and
   its new `O` mark conflict like this.

Untracked files can stay where they are, unless the pick adds a file at the same path.

### Cherry-pick tooling (in-repo)

- Procedure: `.claude/skills/cherry-pick/SKILL.md` (the `cherry-pick` skill). It owns the preflight, the Anikku
  compatibility check, the adaptation rules above, the message/log-row handling and the verification plan.
- Log tooling: `.claude/skills/cherrypick-log/` (`update_cherrypick_log.py`, `config.json`, `README.md`).
  - Regenerate the table: `python3 .claude/skills/cherrypick-log/update_cherrypick_log.py` (needs the upstream remotes).
  - Mark a pick: `python3 .claude/skills/cherrypick-log/update_cherrypick_log.py mark <full-hash> --status O`
    (also `--note`, `--status '?'`, `--status X`; quote `?` in zsh). Only the Status and Notes cells are yours; the
    script rewrites everything else.
  - `cherrypick_log.md` is generated: never edit the table by hand except to resolve a stash-pop conflict.

## Versioning – bump once per PR, theme bump propagates

Source of truth: `CONTRIBUTING.md:326-365` (individual) + `CONTRIBUTING.md:1466-1591` (themes) + `gradle/build-logic/src/main/kotlin/ExtensionPlugin.kt:128-136`:

```kotlin
val versionCodeProvider = themeExtension.flatMap { themeKeiyoushi ->
    // ...
    themeKeiyoushi.baseVersionCode.zip(keiyoushi.versionCode) { base, ext -> base + ext }
}.orElse(keiyoushi.versionCode)
```

Rules:

- Individual extension (no theme): increment `versionCode` by **1** if code affecting users changed. Bump **once per PR** – do not increment multiple times across commits.
- Theme (`lib-multisrc/<theme>/build.gradle.kts`): increment `baseVersionCode` by **1** when theme logic changes.
- When `baseVersionCode` is bumped, **do not** bump `versionCode` for extensions using that theme in the same PR – the addition already bumps every extension's effective version code. Only bump the extension's `versionCode` when the extension itself changed independently of the theme.
- Checklist mirrors this: `CONTRIBUTING.md#pull-request-checklist` ~ `1785-1786` and `.github/pull_request_template.md:3-4`.

## CI – what it bumps and builds for you

From `.github/workflows/build_pull_request.yml` + `.github/scripts/`:

- Changing anything under `lib/` makes CI run `bump-versions.py`, which **auto-bumps every dependent
  extension**. Do not bump those by hand – only bump the module you actually edited.
- CI builds only the changed modules (`generate-build-matrices.py` → `<module>:assembleRelease`, or
  `assembleDebug` in a PR check without the signing key), except in a full rebuild, which builds every extension
  and replaces the whole published index with the build output: extensions removed from `src/` drop out, with
  their APKs and icons. A full rebuild happens when a core file changes (`CORE_FILES_REGEX`: `common/`,
  `compiler/`, `core/`, `gradle/`, the root build files, `.github/scripts`), on a manual run of `build_push.yml`
  with `full_rebuild`, and when no earlier successful run is found.
- Head-commit message prefixes: `[skip bumping version]` skips the auto-bump; `[skip yuzono ci]` skips
  the bump *and* the build, except in a manual `full_rebuild` run, which still builds.

### What CI actually does in this fork

- `build_push.yml` ("CI") triggers only on pushes to **`master`**; `build_pull_request.yml` ("PR check") runs on PRs.
  **Pushing a feature branch alone runs no build** – open a PR (or push to `master`) to get a compilation check.
- This fork **publishes to its own repository**, `ruuuberth/anime-repo` (branch `repo`): the upstream gates
  (`github.repository == 'yuzono/anime-extensions'`) were removed from the version-bump, APK-upload and publish jobs,
  and the publish job checks that repo out with `secrets.GITHUB_TOKEN`. The app adds the repository with
  `https://raw.githubusercontent.com/ruuuberth/anime-repo/repo/index.min.json`.
- `publish-repo.py` takes the target repository and branding from the environment (`EXTENSION_REPO`, `REPO_NAME`,
  `REPO_BADGE_LABEL`, `REPO_WEBSITE`, `REPO_DISCORD`), with the upstream values as defaults so upstream picks keep
  applying. The signing-key fingerprint comes from `SIGNING_KEY_FINGERPRINT`, else from the published `repo.json`,
  else the upstream default – **it must match the `meta.signingKeyFingerprint` in `ruuuberth/anime-repo`'s
  `repo.json`**, or the app refuses to trust the APKs.
- The first successful run in this fork has no base commit, so it is a **full rebuild**: all ~250 extensions are built
  and the whole index is replaced by the build output. Later runs build only what changed.
- Signing secrets in `ruuuberth/anime-extensions`: `SIGNING_KEY` (base64 of the keystore), `ALIAS`,
  `KEY_STORE_PASSWORD`, `KEY_PASSWORD`. **The keystore lives outside the repo** (`~/.config/aniyomi-extensions/`);
  losing it means already-installed extensions can never be updated, so back it up.
- The publish job pushes to `ruuuberth/anime-repo` over **SSH** with the `DEPLOY_KEY` secret, because `GITHUB_TOKEN`
  can only write to the repository the workflow runs in (a cross-repo HTTPS push fails with 403). The matching
  deploy key is registered on `ruuuberth/anime-repo` with write access; GitHub's ed25519 host key is pinned in
  `known_hosts` (from `https://api.github.com/meta`). The private key lives outside the repo too.
- `auto_merge_upstream.yml` and `codeberg_mirror.yml` stay gated to `yuzono/anime-extensions`, so they are no-ops here.
- Fork-only workflow: `.github/workflows/build-animeonlineninja.yml` – on a push to `ci/build-animeonlineninja`
  it builds `:src:es:animeonlineninja:assembleDebug` from branch `local/animeonlineninja-gtranslate` and uploads
  the APK as an artifact.
- Read results with `gh run list --branch <branch>`, `gh run watch <id> --exit-status`, `gh run view <id> --log-failed`.

## Boundaries

- Do not change `versionName` manually (generated `<libVersion>.<versionCode>`; set `libVersion = "14"` unless the extension uses the lib 16 API, and never lower than its theme's `libVersion`).
- Do not commit `web_hi_res_512.png` (delete after Icon Generator).
- Do not push to `upstream` (`no_pushing` – fork workflow `CONTRIBUTING.md:182-211`). Use `origin` (your fork) for PRs.
- Never commit secrets, keystore, or `local.properties`.
- PRs: follow `CONTRIBUTING.md#submitting-the-changes` ~ `1768-1793` (checklist: version bump, `contentWarning`,
  unchanged source names, kept `id`, removed `web_hi_res_512.png`); title concise, reference issues (`Closes #xyz`);
  one version bump per module per PR as above.
