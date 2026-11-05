package io.embodit.gonine.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import io.embodit.gonine.domain.game.Settings
import io.embodit.gonine.ui.board.SoundKind
import kotlin.random.Random

/**
 * Short wooden / stone sounds from `assets/sounds/<kind>.wav` (made by tools/make_sounds.py), through a
 * SoundPool. Placing stones plays at a random rate of ±7 % that never repeats back to back (design sound.variation).
 * [apply] follows the settings: sound off mutes, the three volume steps scale the gain. No ambient loop (B24).
 */
class SoundPlayer(private val context: Context) {
    @Volatile private var enabled = true
    @Volatile private var gain = 1f

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = HashMap<SoundKind, Int>()
    private val loaded = HashSet<Int>()
    private var lastRate = 1f
    private val random = Random.Default

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
        for (kind in SoundKind.entries) {
            val name = "sounds/${kind.name.lowercase()}.wav"
            runCatching { context.assets.openFd(name).use { fd -> ids[kind] = pool.load(fd, 1) } }
                .onFailure { Log.i(GoGraph.TAG, "no sound asset $name") }
        }
    }

    fun apply(s: Settings) {
        enabled = s.sound
        gain = when (s.volume) { 0 -> 0.35f; 1 -> 0.65f; else -> 1f }
    }

    fun play(kind: SoundKind) {
        if (!enabled) return
        val id = ids[kind] ?: return
        if (synchronized(loaded) { id !in loaded }) return
        val varied = kind == SoundKind.PLACE || kind == SoundKind.PLACE_2 || kind == SoundKind.PLACE_3 || kind == SoundKind.CAPTURE
        var rate = 1f
        if (varied) {
            rate = 0.93f + random.nextFloat() * 0.14f
            if (kotlin.math.abs(rate - lastRate) < 0.015f) rate = if (rate + 0.02f <= 1.07f) rate + 0.02f else rate - 0.02f
            lastRate = rate
        }
        pool.play(id, gain, gain, 1, 0, rate)
    }
}
