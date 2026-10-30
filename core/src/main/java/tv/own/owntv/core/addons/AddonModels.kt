package tv.own.owntv.core.addons

/**
 * Stremio addon protocol types, as far as OwnTV reads them. Every field an addon may leave out is
 * nullable or empty here, because addons in the wild leave out nearly everything but `id` and `name`.
 *
 * Spec: https://github.com/Stremio/stremio-addon-sdk/tree/master/docs/api
 */

/** An addon the user installed: where it lives ([transportUrl], always ending in `manifest.json`) and what it said it is. */
data class InstalledAddon(
    val transportUrl: String,
    val manifest: AddonManifest,
) {
    /** The URL every resource path hangs off: [transportUrl] without `/manifest.json`. */
    val baseUrl: String get() = AddonUrls.baseOf(transportUrl)
}

data class AddonManifest(
    val id: String,
    val version: String,
    val name: String,
    val description: String? = null,
    val logo: String? = null,
    val background: String? = null,
    /** Content types the addon serves at all, e.g. `movie`, `series`, `tv`, `channel`. */
    val types: List<String> = emptyList(),
    /** Manifest-wide id prefixes (`tt`, `kitsu:`). Null = any id. A resource may override it. */
    val idPrefixes: List<String>? = null,
    val resources: List<AddonResource> = emptyList(),
    val catalogs: List<AddonCatalog> = emptyList(),
    /** The addon wants to be configured on its own website before it will return anything. */
    val configurationRequired: Boolean = false,
    val adult: Boolean = false,
) {
    /**
     * Whether this addon answers [resource] (`catalog`, `meta`, `stream`, `subtitles`) for [type] and,
     * when given, [id]. A resource with no types or prefixes of its own inherits the manifest's.
     */
    fun supports(resource: String, type: String, id: String? = null): Boolean {
        val entry = resources.firstOrNull { it.name == resource } ?: return false
        val types = entry.types ?: this.types
        if (types.isNotEmpty() && type !in types) return false
        val prefixes = entry.idPrefixes ?: idPrefixes
        if (id != null && !prefixes.isNullOrEmpty() && prefixes.none { id.startsWith(it) }) return false
        return true
    }
}

data class AddonResource(
    val name: String,
    val types: List<String>? = null,
    val idPrefixes: List<String>? = null,
)

data class AddonCatalog(
    val type: String,
    val id: String,
    val name: String?,
    val extra: List<CatalogExtra> = emptyList(),
) {
    /** Some catalog extras are mandatory (a search box, a genre); such a catalog can't be browsed as a plain row. */
    val browsable: Boolean get() = extra.none { it.isRequired }
    val searchable: Boolean get() = extra.any { it.name == EXTRA_SEARCH }
    val pageable: Boolean get() = extra.any { it.name == EXTRA_SKIP }
    val genres: List<String> get() = extra.firstOrNull { it.name == EXTRA_GENRE }?.options.orEmpty()

    companion object {
        const val EXTRA_SEARCH = "search"
        const val EXTRA_SKIP = "skip"
        const val EXTRA_GENRE = "genre"
    }
}

data class CatalogExtra(
    val name: String,
    val isRequired: Boolean = false,
    val options: List<String> = emptyList(),
)

/** One tile in a catalog: enough to draw a poster and open the title. */
data class MetaPreview(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    /** `poster` (2:3, the default), `landscape` (16:9) or `square`. */
    val posterShape: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
)

/** The full title: everything in the preview plus cast, runtime and, for a series, its episodes. */
data class MetaDetail(
    val preview: MetaPreview,
    val runtime: String? = null,
    val cast: List<String> = emptyList(),
    val director: List<String> = emptyList(),
    /** Episodes for a series, or the parts of a channel. Empty for a movie, whose stream id is the meta id. */
    val videos: List<MetaVideo> = emptyList(),
) {
    val id: String get() = preview.id
    val type: String get() = preview.type

    /**
     * Whether the title is played one video at a time. A movie that lists itself as its only video
     * (Kitsu does) is still a movie: Stremio asks for its streams by the meta id.
     */
    val episodic: Boolean get() = videos.isNotEmpty() && !(videos.size == 1 && videos[0].id == id)

    /** Season numbers in order, specials (season 0) last, as Stremio itself lists them. */
    val seasons: List<Int> get() = videos.mapNotNull { it.season }.distinct().sortedWith(compareBy({ it == 0 }, { it }))

    /** A channel's videos, which carry no season at all, in the addon's order. Empty when there are seasons. */
    val unseasoned: List<MetaVideo> get() = if (seasons.isEmpty()) videos else emptyList()

    fun episodes(season: Int): List<MetaVideo> =
        videos.filter { it.season == season }.sortedBy { it.episode ?: Int.MAX_VALUE }
}

data class MetaVideo(
    /** The id `stream` requests are made with, e.g. `tt0944947:1:1`. */
    val id: String,
    val title: String,
    val season: Int? = null,
    val episode: Int? = null,
    val released: String? = null,
    val thumbnail: String? = null,
    val overview: String? = null,
)

/**
 * A playable stream. Only direct HTTP(S) links are kept by the parser, so [url] is always set:
 * torrents (`infoHash`), YouTube ids and external web pages are dropped (see [AddonJson.streams]).
 */
data class AddonStream(
    val url: String,
    /** Usually the source and quality, e.g. "Torrentio\n4K [RD+]". */
    val name: String? = null,
    /** Usually the file name, size and seeders; `description` in newer addons. Several lines. */
    val title: String? = null,
    /** Headers the addon says the request needs (`behaviorHints.proxyHeaders.request`). */
    val headers: Map<String, String> = emptyMap(),
    val filename: String? = null,
    val sizeBytes: Long? = null,
    /** Streams sharing a group come from the same release; used to keep quality when moving to the next episode. */
    val bingeGroup: String? = null,
    val subtitles: List<AddonSubtitle> = emptyList(),
)

data class AddonSubtitle(val id: String?, val url: String, val lang: String?)

/** A catalog page and the addon it came from, so a tile can be opened against the right addon. */
data class CatalogPage(
    val addon: InstalledAddon,
    val catalog: AddonCatalog,
    val items: List<MetaPreview>,
)
