package tv.own.owntv.core.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddonUrlsTest {

    @Test
    fun `manifest links are normalised`() {
        assertEquals("https://v3-cinemeta.strem.io/manifest.json", AddonUrls.normalizeManifestUrl(" https://v3-cinemeta.strem.io/manifest.json "))
        assertEquals("https://torrentio.strem.fun/realdebrid=KEY/manifest.json", AddonUrls.normalizeManifestUrl("stremio://torrentio.strem.fun/realdebrid=KEY/manifest.json"))
        assertEquals("https://example.com/addon/manifest.json", AddonUrls.normalizeManifestUrl("example.com/addon/"))
        assertEquals("http://192.168.1.5:7000/manifest.json", AddonUrls.normalizeManifestUrl("http://192.168.1.5:7000"))
        assertEquals("https://a.example/manifest.json?cfg=1", AddonUrls.normalizeManifestUrl("https://a.example?cfg=1"))
    }

    @Test
    fun `things that are not addon links are refused`() {
        assertNull(AddonUrls.normalizeManifestUrl(""))
        assertNull(AddonUrls.normalizeManifestUrl("not a url"))
        assertNull(AddonUrls.normalizeManifestUrl("magnet://abc"))
        assertNull(AddonUrls.normalizeManifestUrl("https:///manifest.json"))
    }

    @Test
    fun `resource paths follow the Stremio layout`() {
        val base = AddonUrls.baseOf("https://torrentio.strem.fun/realdebrid=KEY/manifest.json")
        assertEquals("https://torrentio.strem.fun/realdebrid=KEY", base)
        assertEquals("$base/stream/series/tt0944947:1:1.json", AddonUrls.resource(base, "stream", "series", "tt0944947:1:1"))
        assertEquals(
            "$base/catalog/movie/top/search=the%20matrix&skip=100.json",
            AddonUrls.resource(base, "catalog", "movie", "top", listOf("search" to "the matrix", "skip" to "100")),
        )
        assertEquals("$base/meta/movie/kitsu%2F1.json", AddonUrls.resource(base, "meta", "movie", "kitsu/1"))
        assertEquals("%C3%A9t%C3%A9%26%3D", AddonUrls.encode("été&="))
    }
}
