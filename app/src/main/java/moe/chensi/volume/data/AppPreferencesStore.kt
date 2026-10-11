package moe.chensi.volume.data

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class AppPreferencesStore(private val dataStore: DataStore<Preferences>) {
    companion object {
        private val key = stringPreferencesKey("apps")

        private val json = Json { ignoreUnknownKeys = true }
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    @Serializable
    private data class SerializedState(
        val values: MutableList<AppPreferences>,
        val indices: MutableMap<String, Int>,
        val systemSliderVisibility: MutableMap<String, Boolean> = mutableMapOf()
    )

    private val lock = Any()
    private var state = SerializedState(mutableListOf(), mutableMapOf())
    val values: List<AppPreferences>
        get() = state.values
    val indices: Map<String, Int>
        get() = synchronized(lock) { state.indices.toMap() }
    fun getSystemSliderVisible(id: String): Boolean {
        return synchronized(lock) { state.systemSliderVisibility[id] ?: true }
    }

    fun setSystemSliderVisible(id: String, value: Boolean) {
        val changed = synchronized(lock) {
            val oldValue = state.systemSliderVisibility[id] ?: true
            if (oldValue == value) {
                return@synchronized false
            }

            val updated = state.systemSliderVisibility.toMutableMap()
            updated[id] = value
            state = state.copy(systemSliderVisibility = updated)
            true
        }

        if (changed) {
            save(0L)
        }
    }

    var systemSliderVisibility: Map<String, Boolean>
        get() = synchronized(lock) { state.systemSliderVisibility.toMap() }
        set(value) {
            val changed = synchronized(lock) {
                if (state.systemSliderVisibility == value) {
                    return@synchronized false
                }

                state = state.copy(systemSliderVisibility = value.toMutableMap())
                true
            }

            if (changed) {
                save(0L)
            }
        }

    fun track(onChange: (first: Boolean) -> Unit) {
        var first = true

        scope.launch {
            dataStore.data.collect { preferences ->
                if (first) {
                    val valueJson = preferences[key]
                    if (valueJson != null) {
                        synchronized(lock) {
                            try {
                                state = json.decodeFromString<SerializedState>(valueJson)
                            } catch (e: Exception) {
                                Log.e("AppPreferencesStore", "Failed to decode state", e)
                            }
                        }
                    }
                    onChange(true)
                    first = false
                }
            }
        }
    }

    fun getOrCreate(packageName: String): AppPreferences {
        synchronized(lock) {
            val index = state.indices[packageName]
            if (index != null) {
                return state.values[index]
            }

            val value = AppPreferences()
            state.indices[packageName] = state.values.size
            state.values.add(value)
            return value
        }
    }

    private var saveJob: Job? = null

    fun save(debounceMs: Long = 300L) {
        synchronized(lock) {
            saveJob?.cancel()
            val jsonString = try {
                json.encodeToString(state)
            } catch (e: Exception) {
                Log.e("AppPreferencesStore", "Failed to encode state", e)
                return
            }
            saveJob = scope.launch {
                if (debounceMs > 0) {
                    delay(debounceMs)
                }
                try {
                    dataStore.edit { preferences ->
                        preferences[key] = jsonString
                    }
                } catch (e: Exception) {
                    Log.e("AppPreferencesStore", "Failed to save state", e)
                }
            }
        }
    }
}
