package io.embodit.gonine.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import io.embodit.gonine.domain.game.Settings
import io.embodit.gonine.ui.board.SoundKind
import kotlin.random.Random

/**
 * CC0 recordings (Kenney impact / casino / interface packs, imported and softened by tools/import_cc0.py) from
 * `assets/sounds/<kind>.wav`, plus `<kind>_b.wav` as a second take where present, through a SoundPool. Placing
 * stones alternates takes and plays at a random rate of ±5 % that never repeats back to back (profile D51).
 * [apply] follows the settings: sound off mutes, the three volume steps scale the gain. No ambient loop (B24).
 */
class SoundPlayer(private val context: Context) {
    @Volatile private var enabled = true
    @Volatile private var gain = 1f

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = HashMap<SoundKind, List<Int>>()
    private val lastTake = HashMap<SoundKind, Int>()
    private val loaded = HashSet<Int>()
    private var lastRate = 1f
    private val random = Random.Default

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
        for (kind in SoundKind.entries) {
            val base = kind.name.lowercase()
            val takes = listOf("$base.wav", "${base}_b.wav").mapNotNull { name ->
                runCatching { context.assets.openFd("sounds/$name").use { fd -> pool.load(fd, 1) } }.getOrNull()
            }
            if (takes.isEmpty()) Log.i(GoGraph.TAG, "no sound asset sounds/$base.wav") else ids[kind] = takes
        }
    }

    fun apply(s: Settings) {
        enabled = s.sound
        gain = when (s.volume) { 0 -> 0.45f; 1 -> 0.7f; else -> 1f }
    }

    fun play(kind: SoundKind) {
        if (!enabled) return
        val takes = ids[kind] ?: return
        val take = if (takes.size < 2) 0 else ((lastTake[kind] ?: -1) + 1) % takes.size
        lastTake[kind] = take
        val id = takes[take]
        if (synchronized(loaded) { id !in loaded }) return
        val varied = kind == SoundKind.PLACE || kind == SoundKind.PLACE_2 || kind == SoundKind.PLACE_3 || kind == SoundKind.CAPTURE
        var rate = 1f
        if (varied) {
            rate = 0.95f + random.nextFloat() * 0.10f
            if (kotlin.math.abs(rate - lastRate) < 0.015f) rate = if (rate + 0.02f <= 1.05f) rate + 0.02f else rate - 0.02f
            lastRate = rate
        }
        pool.play(id, gain, gain, 1, 0, rate)
    }
}
