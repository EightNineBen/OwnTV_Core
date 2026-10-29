package tv.own.owntv.core.addons

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import tv.own.owntv.core.network.HttpClient

/**
 * Everything the addon screens ask for: installing addons, browsing their catalogs, a title's details
 * and its streams. Nothing here is synced or cached in the database; addons are asked on demand,
 * the way Stremio itself works.
 */
class AddonRepository(
    private val store: AddonStore,
    private val http: HttpClient,
) {

    val addons: Flow<List<InstalledAddon>> = store.addons

    sealed interface InstallResult {
        data class Installed(val addon: InstalledAddon) : InstallResult
        data object InvalidUrl : InstallResult
        /** Reached nothing, or got something that isn't a manifest. */
        data object Unreachable : InstallResult
        /** The addon must be configured on its own page first; its configured URL is what to install. */
        data class NeedsConfiguration(val manifest: AddonManifest) : InstallResult
    }

    suspend fun install(input: String): InstallResult {
        val url = AddonUrls.normalizeManifestUrl(input) ?: return InstallResult.InvalidUrl
        val json = fetch(url) ?: return InstallResult.Unreachable
        val manifest = runCatching { AddonJson.manifest(json) }.getOrNull() ?: return InstallResult.Unreachable
        if (manifest.configurationRequired) return InstallResult.NeedsConfiguration(manifest)
        store.put(url, json)
        Log.i(TAG, "installed addon id=${manifest.id} version=${manifest.version}")
        return InstallResult.Installed(InstalledAddon(url, manifest))
    }

    suspend fun remove(addon: InstalledAddon) = store.remove(addon.transportUrl)

    suspend fun move(addon: InstalledAddon, up: Boolean) = store.move(addon.transportUrl, up)

    /** Re-reads every manifest, so new catalogs and versions show up. A failing addon keeps what it had. */
    suspend fun refreshManifests() = coroutineScope {
        store.getAll().map { addon ->
            async {
                val json = fetch(addon.transportUrl) ?: return@async
                if (runCatching { AddonJson.manifest(json) }.isSuccess) store.put(addon.transportUrl, json)
            }
        }.awaitAll()
    }

    /** The catalogs that can be shown as plain rows (no mandatory search or genre), in addon order. */
    fun browseCatalogs(addons: List<InstalledAddon>): List<Pair<InstalledAddon, AddonCatalog>> =
        addons.flatMap { addon -> addon.manifest.catalogs.filter { it.browsable }.map { addon to it } }

    /** One page of a catalog. Empty on any failure: a broken row shouldn't take the screen down with it. */
    suspend fun catalogPage(addon: InstalledAddon, catalog: AddonCatalog, skip: Int = 0, genre: String? = null): List<MetaPreview> {
        val json = fetch(AddonUrls.catalog(addon, catalog, skip = skip, genre = genre)) ?: return emptyList()
        return AddonJson.catalogItems(json)
    }

    /** Every searchable catalog's results for [query], asked in parallel; catalogs with no hits are left out. */
    suspend fun search(addons: List<InstalledAddon>, query: String): List<CatalogPage> = coroutineScope {
        addons.flatMap { addon -> addon.manifest.catalogs.filter { it.searchable }.map { addon to it } }
            .map { (addon, catalog) ->
                async {
                    val json = withTimeoutOrNull(REQUEST_TIMEOUT_MS) { fetch(AddonUrls.catalog(addon, catalog, search = query)) }
                    CatalogPage(addon, catalog, json?.let(AddonJson::catalogItems).orEmpty())
                }
            }
            .awaitAll()
            .filter { it.items.isNotEmpty() }
    }

    /**
     * A title's details. The addon the tile came from is asked first, then every other addon that
     * serves `meta` for this type and id (Cinemeta, for `tt` ids), in the user's order.
     */
    suspend fun meta(addons: List<InstalledAddon>, type: String, id: String, from: InstalledAddon? = null): MetaDetail? {
        val candidates = (listOfNotNull(from) + addons.filter { it.transportUrl != from?.transportUrl })
            .filter { it.manifest.supports("meta", type, id) }
        for (addon in candidates) {
            val json = withTimeoutOrNull(REQUEST_TIMEOUT_MS) { fetch(AddonUrls.meta(addon, type, id)) } ?: continue
            AddonJson.meta(json)?.let { return it }
        }
        return null
    }

    /**
     * Asks every addon that serves streams for [videoId] at once, and emits the growing list as each
     * one answers, so a fast addon's links show while a slow one is still searching. An addon that
     * fails or times out is reported with no streams and `failed` set.
     */
    fun streams(addons: List<InstalledAddon>, type: String, videoId: String): Flow<List<AddonStreamsResult>> = channelFlow {
        val sources = addons.filter { it.manifest.supports("stream", type, videoId) }
        val results = sources.map { AddonStreamsResult(it, emptyList(), pending = true) }.toMutableList()
        send(results.toList())
        sources.forEachIndexed { index, addon ->
            launch {
                val json = withTimeoutOrNull(STREAM_TIMEOUT_MS) { fetch(AddonUrls.stream(addon, type, videoId)) }
                val streams = json?.let(AddonJson::streams).orEmpty()
                val result = AddonStreamsResult(addon, streams, pending = false, failed = json == null)
                synchronized(results) { results[index] = result }
                send(synchronized(results) { results.toList() })
            }
        }
    }

    private suspend fun fetch(url: String): JSONObject? = try {
        JSONObject(http.getText(url, userAgent = HttpClient.FALLBACK_USER_AGENT))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Never the URL or the message (which quotes it): an addon URL often carries a debrid key in its path.
        Log.w(TAG, "addon request failed: ${(e as? HttpClient.HttpStatusException)?.let { "HTTP ${it.code}" } ?: e.javaClass.simpleName}")
        null
    }

    private companion object {
        const val TAG = "AddonRepository"
        const val REQUEST_TIMEOUT_MS = 15_000L
        // Debrid addons check availability with the debrid service before answering; give them longer.
        const val STREAM_TIMEOUT_MS = 30_000L
    }
}

/** One addon's answer in a stream search. */
data class AddonStreamsResult(
    val addon: InstalledAddon,
    val streams: List<AddonStream>,
    val pending: Boolean,
    val failed: Boolean = false,
)
