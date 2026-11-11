package com.cinesubz

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure parsing helpers (JSoup + regex only, no Cloudstream/Android dependencies)
 * so they can be unit tested on the JVM without the plugin stubs.
 */

/** A parsed card from search / main page / category listings. */
data class CardInfo(
    val title: String,
    val url: String,
    val posterUrl: String?,
    val isTv: Boolean,
)

/** A parsed series episode from the TvSeries details page. */
data class EpisodeInfo(
    val url: String,
    val season: Int?,
    val episode: Int?,
    val title: String?,
    val posterUrl: String?,
    /** raw date text as rendered by the site, e.g. "Jan. 20, 2008" */
    val date: String?,
)

data class ActorInfo(
    val name: String,
    val role: String?,
    val image: String?,
)

/** Parsed metadata from a movie / tvshow / episode details page. */
data class DetailsInfo(
    val title: String,
    val posterUrl: String?,
    /** IMDb score as a decimal string, e.g. "8.1" */
    val rating: String?,
    val year: Int?,
    /** raw duration text, e.g. "1h 47m" or "163 min" */
    val duration: String?,
    val plot: String?,
    val genres: List<String>,
    val actors: List<ActorInfo>,
)

/** One direct mp4 quality from a CS Player ALL_QUALITIES blob. */
data class QualityEntry(val label: String, val url: String)

/** Parsed result of the EvoStreamX FirePlayer getVideo response. */
data class EvoVideo(
    val securedLink: String?,
    val videoSource: String?,
    val hls: Boolean,
)

/* ---------- cards ---------- */

fun parseCard(root: Element): CardInfo? {
    val anchor = root.selectFirst("a[data-ptype]")
        ?: root.selectFirst("a.play4")
        ?: root.selectFirst("a[href]")
        ?: return null
    val href = anchor.attr("href").trim()
    if (!href.startsWith("http")) return null
    val title = anchor.attr("title").trim().ifBlank {
        (root.selectFirst("div.item-desc-title h3") ?: root.selectFirst("h3"))?.text()?.trim().orEmpty()
    }
    if (title.isBlank()) return null
    val imgEl = root.selectFirst("img.thumb")
    val poster = imgEl?.let {
        (it.attr("data-original").trim().ifBlank { it.attr("src").trim() })
            .takeIf { src -> src.startsWith("http") && !src.contains("placeholder", ignoreCase = true) }
    }
    val isTv = anchor.attr("data-ptype").equals("tvshows", true) || href.contains("/tvshows/")
    return CardInfo(title, href, poster, isTv)
}

/** Parses every listing card on a page. Handles both `display-item` (archives / search)
 * and `module-item` (homepage carousels) markup. */
fun parseCards(html: String): List<CardInfo> =
    Jsoup.parse(html)
        .select("div.display-item, div.module-item")
        .mapNotNull(::parseCard)
        .distinctBy { it.url }

/* ---------- details page ---------- */

fun parseDetails(html: String): DetailsInfo? {
    val doc = Jsoup.parse(html)
    val title = doc.selectFirst("div.details-title h3")?.text()?.trim().orEmpty()
    if (title.isBlank()) return null

    val poster = doc.selectFirst("img.poster-img")?.attr("src")?.trim()
        ?.takeIf { it.startsWith("http") }

    val rating = doc.selectFirst("span.data-imdb")?.text()
        ?.substringAfter("IMDb:", "")
        ?.trim()
        ?.split(" ")
        ?.firstOrNull()
        ?.takeIf { it.any { ch -> ch.isDigit() } }

    // MUST be scoped: the site's header nav contains /release/ year links too
    val year = doc.selectFirst("div.details-info a[href*='/release/']")?.text()
        ?.filter { it.isDigit() }?.takeIf { it.length in 2..4 }?.toIntOrNull()

    val duration = doc.selectFirst("[itemprop=duration]")?.text()?.trim()?.takeIf { it.isNotBlank() }
    val plot = doc.selectFirst("div.details-desc")?.text()?.trim()?.takeIf { it.isNotBlank() }
    val genres = doc.select("div.details-genre a")
        .map { it.text().trim() }
        .filter { it.isNotBlank() && it != "|" }
        .distinct()

    val actors = doc.select("div.zt-cast-card").mapNotNull { card ->
        val name = card.selectFirst("span.zt-cast-name")?.text()?.trim().orEmpty()
        if (name.isBlank()) return@mapNotNull null
        val role = card.selectFirst("span.zt-cast-role")?.text()?.trim()?.takeIf { it.isNotBlank() }
        val image = card.selectFirst("img")?.attr("src")?.trim()?.takeIf { it.startsWith("http") }
        ActorInfo(name, role, image)
    }

    return DetailsInfo(title, poster, rating, year, duration, plot, genres, actors)
}

