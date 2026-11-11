package com.cinesubz

import org.jsoup.Jsoup
import org.junit.Test

/* All assertions use the stdlib `check(...)` so no test frameworks beyond JUnit4 are needed. */
class ParserTest {

    /* ---------- cards (search / latest / homepage) ---------- */

    @Test
    fun `parse search card`() {
        val html = """
            <div class="display-item"><div class="item-box">
              <a href="https://cinesubz.lk/movies/jailer-2-2026-sinhala-subtitles/" data-url="jailer-2-2026-sinhala-subtitles"
                 data-ptype="movies" title="Jailer 2 (2026) Sinhala Subtitles"></a>
              <img data-original="https://cinesubz.lk/wp-content/uploads/2026/10/Jailer-2-HD.jpg" class="thumb mli-thumb">
              <div class="item-desc"><div class="item-desc-title"><h3>Jailer 2 (2026) Sinhala Subtitles</h3></div></div>
            </div></div>
        """.trimIndent()
        val card = parseCard(Jsoup.parse(html).selectFirst("div.display-item")!!)
        requireNotNull(card)
        check(card.title == "Jailer 2 (2026) Sinhala Subtitles")
        check(card.url.endsWith("/movies/jailer-2-2026-sinhala-subtitles/"))
        check(!card.isTv)
        check(card.posterUrl == "https://cinesubz.lk/wp-content/uploads/2026/10/Jailer-2-HD.jpg")
    }

    @Test
    fun `card data-original fallback to src`() {
        val html = """
            <div class="display-item"><div class="item-box">
              <a href="https://cinesubz.lk/movies/foo/" data-ptype="movies" title="Foo"></a>
              <img class="thumb" src="https://cinesubz.lk/wp-content/uploads/foo.jpg">
            </div></div>
        """.trimIndent()
        val card = parseCard(Jsoup.parse(html).selectFirst("div.display-item")!!)
        requireNotNull(card)
        check(card.posterUrl == "https://cinesubz.lk/wp-content/uploads/foo.jpg")
    }

    @Test
    fun `homepage module card with play4 anchor is tv`() {
        val html = """
            <div class="home-module stream movies trending" data-module-id="featured-trending">
              <div class="module-content owl-carousel">
                <div class="module-item"><div class="item-size">
                <a href="https://cinesubz.lk/tvshows/game-of-thrones-2011-sinhala-subtitles/" data-ptype="tvshows"
                   class="play4" title="Game of Thrones (2011)">
                  <img class="thumb mli-thumb" src="https://cinesubz.lk/wp-content/uploads/2021/08/got.jpg">
                </a>
                <div class="item-desc"><div class="item-desc-title"><h3>Game of Thrones (2011)</h3></div></div>
                </div></div>
              </div>
            </div>
        """.trimIndent()
        val card = parseCard(Jsoup.parse(html).selectFirst("div.module-item")!!)
        requireNotNull(card)
        check(card.title == "Game of Thrones (2011)")
        check(card.isTv)
        check(card.posterUrl == "https://cinesubz.lk/wp-content/uploads/2021/08/got.jpg")
    }

    @Test
    fun `parseCards handles mixed page and dedupes`() {
        val html = """
            <div class="display-item"><div class="item-box">
              <a href="https://cinesubz.lk/movies/a/" data-ptype="movies" title="A"></a>
              <img class="thumb" src="https://cinesubz.lk/a.jpg">
            </div></div>
            <div class="display-item"><div class="item-box">
              <a href="https://cinesubz.lk/tvshows/b/" data-ptype="tvshows" title="B"></a>
              <img class="thumb" src="https://cinesubz.lk/b.jpg">
            </div></div>
            <div class="module-item"><div class="item-size">
              <a href="https://cinesubz.lk/movies/a/" data-ptype="movies" title="A dup"></a>
              <img class="thumb" src="https://cinesubz.lk/a.jpg">
            </div></div>
        """.trimIndent()
        val cards = parseCards(html)
        check(cards.size == 2)
        check(cards.first { it.title == "A" }.title == "A") // first occurrence wins
        check(cards.any { it.isTv })
    }

    /* ---------- details page ---------- */

