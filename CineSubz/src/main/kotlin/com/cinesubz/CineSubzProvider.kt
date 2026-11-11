package com.cinesubz

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import java.net.URLEncoder

const val SITE_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

/**
 * Provider for CineSubz.lk - Movies & TV series with Sinhala subtitles.
 *
 * Site notes (verified live):
 * - Search has no paging (`/?s={query}`, `&page=` 404s).
 * - All listing cards: `div.display-item > div.item-box` (archives/search) and
 *   `div.module-item` (homepage carousels), both with `a[data-ptype]`.
 * - Player servers live on `#playeroptionsul li.zetaflix_player_option` where each
 *   option carries its own `data-post` / `data-type` ("mv"|"ep") / `data-nume`.
 *   `data-nume="trailer"` must be skipped.
 * - `GET wp-json/zetaplayer/v2/{post}/{type}/{nume}` returns
 *   `{"embed_url","type","play_url","msg"}`; `type:"mp4"` leads to a CS Player
 *   HTML page containing direct mp4 links (ALL_QUALITIES), `type:"iframe"`
 *   leads to an EvoStreamX FirePlayer page ([EvoPlayer] handles it).
 */
class CineSubz : MainAPI() {
    override var mainUrl = "https://cinesubz.lk"
    override var name = "CineSubz"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "si"

    private val browserHeaders = mapOf("User-Agent" to SITE_USER_AGENT)

    override val mainPage = mainPageOf(
        "$mainUrl/trending" to "Trending Now",
        "$mainUrl/movies" to "Latest Movies",
        "$mainUrl/tvshows" to "Latest TV Shows",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isTrending = request.data.substringAfterLast("/") == "trending"
        // /page/1/ only exists as a 301 redirect to the archive root, so skip it for page 1
        val url = when {
            isTrending -> mainUrl
            page == 1 -> "${request.data}/"
            else -> "${request.data}/page/$page/"
        }
        val doc = app.get(url, headers = browserHeaders).document

        // Trending Now lives in its own carousel module (the other home modules are
        // AJAX-loaded shells); archives/search use display-item cards.
        val cardElements = if (isTrending) {
            val scoped = doc.select("div.home-module[data-module-id='featured-trending'] div.module-item")
            if (scoped.isNotEmpty()) scoped else doc.select("div.module-item")
        } else {
            doc.select("div.display-item, div.module-item")
        }
        val cards = cardElements
            .mapNotNull(::parseCard)
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }

