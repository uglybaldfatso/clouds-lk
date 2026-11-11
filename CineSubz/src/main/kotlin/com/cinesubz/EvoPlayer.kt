package com.cinesubz

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities

/**
 * EvoStreamX "Evo Player" (FirePlayer) extractor.
 *
 * CineSubz embeds this as `type:"iframe"` with `https://evostreamx.top/video/{hash}` pages.
 * The page packs its JS (dean-Edwards packer); unpacked it POSTs
 * `player/index.php?data={hash}&do=getVideo` with body `hash={hash}&r={referrer}`,
 * which responds with JSON containing `securedLink` (playable master.m3u8) and
 * `videoSource` (HLS master.txt fallback). The evostreamx host 404s without a
 * Referer, so one is always required.
 */
class EvoPlayer : ExtractorApi() {
    override val name = "EvoStreamX"
    override val mainUrl = "https://evostreamx.top"
    override val requiresReferer = true

    /** ExtractorApi entry point (returns Unit by contract). */
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extract(url, referer, subtitleCallback, callback)
    }

    /** Returns true if any playable link was emitted. */
    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val hash = extractEvoHash(url) ?: return false
        val effectiveReferer = referer?.takeIf { it.isNotBlank() } ?: "https://cinesubz.lk/"

        val body = try {
            app.post(
                "$mainUrl/player/index.php?data=$hash&do=getVideo",
                data = mapOf("hash" to hash, "r" to effectiveReferer),
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to effectiveReferer,
                ),
            ).text
        } catch (e: Exception) {
            return false
        }

        val video = parseEvoVideo(body) ?: return false
        val streamUrl = video.securedLink ?: video.videoSource ?: return false

        return if (streamUrl.contains(".m3u8")) {
            val links = M3u8Helper.generateM3u8(
                source = name,
                streamUrl = streamUrl,
                referer = "$mainUrl/",
            )
            links.forEach(callback)
            links.isNotEmpty()
        } else {
            callback(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = streamUrl,
                    referer = "$mainUrl/",
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.VIDEO,
                    headers = mapOf("Referer" to "$mainUrl/"),
                )
            )
            true
        }
    }
}
