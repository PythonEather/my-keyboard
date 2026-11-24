package com.expressive.keyboard

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.audiofx.EnvironmentalReverb
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ============================================================================
// Звук мода: kb.playAudio / kb.setAudio.
// Панорама, высота тона, скорость, громкость от расстояния, реверберация и бесшовный цикл (gapless).
// Математика вынесена в VoiceMath, чтобы её можно было проверять тестами без Android.
// ============================================================================

internal object VoiceMath {
    /** Левый и правый канал для панорамы −1..1. Равная мощность, в центре оба канала 1.0 (звук без панорамы не стихает). */
    fun panGains(pan: Float): Pair<Float, Float> {
        val p = pan.coerceIn(-1f, 1f)
        val angle = (p + 1f) * (PI.toFloat() / 4f)
        val s = sqrt(2f)
        return minOf(1f, s * cos(angle)) to minOf(1f, s * sin(angle))
    }

    /**
     * Громкость от расстояния до источника. До refDistance — полная, на maxDistance — тишина, между ними
     * обратная зависимость, плавно приведённая к нулю (rolloff 1 — как в OpenAL).
     */
    fun distanceGain(distance: Float, maxDistance: Float, refDistance: Float = 1f, rolloff: Float = 1f): Float {
        if (maxDistance <= refDistance) return 1f
        val d = distance.coerceIn(refDistance, maxDistance)
        val inverse = refDistance / (refDistance + rolloff * (d - refDistance))
        val atMax = refDistance / (refDistance + rolloff * (maxDistance - refDistance))
        // вычитаем уровень на maxDistance: на краю — настоящая тишина, а не 5 %
        return ((inverse - atMax) / (1f - atMax)).coerceIn(0f, 1f)
    }
}

/** Одна реверберация на мод: все его голоса отправляют в неё часть сигнала (aux send). */
internal class ModReverb {
    private var fx: EnvironmentalReverb? = null
    var size = -1f
        private set

    /** id эффекта для attachAuxEffect или null, если устройство не умеет. size 0..1: от комнаты до большого зала. */
    fun ensure(sizeWanted: Float): Int? {
        val want = sizeWanted.coerceIn(0f, 1f)
        val cur = fx
        if (cur != null && kotlin.math.abs(want - size) < 0.01f) return cur.id
        val r = cur ?: runCatching { EnvironmentalReverb(0, 0) }.getOrNull() ?: return null
        runCatching {
            r.decayTime = (400 + 5600 * want).toInt()
            r.decayHFRatio = 800.toShort()
            r.roomLevel = (-1000).toShort()
            r.roomHFLevel = (-400).toShort()
            r.reflectionsLevel = (-800).toShort()
            r.reflectionsDelay = 20
            r.reverbLevel = (-300 + 300 * want).toInt().toShort()
            r.reverbDelay = (30 + 50 * want).toInt()
            r.diffusion = 1000.toShort()
            r.density = 1000.toShort()
            r.setEnabled(true)
        }
        fx = r
        size = want
        return r.id
    }

    fun release() {
        runCatching { fx?.release() }
        fx = null
        size = -1f
    }
}

/**
 * Один звук мода. Бесшовный цикл — две MediaPlayer цепочкой через setNextMediaPlayer: следующий уже подготовлен и
 * стартует в тот же момент, когда кончился текущий. Обычный isLooping даёт паузу на стыке.
 */
internal class ModVoice(
    private val path: String,
    private val loop: Boolean,
    private val gapless: Boolean,
    private val reverb: ModReverb,
    private val onFinished: () -> Unit
) {
    var volume = 1f
    var pan = 0f
    var pitch = 1f
    var rate = 1f
    var distance = 0f
    var maxDistance = 20f
    /** 0..1: сколько сигнала уходит в реверберацию. */
    var reverbSend = 0f
    var reverbSize = 0.5f

    @Volatile private var dead = false
    private var current: MediaPlayer? = null
    private var queued: MediaPlayer? = null

    private val chained get() = loop && gapless

    fun start() {
        val first = create()
        current = first
        first.setOnPreparedListener { p ->
            if (dead) return@setOnPreparedListener
            apply(p, withRate = true)
            p.start()
            if (chained) queueNext(p)
        }
        first.prepareAsync()
    }

    private fun create(): MediaPlayer {
        val mp = MediaPlayer()
        mp.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        )
        mp.setDataSource(path)
        mp.isLooping = loop && !gapless
        mp.setOnCompletionListener { done ->
            if (dead) return@setOnCompletionListener
            if (chained) {
                // следующий уже играет сам: делаем его текущим и готовим ещё один
                val next = queued
                queued = null
                runCatching { done.release() }
                if (next != null) {
                    current = next
                    apply(next, withRate = true)
                    queueNext(next)
                } else {
                    // следующий не успел подготовиться (очень короткий файл): начинаем заново, пауза неизбежна
                    current = null
                    start()
                }
            } else if (!loop) {
                runCatching { done.release() }
                if (current === done) current = null
                dead = true
                onFinished()
            }
        }
        return mp
    }

    private fun queueNext(after: MediaPlayer) {
        if (dead) return
        val n = try {
            create()
        } catch (e: Exception) {
            return
        }
        n.setOnPreparedListener { p ->
            if (dead || current !== after) {
                runCatching { p.release() }
                return@setOnPreparedListener
            }
            apply(p, withRate = false)
            queued = p
            runCatching { after.setNextMediaPlayer(p) }
        }
        n.prepareAsync()
    }

    /**
     * Применяет громкость, панораму, реверберацию, а при withRate — ещё высоту тона и скорость.
     * Высоту тона нельзя задавать у игрока, который ждёт в очереди: на некоторых версиях Android PlaybackParams запускает воспроизведение.
     */
    private fun apply(mp: MediaPlayer, withRate: Boolean) {
        val (l, r) = VoiceMath.panGains(pan)
        val g = (volume * VoiceMath.distanceGain(distance, maxDistance)).coerceIn(0f, 1f)
        runCatching { mp.setVolume(l * g, r * g) }
        runCatching {
            if (reverbSend > 0f) {
                reverb.ensure(reverbSize)?.let { id ->
                    mp.attachAuxEffect(id)
                    mp.setAuxEffectSendLevel(reverbSend.coerceIn(0f, 1f))
                }
            } else {
                mp.setAuxEffectSendLevel(0f)
            }
        }
        if (withRate && (pitch != 1f || rate != 1f)) {
            runCatching { mp.playbackParams = PlaybackParams().setPitch(pitch.coerceIn(0.25f, 4f)).setSpeed(rate.coerceIn(0.25f, 4f)) }
        }
    }

    /** kb.setAudio: меняет параметры уже играющего звука. */
    fun update() {
        if (dead) return
        current?.let { apply(it, withRate = true) }
        // игрок в очереди получит высоту тона, когда станет текущим
        queued?.let { apply(it, withRate = false) }
    }

    fun stop() {
        dead = true
        for (p in listOf(current, queued)) {
            if (p != null) {
                runCatching { p.setOnCompletionListener(null) }
                runCatching { p.stop() }
                runCatching { p.release() }
            }
        }
        current = null
        queued = null
    }
}