    @Test
    fun `parse movie details`() {
        val html = """
            <div class="details-title"><h3>Jailer 2 (2026) Sinhala Subtitles></h3></div>
            <img class="poster-img" src="https://cinesubz.lk/poster.jpg">
            <span class="data-imdb v2"> IMDb: 8.1 <span class="data-imdb-votes">(7.2K)</span> <b id="repimdb"></b></span>
            <span itemprop="duration" class="data-views">163 min</span>
            <div class="details-info"><p><strong>Year:</strong><a href="https://cinesubz.lk/release/2026/">2026</a></p></div>
            <div class="details-genre">
              <a href="https://cinesubz.lk/genre/action/">Action</a>, <a href="https://cinesubz.lk/genre/comedy/">Comedy</a>,
            </div>
            <div class="details-desc"><p>Online Watch / Direct Download</p></div>
            <div class="zt-cast-card">
              <a class="zt-cast-link" title="Rajinikanth">
                <div class="zt-cast-image"><img src="https://image.tmdb.org/t/p/w185/x.jpg"></div>
                <div class="zt-cast-info"><span class="zt-cast-name">Rajinikanth</span><span class="zt-cast-role">Muthuvel Pandian</span></div>
              </a>
            </div>
        """.trimIndent()
        val details = parseDetails(html)
        requireNotNull(details)
        check(details.title.startsWith("Jailer 2"))
        check(details.posterUrl == "https://cinesubz.lk/poster.jpg")
        check(details.rating == "8.1")
        check(details.year == 2026)
        check(details.duration == "163 min")
        check(details.plot == "Online Watch / Direct Download")
        check(details.genres.containsAll(listOf("Action", "Comedy")))
        check(details.actors.size == 1)
        check(details.actors.first().name == "Rajinikanth")
        check(details.actors.first().role == "Muthuvel Pandian")
        check(details.actors.first().image == "https://image.tmdb.org/t/p/w185/x.jpg")
    }

    @Test
    fun `parseDetails fails gracefully on blank page`() {
        check(parseDetails("<div><p>nothing</p></div>") == null)
    }

    @Test
    fun `year link is scoped to details-info (nav Years link ignored)`() {
        // real pages render a Years submenu EARLIER in the document with /release/ links;
        // this regression test keeps parseDetails scoped to div.details-info
        val html = """
            <li class="menu-item menu-item-has-children"><a href="https://cinesubz.net/release/2022/"
                class="menu-link"><strong>Years</strong></a>
              <ul class="sub-menu"><li><a href="https://cinesubz.net/release/2026/">2026</a></li></ul>
            </li>
            <div class="details-title"><h3>Jailer 2 (2026)</h3></div>
            <div class="details-info"><p><strong>Year:</strong>
              <a href="https://cinesubz.lk/release/2011/">2011</a></p></div>
        """.trimIndent()
        val details = parseDetails(html)
        requireNotNull(details)
        check(details.year == 2011)  // not 2026 from the nav, not null from "Years"
    }

    /* ---------- episodes ---------- */

    @Test
    fun `parse tv seasons and episodes`() {
        val html = """
            <ul id="season-listep-1" class="episodes-list active" data-season="1">
              <li class=" active">
                <a class="episode-link" href="https://cinesubz.lk/episodes/breaking-bad-1x1/" data-pid="9428" data-season="1" data-episode="1">
                  <span class="ep-num">1</span>
                  <span class="ep-thumb"><img src="https://cinesubz.lk/wp-content/themes/zetaflix/assets/img/no/zt_backdrop.png"></span>
                  <span class="data"><span class="ep-title">Pilot</span><span class="ep-date">Jan. 20, 2008</span></span>
                </a>
              </li>
              <li class=" ">
                <a class="episode-link" href="https://cinesubz.lk/episodes/breaking-bad-1x2/" data-pid="9430" data-season="1" data-episode="2">
                  <span class="ep-num">2</span>
                  <span class="ep-thumb"><img src="https://cinesubz.lk/wp-content/uploads/ep2.jpg"></span>
                  <span class="data"><span class="ep-title">Cat's in the Bag...</span><span class="ep-date">Jan. 27, 2008</span></span>
                </a>
              </li>
            </ul>
            <ul id="season-listep-2" class="episodes-list" data-season="2">
              <li>
                <a class="episode-link" href="https://cinesubz.lk/episodes/breaking-bad-2x1/" data-pid="9500" data-season="2" data-episode="1">
                  <span class="data"><span class="ep-title">Seven Thirty-Seven</span><span class="ep-date">Mar. 8, 2009</span></span>
                </a>
              </li>
            </ul>
        """.trimIndent()
        val episodes = parseEpisodes(html)
        check(episodes.size == 3)
        with(episodes[0]) {
            check(season == 1 && episode == 1)
            check(title == "Pilot")
            check(date == "Jan. 20, 2008")
            // placeholder backdrop is filtered out
            check(posterUrl == null)
        }
        with(episodes[1]) {
            check(posterUrl == "https://cinesubz.lk/wp-content/uploads/ep2.jpg")
        }
        with(episodes[2]) {
            check(season == 2 && episode == 1)
            check(title == "Seven Thirty-Seven")
        }
    }

    /* ---------- CS Player ALL_QUALITIES ---------- */

    @Test
    fun `parse cs player qualities`() {
        val page = """
            <script>
            const PLAYLIST = {"file": ".."};
            const ALL_QUALITIES = [
                {"html":"SD 480P","url":"https://cs07.supercloud1.xyz/f1.mp4?play=true","default":false},
                {"html":"HD 720P","url":"https://cs07.supercloud1.xyz/f2.mp4?play=true","default":true},
                {"html":"FHD 1080P","url":"https://cs07.supercloud1.xyz/f3.mp4?play=true","default":false}
            ];
            const CSX = {"on":false,"t":0,"k":""};
            </script>
        """.trimIndent()
        val entries = parseAllQualities(page)
        check(entries.size == 3)
        check(entries[0].label == "SD 480P")
        check(entries[0].url == "https://cs07.supercloud1.xyz/f1.mp4?play=true")
        check(entries[2].label == "FHD 1080P")
        check(entries[2].url.endsWith("f3.mp4?play=true"))
    }

