package tv.own.owntv.core.addons

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.addonStore: DataStore<Preferences> by preferencesDataStore(name = "owntv_addons")

/**
 * The installed addons, in the user's order, as a JSON list in DataStore. Not a Room table, for the
 * same reason as the EPG feeds (see EpgSourceStore): it keeps addon support out of the schema, so
 * upstream migrations never have to know about it.
 *
 * Each entry keeps the manifest exactly as the addon sent it, and is parsed again on read, so a
 * newer parser picks up fields an older one ignored without a re-install.
 */
class AddonStore(private val context: Context) {

    private object Keys {
        val LIST = stringPreferencesKey("addons")
    }

    val addons: Flow<List<InstalledAddon>> = context.addonStore.data.map { prefs -> parse(prefs[Keys.LIST]) }

    suspend fun getAll(): List<InstalledAddon> = parse(context.addonStore.data.first()[Keys.LIST])

    /** Adds [transportUrl], or replaces its manifest in place when it is already installed. */
    suspend fun put(transportUrl: String, manifestJson: JSONObject) {
        edit { list ->
            val entry = Entry(transportUrl, manifestJson.toString())
            val at = list.indexOfFirst { it.transportUrl == transportUrl }
            if (at < 0) list + entry else list.toMutableList().also { it[at] = entry }
        }
    }

    suspend fun remove(transportUrl: String) {
        edit { list -> list.filterNot { it.transportUrl == transportUrl } }
    }

    /** One place up or down; addon order decides which addon's details and streams are listed first. */
    suspend fun move(transportUrl: String, up: Boolean) {
        edit { list ->
            val from = list.indexOfFirst { it.transportUrl == transportUrl }
            val to = if (up) from - 1 else from + 1
            if (from < 0 || to !in list.indices) list
            else list.toMutableList().also { java.util.Collections.swap(it, from, to) }
        }
    }

    private data class Entry(val transportUrl: String, val manifest: String)

    private suspend fun edit(change: (List<Entry>) -> List<Entry>) {
        context.addonStore.edit { prefs ->
            val list = change(entries(prefs[Keys.LIST]))
            if (list.isEmpty()) prefs.remove(Keys.LIST) else prefs[Keys.LIST] = write(list)
        }
    }

    private fun entries(raw: String?): List<Entry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("transportUrl").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Entry(url, o.optString("manifest"))
            }
        }.getOrElse { e ->
            Log.w(TAG, "installed addon list unreadable", e)
            emptyList()
        }
    }

    private fun parse(raw: String?): List<InstalledAddon> = entries(raw).mapNotNull { entry ->
        runCatching { InstalledAddon(entry.transportUrl, AddonJson.manifest(JSONObject(entry.manifest))) }
            .onFailure { Log.w(TAG, "stored manifest unreadable", it) }
            .getOrNull()
    }

    private fun write(list: List<Entry>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("transportUrl", it.transportUrl).put("manifest", it.manifest)) }
    }.toString()

    private companion object {
        const val TAG = "AddonStore"
    }
}