/* ---------- episodes ---------- */

fun parseEpisodes(html: String): List<EpisodeInfo> {
    val doc = Jsoup.parse(html)
    return doc.select("ul.episodes-list[data-season]").flatMap { listEl ->
        val listSeason = listEl.attr("data-season").toIntOrNull()
        listEl.select("a.episode-link").mapNotNull { epEl ->
            val href = epEl.attr("href").trim()
            if (!href.startsWith("http")) return@mapNotNull null
            val season = listSeason ?: epEl.attr("data-season").toIntOrNull()
            val episode = epEl.attr("data-episode").toIntOrNull()
                ?: epEl.selectFirst("span.ep-num")?.text()?.trim()?.toIntOrNull()
            val title = epEl.selectFirst("span.ep-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            val poster = epEl.selectFirst("img")?.attr("src")?.trim()
                ?.takeIf { it.startsWith("http") && !it.contains("zt_backdrop") }
            val date = epEl.selectFirst("span.ep-date")?.text()?.trim()?.takeIf { it.isNotBlank() }
            EpisodeInfo(href, season, episode, title, poster, date)
        }
    }
}

/* ---------- CS Player (direct mp4) ---------- */

/** Matches {"html":"HD 720P","url":"https://...","default":false} inside ALL_QUALITIES. */
private val qualityEntryRegex =
    Regex("""\{\s*"html"\s*:\s*"([^"]+)"\s*,\s*"url"\s*:\s*"([^"]+)"[^}]*\}""")

fun parseAllQualities(playerPageHtml: String): List<QualityEntry> {
    val start = playerPageHtml.indexOf("ALL_QUALITIES")
    if (start < 0) return emptyList()
    return qualityEntryRegex.findAll(playerPageHtml.substring(start)).map { match ->
        QualityEntry(
            label = match.groupValues[1].trim(),
            url = match.groupValues[2].trim().replace("\\/", "/"),
        )
    }.filter { it.url.startsWith("http") }.toList()
}

/** Maps a quality label like "SD 480P" / "HD 720P" / "FHD 1080P" / "4K UHD" to an int, or null. */
fun qualityLabelToInt(label: String): Int? {
    Regex("""(\d{3,4})""").find(label)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    return when {
        label.contains("2160", false) || label.contains("4K", true) -> 2160
        label.contains("FHD", true) -> 1080
        label.contains("HD", true) -> 720
        label.contains("SD", true) -> 480
        else -> null
    }
}

/** Infers the quality from a direct link filename like "...-720p.mp4". */
fun qualityFromUrl(url: String): Int? =
    """[-_.](\d{3,4})p\.""".toRegex(RegexOption.IGNORE_CASE)
        .find(url)?.groupValues?.get(1)?.toIntOrNull()

/**
 * The CS Player now renders single-quality files with an EMPTY ALL_QUALITIES array,
 * but embeds the direct `...mp4?play=true` URL in the page JS. This extracts those.
 */
fun parseDirectMp4s(playerPageHtml: String): List<String> =
    Regex("""https?://[^"'\s<>]+\.mp4[^"'\s<>]*""")
        .findAll(playerPageHtml)
        .map { it.value.trim() }
        .filter { it.startsWith("http") }
        .distinct()
        .toList()

/** Direct video hosts serve the file only with ?play=true on the URL. */
fun withPlayParam(url: String): String =
    if (url.contains(".mp4") && !url.contains("play=")) {
        url + (if (url.contains('?')) "&" else "?") + "play=true"
    } else {
        url
    }

/* ---------- EvoStreamX (FirePlayer -> m3u8) ---------- */

/** Hash embedded in iframe src: https://evostreamx.top/video/{32-char-token}.
 * The wp-json embed_url is the whole `<iframe src="...">` markup, so `"` is a valid boundary. */
fun extractEvoHash(iframeSrc: String): String? {
    val match = Regex("""/video/([A-Za-z0-9]{32})(?:[/?#"']|$)""").find(iframeSrc)
    return match?.groupValues?.get(1)
}

fun parseEvoVideo(json: String): EvoVideo? {
    if (!json.contains("securedLink") && !json.contains("videoSource")) return null
    val secured = jsonString(json, "securedLink")?.takeIf { it.isNotBlank() }
    val source = jsonString(json, "videoSource")?.takeIf { it.isNotBlank() }
    if (secured == null && source == null) return null
    return EvoVideo(secured, source, json.contains("\"hls\"") && json.contains("true"))
}

/** Minimal string-field lookup tolerant of JS escaping. */
private fun jsonString(json: String, key: String): String? {
    val match = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)
        ?: return null
    return match.groupValues[1]
        .replace("\\/", "/")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
}
