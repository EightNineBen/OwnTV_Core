package tv.own.owntv.core.addons

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonJsonTest {

    private val cinemeta = JSONObject(
        """
        {"id":"com.linvo.cinemeta","version":"3.0.12","name":"Cinemeta","types":["movie","series"],
         "idPrefixes":["tt"],
         "resources":["catalog",{"name":"meta","types":["movie","series"],"idPrefixes":["tt"]},"addon_catalog"],
         "catalogs":[
           {"type":"movie","id":"top","name":"Popular","extra":[{"name":"genre","options":["Action","Drama"]},{"name":"search"},{"name":"skip"}]},
           {"type":"series","id":"imdbRating","name":"Featured","extraSupported":["genre","skip"],"extraRequired":["genre"]}
         ]}
        """,
    )

    @Test
    fun `manifest resources catalogs and extras`() {
        val m = AddonJson.manifest(cinemeta)
        assertEquals("Cinemeta", m.name)
        assertEquals(listOf("catalog", "meta", "addon_catalog"), m.resources.map { it.name })
        assertTrue(m.supports("meta", "series", "tt0944947"))
        assertFalse(m.supports("meta", "series", "kitsu:1"))
        assertFalse(m.supports("stream", "movie", "tt1"))
        val (top, featured) = m.catalogs
        assertTrue(top.browsable && top.searchable && top.pageable)
        assertEquals(listOf("Action", "Drama"), top.genres)
        // The legacy extraRequired form still marks a catalog that can't be a plain row.
        assertFalse(featured.browsable)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a body without id and name is not a manifest`() {
        AddonJson.manifest(JSONObject("""{"streams":[]}"""))
    }

    @Test
    fun `configuration required is read from behaviour hints`() {
        val m = AddonJson.manifest(JSONObject("""{"id":"x","name":"X","resources":["stream"],"behaviorHints":{"configurable":true,"configurationRequired":true}}"""))
        assertTrue(m.configurationRequired)
    }

    @Test
    fun `catalog items skip broken entries`() {
        val items = AddonJson.catalogItems(
            JSONObject("""{"metas":[{"id":"tt1","type":"movie","name":"One","poster":"p","imdbRating":"7.1","genres":["Drama"]},{"name":"no id"},5,{"id":"tt2","type":"movie","year":1999,"genre":"Action"}]}"""),
        )
        assertEquals(listOf("tt1", "tt2"), items.map { it.id })
        assertEquals("1999", items[1].releaseInfo)
        assertEquals("tt2", items[1].name)
        assertEquals(listOf("Action"), items[1].genres)
    }

    @Test
    fun `series meta lists seasons with specials last`() {
        val meta = AddonJson.meta(
            JSONObject(
                """{"meta":{"id":"tt0944947","type":"series","name":"Game of Thrones","releaseInfo":"2011-2019","runtime":"57 min","cast":["A","B"],
                "videos":[{"id":"tt0944947:2:1","title":"S2E1","season":2,"episode":1},
                          {"id":"tt0944947:1:2","name":"Second","season":1,"number":2},
                          {"id":"tt0944947:0:1","title":"Special","season":0,"episode":1},
                          {"id":"tt0944947:1:1","title":"Winter Is Coming","season":"1","episode":1,"thumbnail":null}]}}""",
            ),
        )!!
        assertEquals(listOf(1, 2, 0), meta.seasons)
        assertEquals(listOf("tt0944947:1:1", "tt0944947:1:2"), meta.episodes(1).map { it.id })
        assertEquals("Second", meta.episodes(1)[1].title)
        assertNull(meta.episodes(1)[0].thumbnail)
        assertEquals(listOf("A", "B"), meta.cast)
    }

    @Test
    fun `no meta in the body is null`() {
        assertNull(AddonJson.meta(JSONObject("""{"meta":null}""")))
    }

    @Test
    fun `only direct links survive, with their headers`() {
        val streams = AddonJson.streams(
            JSONObject(
                """{"streams":[
                  {"name":"Torrentio\n4k","title":"Movie.2160p.mkv\n💾 20 GB","infoHash":"abc","fileIdx":0},
                  {"name":"[RD+] Torrentio\n4k","description":"Movie.2160p.mkv","url":"https://rd.example/dl/1",
                   "behaviorHints":{"bingeGroup":"torrentio|4k","filename":"Movie.2160p.mkv","videoSize":21474836480,
                     "proxyHeaders":{"request":{"User-Agent":"Custom/1","Referer":"https://x"}}},
                   "subtitles":[{"id":"1","url":"https://s/1.srt","lang":"eng"}]},
                  {"ytId":"dQw4w9WgXcQ"},
                  {"externalUrl":"https://www.netflix.com/title/1"},
                  {"url":"magnet:?xt=urn:btih:abc"}
                ]}""",
            ),
        )
        assertEquals(1, streams.size)
        val s = streams.single()
        assertEquals("https://rd.example/dl/1", s.url)
        assertEquals("Movie.2160p.mkv", s.title)
        assertEquals(21474836480L, s.sizeBytes)
        assertEquals(mapOf("User-Agent" to "Custom/1", "Referer" to "https://x"), s.headers)
        assertEquals("torrentio|4k", s.bingeGroup)
        assertEquals("eng", s.subtitles.single().lang)
    }
}