        val hasNext = when {
            isTrending -> false
            else -> {
                // `div.page-nav .pagination span.total` renders "Page X of Y"
                val total = doc.selectFirst("div.page-nav .pagination span.total")?.text()
                    ?.let { Regex("""of\s+(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                if (total != null) page < total else cards.isNotEmpty()
            }
        }
        return newHomePageResponse(request.name, cards, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}",
            headers = browserHeaders,
        ).document
        return doc.select("div.display-item")
            .mapNotNull(::parseCard)
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
    }

    // site has no separate quick search
    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun CardInfo.toSearchResponse(): SearchResponse? {
        // NOTE: keep the poster in a local val — inside the initializer lambdas below,
        // an unqualified `posterUrl` would resolve to the builder's own null property
        // (receiver shadowing) instead of this CardInfo's field.
        val poster = this.posterUrl
        return if (isTv) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = browserHeaders).document
        val details = parseDetails(doc.html())
            ?: throw ErrorLoadingException("No details found at $url")
        if (url.contains("/tvshows/")) {
            val episodes = parseEpisodes(doc.html())
            return newTvSeriesLoadResponse(details.title, url, TvType.TvSeries, episodes.map { ep ->
                newEpisode(ep.url) {
                    this.name = ep.title ?: "${ep.season ?: "S?"}x${ep.episode ?: "??"}"
                    this.season = ep.season
                    this.episode = ep.episode
                    this.posterUrl = ep.posterUrl
                    ep.date?.let { runCatching { addDate(it, "MMM. dd, yyyy") } }
                }
            }) {
                applyDetails(this, details)
            }
        }
        return newMovieLoadResponse(details.title, url, TvType.Movie, dataUrl = url) {
            applyDetails(this, details)
        }
    }

    private fun applyDetails(response: LoadResponse, details: DetailsInfo) {
        response.posterUrl = details.posterUrl
        response.plot = details.plot
        details.rating?.let { response.addScore(Score.from(it, 10)) }
        details.year?.let { response.year = it }
        details.duration?.let { response.addDuration(it) }
        if (details.genres.isNotEmpty()) response.tags = details.genres
        if (details.actors.isNotEmpty()) {
            response.addActors(details.actors.map { actor ->
                Actor(actor.name, actor.image) to actor.role
            })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = browserHeaders).document
        val options = doc.select("#playeroptionsul li.zetaflix_player_option")
        if (options.isEmpty()) return false

        var found = false
        for (option in options) {
            val nume = option.attr("data-nume").trim()
            val post = option.attr("data-post").trim()
            val ptype = option.attr("data-type").trim()
            if (nume.isBlank() || post.isBlank() || ptype.isBlank()) continue
            // skip trailer options and broken entries
            if (!nume.all { it.isDigit() }) continue

            val player = try {
                app.get(
                    "$mainUrl/wp-json/zetaplayer/v2/$post/$ptype/$nume",
                    headers = browserHeaders,
                    referer = mainUrl,
                ).text.let { body -> tryParseJson<ZetaPlayerResult>(body) }
            } catch (e: Exception) {
                null
            } ?: continue

            val embedUrl = player.embedUrl?.trim().orEmpty()
            if (embedUrl.isBlank()) continue // type:false dead server

            // the wp-json value can be a plain URL or a full `<iframe src="...">` blob
            val strippedRegex = "src=\\\"([^\\\"]+)\\\"".toRegex()
            val embedSource = if (embedUrl.startsWith("<")) {
                strippedRegex.find(embedUrl)?.groupValues?.get(1) ?: embedUrl
            } else {
                embedUrl
            }

            try {
                found = when (player.type?.trim()) {
                    "mp4" -> handleCsPlayer(embedSource, callback) || found
                    "iframe" -> {
                        val evoplayer = if (
                            embedSource.contains("evostreamx", true)
                        ) {
                            runCatching {
                                EvoPlayer().extract(
                                    embedSource,
                                    mainUrl,
                                    subtitleCallback,
                                    callback,
                                )
                            }.getOrDefault(false)
                        } else {
                            false
                        }
                        evoplayer || loadExtractor(
                            embedSource,
                            referer = mainUrl,
                            subtitleCallback,
                            callback,
                        ) || found
                    }
                    else -> loadExtractor(embedSource, referer = mainUrl, subtitleCallback, callback) || found
                }
            } catch (e: Exception) {
                // one dead server must not kill the rest of the page
            }
        }
        return found
    }

    /** CS Player family: embed_url leads to an HTML player page. Two shapes in the wild:
     *  - multi-quality: `const ALL_QUALITIES = [{"html":"HD 720P","url":"...mp4?play=true",...}]`
     *  - single quality: `const ALL_QUALITIES = []` with the direct `...mp4?play=true` URL
     *    embedded in the page JS.
     *  Direct files resolve ONLY with `Referer: $mainUrl` (verified live, csNN.supercloud1.xyz). */
    private suspend fun handleCsPlayer(
        embedUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playerPage = try {
            app.get(embedUrl, headers = browserHeaders, referer = mainUrl).text
        } catch (e: Exception) {
            ""
        }

        data class Direct(val url: String, val label: String, val quality: Int)

        val directs: List<Direct> = if (playerPage.isNotBlank()) {
            val entries = parseAllQualities(playerPage)
            if (entries.isNotEmpty()) {
                entries.map { q ->
                    Direct(
                        url = withPlayParam(q.url),
                        label = q.label,
                        quality = qualityLabelToInt(q.label) ?: qualityFromUrl(q.url) ?: Qualities.Unknown.value,
                    )
                }
            } else {
                parseDirectMp4s(playerPage).map { url ->
                    val quality = qualityFromUrl(url) ?: Qualities.Unknown.value
                    Direct(url, "${quality}p", quality)
                }
            }
        } else emptyList()

        // last resort: follow the embed redirect chain and play the resolved file itself
        val links = if (directs.isNotEmpty()) {
            directs
        } else if (embedUrl.contains(".mp4")) {
            listOf(Direct(withPlayParam(embedUrl), "", Qualities.Unknown.value))
        } else {
            emptyList()
        }
        if (links.isEmpty()) return false

        links.forEach { link ->
            callback(
                ExtractorLink(
                    source = name,
                    name = "CS Player ${link.label}".trim(),
                    url = link.url,
                    referer = mainUrl,
                    quality = link.quality,
                    type = ExtractorLinkType.VIDEO,
                    headers = mapOf(
                        "User-Agent" to SITE_USER_AGENT,
                        "Referer" to mainUrl,
                    ),
                )
            )
        }
        return true
    }
}

/** wp-json/zetaplayer/v2 response */
data class ZetaPlayerResult(
    @com.fasterxml.jackson.annotation.JsonProperty("embed_url") val embedUrl: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("type") val type: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("msg") val msg: String? = null,
)
