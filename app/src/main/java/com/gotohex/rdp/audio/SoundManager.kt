package com.gotohex.rdp.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import com.gotohex.rdp.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Space-themed sound manager using SoundPool for low-latency sfx.
 * Sounds are galaxy/warp themed (generated WAV files in res/raw/).
 */
@Singleton
class SoundManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    enum class Sound {
        TAP,        // holographic interface tap
        TOGGLE,     // sci-fi switch
        SWIPE,      // whoosh navigation
        SUCCESS,    // tri-tone mission accomplished
        ERROR,      // dissonant failure buzz
        CONNECT,    // cinematic 2.4s galaxy warp connection
    }

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val soundMap    = mutableMapOf<Sound, Int>()
    // FIX #6: track each sound ID individually so play() can guard against
    // sounds that haven't finished loading yet (e.g. rapid tap right after launch).
    private val loadedIds   = mutableSetOf<Int>()
    private var enabled     = true
    @Volatile private var released = false  // BUG-10 FIX: guard against play() after release()

    init {
        // FIX #6: add the completed sound's sampleId to loadedIds instead of
        // setting a single boolean on the first completion (which previously
        // allowed play() on the other 5 sounds that were still loading).
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loadedIds.add(sampleId)
        }
        soundMap[Sound.TAP]     = pool.load(context, R.raw.sfx_tap,     1)
        soundMap[Sound.TOGGLE]  = pool.load(context, R.raw.sfx_toggle,  1)
        soundMap[Sound.SWIPE]   = pool.load(context, R.raw.sfx_swipe,   1)
        soundMap[Sound.SUCCESS] = pool.load(context, R.raw.sfx_success, 1)
        soundMap[Sound.ERROR]   = pool.load(context, R.raw.sfx_error,   1)
        soundMap[Sound.CONNECT] = pool.load(context, R.raw.sfx_connect, 1)
    }

    fun play(sound: Sound, volume: Float = 1f) {
        if (!enabled || released) return  // BUG-10 FIX: guard against play() after release()
        val id = soundMap[sound] ?: return
        // FIX #6: skip silently if this specific sound hasn't finished loading yet.
        if (id !in loadedIds) return
        val vol = volume.coerceIn(0f, 1f)
        pool.play(id, vol, vol, 1, 0, 1f)
    }

    fun setEnabled(isEnabled: Boolean) {
        enabled = isEnabled
    }

    fun release() {
        released = true  // BUG-10 FIX: set before pool.release() to prevent race
        pool.release()
    }
}