    @Test
    fun `parse cs player qualities handles escaped urls`() {
        val page = """
            const ALL_QUALITIES = [{"html":"HD 720P","url":"https:\/\/cs07.supercloud1.xyz\/f2.mp4?play=true","default":true}];
        """.trimIndent()
        val entries = parseAllQualities(page)
        check(entries.size == 1)
        check(entries[0].url == "https://cs07.supercloud1.xyz/f2.mp4?play=true")
    }

    @Test
    fun `no qualities on player without const`() {
        check(parseAllQualities("<html>random player</html>").isEmpty())
        check(parseAllQualities("const ALL_QUALITIES = [];").isEmpty())
    }

    @Test
    fun `quality label mapping`() {
        check(qualityLabelToInt("SD 480P") == 480)
        check(qualityLabelToInt("HD 720P") == 720)
        check(qualityLabelToInt("FHD 1080P") == 1080)
        check(qualityLabelToInt("4K UHD 2160P") == 2160)
        check(qualityLabelToInt("Unknown XYZ") == null)
    }

    @Test
    fun `quality inferred from direct link filename`() {
        check(qualityFromUrl("https://cs07.supercloud1.xyz/Movie-720p.mp4?play=true") == 720)
        check(qualityFromUrl("https://cs07.supercloud1.xyz/Movie%20x-1080p.mp4") == 1080)
        check(qualityFromUrl("https://cs07.supercloud1.xyz/Show.S01E01.mp4") == null)
    }

    @Test
    fun `single-quality player page without ALL_QUALITIES entries`() {
        // verified live: const ALL_QUALITIES = []; with the direct URL in page JS
        val page = """
            const file = 'https://cs01.supercloud1.xyz/' +
                encodeURIComponent('CineSubz.com - Show.S01E01.mp4');
            const ALL_QUALITIES = [];
            //"https://cs01.supercloud1.xyz/CineSubz.com%20-%20Show.S01E01.mp4?play=true"
        """.trimIndent().replace("//\"", "")
        val entries = parseAllQualities(page)
        check(entries.isEmpty())
        val direct = parseDirectMp4s(page)
        check(direct == listOf("https://cs01.supercloud1.xyz/CineSubz.com%20-%20Show.S01E01.mp4?play=true"))
        check(qualityFromUrl(direct.first()) == null)
        check(withPlayParam(direct.first()) == direct.first()) // play param already present
    }

    @Test
    fun `withPlayParam appends play=true`() {
        check(withPlayParam("https://host/movie.mp4") == "https://host/movie.mp4?play=true")
        check(withPlayParam("https://host/movie.mp4?play=true") == "https://host/movie.mp4?play=true")
        check(withPlayParam("https://host/page") == "https://host/page")
    }

    /* ---------- EvoStreamX ---------- */

    @Test
    fun `extract evo hash from iframe src`() {
        val hash = "ab12cd34ef56ab12cd34ef56ab12cd34"
        check(extractEvoHash("https://evostreamx.top/video/$hash") == hash)
        check(extractEvoHash("https://evostreamx.top/video/$hash?src=cinesubz") == hash)
        // hash extraction is host-agnostic; the provider only routes evostreamx URLs here
        check(extractEvoHash("https://evostreamx2.top/video/$hash") == hash)
        check(extractEvoHash("https://evostreamx.top/video/tooshort") == null)
    }

    @Test
    fun `extract evo hash from full iframe markup (real wp-json shape)`() {
        // the zetaplayer embed_url for Evo is the whole <iframe> HTML, verified live
        check(
            extractEvoHash(
                """<iframe src="https://evostreamx.top/video/e73fecc08ee9b0e1a876614ec3178bac" width="100%" height="360" frameborder="0"></iframe>"""
            ) == "e73fecc08ee9b0e1a876614ec3178bac"
        )
    }

    @Test
    fun `parse evo getVideo response`() {
        // real response shape verified live
        val json = """{"hls":true,"videoImage":null,"videoSource":"https://evostreamx.top/cdn/hls/xyz/master.txt",
            "securedLink":"https://evostreamx.top/hls/hash/master.m3u8?md5=abc&expires=1","videoSources":[]}"""
        val video = parseEvoVideo(json)
        requireNotNull(video)
        check(video.hls)
        check(video.securedLink!!.contains("master.m3u8"))
        check(video.videoSource!!.endsWith("master.txt"))
    }

    @Test
    fun `parse evo response ignores error json`() {
        check(parseEvoVideo("""{"error":"not found"}""") == null)
        check(parseEvoVideo("""{"securedLink":"","videoSource":""}""") == null)
    }
}
