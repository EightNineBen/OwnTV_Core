package tv.own.owntv.core.addons

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads addon responses. Lenient by design: a field of the wrong shape is skipped rather than failing
 * the whole response, since one odd item in a catalog of a hundred shouldn't empty the row.
 */
object AddonJson {

    /** Throws [IllegalArgumentException] when the body has no `id` or `name`, i.e. isn't a manifest. */
    fun manifest(json: JSONObject): AddonManifest {
        val id = json.str("id")
        val name = json.str("name")
        require(id != null && name != null) { "Not an addon manifest" }
        val hints = json.optJSONObject("behaviorHints")
        return AddonManifest(
            id = id,
            version = json.str("version").orEmpty(),
            name = name,
            description = json.str("description"),
            logo = json.str("logo"),
            background = json.str("background"),
            types = json.strings("types"),
            idPrefixes = json.optJSONArray("idPrefixes")?.let { json.strings("idPrefixes") },
            resources = json.optJSONArray("resources").objectsOrStrings().mapNotNull(::resource),
            catalogs = json.optJSONArray("catalogs").objects().mapNotNull(::catalog),
            configurationRequired = hints?.optBoolean("configurationRequired") == true,
            adult = hints?.optBoolean("adult") == true,
        )
    }

    /** A resource is either a bare name (`"stream"`) or an object with its own types and prefixes. */
    private fun resource(entry: Any): AddonResource? = when (entry) {
        is String -> entry.takeIf { it.isNotBlank() }?.let { AddonResource(it) }
        is JSONObject -> entry.str("name")?.let { name ->
            AddonResource(
                name = name,
                types = entry.optJSONArray("types")?.let { entry.strings("types") },
                idPrefixes = entry.optJSONArray("idPrefixes")?.let { entry.strings("idPrefixes") },
            )
        }
        else -> null
    }

    private fun catalog(json: JSONObject): AddonCatalog? {
        val type = json.str("type") ?: return null
        val id = json.str("id") ?: return null
        val extra = json.optJSONArray("extra").objects().mapNotNull { e ->
            e.str("name")?.let { CatalogExtra(it, e.optBoolean("isRequired"), e.strings("options")) }
        }.ifEmpty {
            // The older form: extraSupported names what may be sent, extraRequired what must be.
            val required = json.strings("extraRequired").toSet()
            json.strings("extraSupported").map { CatalogExtra(it, it in required) }
        }
        return AddonCatalog(type = type, id = id, name = json.str("name"), extra = extra)
    }

    fun catalogItems(json: JSONObject): List<MetaPreview> =
        json.optJSONArray("metas").objects().mapNotNull(::preview)

    /** Null when the response holds no `meta` (the addon doesn't know the title). */
    fun meta(json: JSONObject): MetaDetail? {
        val meta = json.optJSONObject("meta") ?: return null
        val preview = preview(meta) ?: return null
        return MetaDetail(
            preview = preview,
            runtime = meta.str("runtime"),
            cast = meta.strings("cast"),
            director = meta.strings("director"),
            videos = meta.optJSONArray("videos").objects().mapNotNull(::video),
        )
    }

    private fun preview(json: JSONObject): MetaPreview? {
        val id = json.str("id") ?: return null
        val type = json.str("type") ?: return null
        return MetaPreview(
            id = id,
            type = type,
            name = json.str("name") ?: id,
            poster = json.str("poster"),
            posterShape = json.str("posterShape"),
            background = json.str("background"),
            logo = json.str("logo"),
            description = json.str("description"),
            releaseInfo = json.str("releaseInfo") ?: json.opt("year")?.takeIf { it != JSONObject.NULL }?.toString(),
            imdbRating = json.str("imdbRating"),
            genres = json.strings("genres").ifEmpty { json.strings("genre") },
        )
    }

    private fun video(json: JSONObject): MetaVideo? {
        val id = json.str("id") ?: return null
        return MetaVideo(
            id = id,
            // Older addons call it name; a few send neither, and the episode number labels it instead.
            title = json.str("title") ?: json.str("name").orEmpty(),
            season = json.int("season"),
            // `number` is the pre-v1 spelling of `episode`.
            episode = json.int("episode") ?: json.int("number"),
            released = json.str("released"),
            thumbnail = json.str("thumbnail"),
            overview = json.str("overview") ?: json.str("description"),
        )
    }

    /**
     * The direct-link streams in a `stream` response. Torrents, YouTube ids and external links are
     * left out: OwnTV plays what a debrid service (or any HTTP host) hands it, and nothing else.
     */
    fun streams(json: JSONObject): List<AddonStream> =
        json.optJSONArray("streams").objects().mapNotNull(::stream)

    private fun stream(json: JSONObject): AddonStream? {
        val url = json.str("url") ?: return null
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return null
        val hints = json.optJSONObject("behaviorHints")
        val headers = hints?.optJSONObject("proxyHeaders")?.optJSONObject("request")?.let { request ->
            request.keys().asSequence().mapNotNull { key -> request.str(key)?.let { key to it } }.toMap()
        }.orEmpty()
        return AddonStream(
            url = url,
            name = json.str("name"),
            title = json.str("description") ?: json.str("title"),
            headers = headers,
            filename = hints?.str("filename"),
            sizeBytes = hints?.optLong("videoSize")?.takeIf { it > 0 },
            bingeGroup = hints?.str("bingeGroup"),
            subtitles = json.optJSONArray("subtitles").objects().mapNotNull { s ->
                s.str("url")?.let { AddonSubtitle(s.str("id"), it, s.str("lang")) }
            },
        )
    }

    // -- org.json helpers: missing, null and blank all read as absent. ----------------------------

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else opt(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    private fun JSONObject.int(key: String): Int? = when (val v = opt(key)) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull()
        else -> null
    }

    /** A string list, accepting a lone string where a list was expected. */
    private fun JSONObject.strings(key: String): List<String> = when (val v = opt(key)) {
        is JSONArray -> (0 until v.length()).mapNotNull { i ->
            v.opt(i)?.takeIf { it != JSONObject.NULL && it !is JSONObject && it !is JSONArray }
                ?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }
        is String -> listOfNotNull(v.trim().takeIf { it.isNotEmpty() })
        else -> emptyList()
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.objectsOrStrings(): List<Any> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { i -> opt(i)?.takeIf { it is String || it is JSONObject } }
}
