package io.embodit.gonine.data

import android.content.Context
import io.embodit.gonine.domain.engine.AiLevel
import io.embodit.gonine.domain.game.GameStore
import io.embodit.gonine.domain.game.Language
import io.embodit.gonine.domain.game.Settings
import io.embodit.gonine.domain.game.Stats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * files/game.json written atomically (Chess Stores): temp file → check it reads back → rename over the
 * old one, keeping the previous save as `.bak`; a broken main file falls back to the backup.
 */
class FileGameStore(context: Context) : GameStore {
    private val file = File(context.filesDir, "game.json")
    private val backup = File(context.filesDir, "game.json.bak")

    override fun load(): String? = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: runCatching { backup.readText() }.getOrNull()

    @Synchronized
    override fun save(text: String) {
        val tmp = File(file.parentFile, "game.json.tmp")
        tmp.writeText(text)
        if (tmp.readText() != text) error("save did not read back")
        if (file.exists()) file.copyTo(backup, overwrite = true)
        if (!tmp.renameTo(file)) { file.writeText(text); tmp.delete() }
    }

    @Synchronized
    override fun clear() {
        file.delete()
        backup.delete()
    }
}

/** App settings in SharedPreferences, as a StateFlow both windows read. */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val value: Settings get() = _settings.value

    private fun read() = Settings(
        language = runCatching { Language.valueOf(prefs.getString("language", null) ?: "SYSTEM") }.getOrDefault(Language.SYSTEM),
        sound = prefs.getBoolean("sound", true),
        volume = prefs.getInt("volume", 2).coerceIn(0, 2),
        reducedMotion = prefs.getBoolean("reduced", false),
        tilt = prefs.getInt("tilt", 15).let { if (it in setOf(0, 15, 25)) it else 15 },
        firstRunDone = prefs.getBoolean("firstRunDone", false),
    )

    fun update(f: (Settings) -> Settings) {
        val s = f(_settings.value)
        prefs.edit()
            .putString("language", s.language.name)
            .putBoolean("sound", s.sound)
            .putInt("volume", s.volume)
            .putBoolean("reduced", s.reducedMotion)
            .putInt("tilt", s.tilt)
            .putBoolean("firstRunDone", s.firstRunDone)
            .apply()
        _settings.value = s
    }
}

/** Wins and losses against each AI level (S09 small print「对入门：赢 2 · 输 3」). */
class StatsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("stats", Context.MODE_PRIVATE)
    private val _stats = MutableStateFlow(read())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    private fun read() = Stats(
        wins = AiLevel.entries.associateWith { prefs.getInt("win_${it.name}", 0) },
        losses = AiLevel.entries.associateWith { prefs.getInt("loss_${it.name}", 0) },
    )

    fun record(level: AiLevel, won: Boolean) {
        val key = (if (won) "win_" else "loss_") + level.name
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
        _stats.value = read()
    }
}
