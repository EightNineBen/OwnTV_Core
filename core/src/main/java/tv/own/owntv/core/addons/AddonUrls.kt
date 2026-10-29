package tv.own.owntv.core.addons

/**
 * Builds addon request URLs the way Stremio does: `{base}/{resource}/{type}/{id}[/{extra}].json`,
 * where extra is `name=value` pairs joined by `&`.
 */
object AddonUrls {

    private const val MANIFEST = "manifest.json"

    /**
     * What the user typed or pasted, as a manifest URL: `stremio://` becomes `https://`, a bare host
     * gains `https://`, and a URL that stops short of `manifest.json` gets it appended.
     * Null when it can't be an addon URL at all.
     */
    fun normalizeManifestUrl(input: String): String? {
        var url = input.trim()
        if (url.isEmpty() || url.any { it.isWhitespace() }) return null
        url = when {
            url.startsWith("stremio://", ignoreCase = true) -> "https://" + url.substring("stremio://".length)
            url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) -> url
            url.contains("://") -> return null
            else -> "https://$url"
        }
        val path = url.substringBefore('?')
        if (!path.endsWith("/$MANIFEST")) {
            // Keep any query (a few addons carry their config there) after the appended file name.
            val query = url.substring(path.length)
            url = path.trimEnd('/') + "/" + MANIFEST + query
        }
        if (url.substringAfter("://").substringBefore('/').isBlank()) return null
        return url
    }

    /** The manifest URL without its file name: the root every resource path is built on. */
    fun baseOf(transportUrl: String): String {
        val path = transportUrl.substringBefore('?')
        return path.removeSuffix(MANIFEST).trimEnd('/')
    }

    fun resource(
        baseUrl: String,
        resource: String,
        type: String,
        id: String,
        extra: List<Pair<String, String>> = emptyList(),
    ): String = buildString {
        append(baseUrl.trimEnd('/'))
        append('/').append(encode(resource))
        append('/').append(encode(type))
        append('/').append(encode(id))
        if (extra.isNotEmpty()) {
            append('/')
            append(extra.joinToString("&") { (name, value) -> encode(name) + "=" + encode(value) })
        }
        append(".json")
    }

    fun catalog(addon: InstalledAddon, catalog: AddonCatalog, skip: Int = 0, genre: String? = null, search: String? = null): String {
        val extra = buildList {
            if (!search.isNullOrBlank()) add(AddonCatalog.EXTRA_SEARCH to search.trim())
            if (!genre.isNullOrBlank()) add(AddonCatalog.EXTRA_GENRE to genre)
            if (skip > 0) add(AddonCatalog.EXTRA_SKIP to skip.toString())
        }
        return resource(addon.baseUrl, "catalog", catalog.type, catalog.id, extra)
    }

    fun meta(addon: InstalledAddon, type: String, id: String): String = resource(addon.baseUrl, "meta", type, id)

    fun stream(addon: InstalledAddon, type: String, videoId: String): String = resource(addon.baseUrl, "stream", type, videoId)

    /**
     * Percent-encodes a path segment. Like JavaScript's encodeURIComponent, except `:` stays as it is:
     * episode ids (`tt0944947:1:1`) are sent raw by Stremio, and static addons hosted as plain files
     * are named that way, so an encoded colon would miss them.
     */
    internal fun encode(value: String): String {
        val out = StringBuilder(value.length)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xFF
            val ch = c.toChar()
            if (c < 0x80 && (ch.isLetterOrDigit() || ch in UNRESERVED)) {
                out.append(ch)
            } else {
                out.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
            }
        }
        return out.toString()
    }

    private const val UNRESERVED = "-_.!~*'():"
    private const val HEX = "0123456789ABCDEF"
}
