package com.example.core.database

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.network.TorveApiClient
import com.example.core.network.TorveModel
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer

private val Context.torvePrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "torve_settings")

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val askBeforeShellCommands: Boolean = true,
    val maxAgentSteps: Int = 20,
    val defaultModelId: String = ""
)

class AppPreferencesStore(private val context: Context) {
    private val json = TorveApiClient.defaultTorveJson()

    val settingsFlow: Flow<AppSettings> = context.torvePrefsDataStore.data.map { prefs ->
        val themeStr = prefs[KEY_THEME_MODE] ?: ThemeMode.DARK.name
        val theme = runCatching { ThemeMode.valueOf(themeStr) }.getOrDefault(ThemeMode.DARK)
        AppSettings(
            themeMode = theme,
            askBeforeShellCommands = prefs[KEY_ASK_BEFORE_SHELL] ?: true,
            maxAgentSteps = (prefs[KEY_MAX_STEPS] ?: 20).coerceIn(5, 40),
            defaultModelId = prefs[KEY_DEFAULT_MODEL] ?: ""
        )
    }

    val cachedModelsFlow: Flow<List<TorveModel>> = context.torvePrefsDataStore.data.map { prefs ->
        val raw = prefs[KEY_CACHED_MODELS] ?: return@map emptyList()
        runCatching {
            json.decodeFromString(ListSerializer(TorveModel.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.torvePrefsDataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    suspend fun setAskBeforeShellCommands(ask: Boolean) {
        context.torvePrefsDataStore.edit { it[KEY_ASK_BEFORE_SHELL] = ask }
    }

    suspend fun setMaxAgentSteps(steps: Int) {
        context.torvePrefsDataStore.edit { it[KEY_MAX_STEPS] = steps.coerceIn(5, 40) }
    }

    suspend fun setDefaultModelId(modelId: String) {
        context.torvePrefsDataStore.edit { it[KEY_DEFAULT_MODEL] = modelId }
    }

    suspend fun saveCachedModels(models: List<TorveModel>) {
        val serialized = json.encodeToString(ListSerializer(TorveModel.serializer()), models)
        context.torvePrefsDataStore.edit { it[KEY_CACHED_MODELS] = serialized }
    }

    suspend fun clearSessionCache() {
        context.torvePrefsDataStore.edit {
            it.remove(KEY_CACHED_MODELS)
            it.remove(KEY_DEFAULT_MODEL)
        }
    }

    companion object {
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_ASK_BEFORE_SHELL = booleanPreferencesKey("ask_before_shell")
        private val KEY_MAX_STEPS = intPreferencesKey("max_agent_steps")
        private val KEY_DEFAULT_MODEL = stringPreferencesKey("default_model_id")
        private val KEY_CACHED_MODELS = stringPreferencesKey("cached_models_json")
    }
}
