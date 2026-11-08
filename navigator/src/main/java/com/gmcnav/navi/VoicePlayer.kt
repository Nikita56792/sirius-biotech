package com.gmcnav.navi

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.gmcnav.navi.core.Prompt

/**
 * Plays prompts made of recordings (assets/sounds/ru_alice/<key>/<file>), chained gaplessly with
 * MediaPlayer.setNextMediaPlayer. There is no speech synthesis. An urgent prompt replaces whatever is playing or queued;
 * a non-urgent one is dropped if it would start more than 3 s late. Navigation-guidance audio attributes with transient
 * ducking focus. A missing file is logged and skipped.
 */
class VoicePlayer(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var released = false
    private var generation = 0
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
    var muted = false
    var onSpoken: ((Prompt) -> Unit)? = null

    private class Item(val p: Prompt, val queuedAt: Long)
    private val queue = ArrayDeque<Item>()
    private var current: Prompt? = null
    private val players = ArrayList<MediaPlayer>()

    fun say(p: Prompt) {
        if (muted || released) return
        if (p.urgent) { queue.clear(); if (current != null && current?.urgent == false) stopCurrent() }
        queue.addLast(Item(p, System.currentTimeMillis()))
        if (current == null) playNext()
    }

    fun stopAll() { queue.clear(); stopCurrent() }

    private fun stopCurrent() {
        generation++
        main.removeCallbacksAndMessages(null)
        players.forEach { runCatching { it.stop() }; it.release() }; players.clear()
        current = null
        am.abandonAudioFocusRequest(focus)
    }

    private fun playNext() {
        if (released) return
        players.forEach { it.release() }; players.clear()
        while (queue.isNotEmpty()) {
            val it = queue.removeFirst()
            if (!it.p.urgent && System.currentTimeMillis() - it.queuedAt > 3000) continue
            current = it.p
            am.requestAudioFocus(focus)
            onSpoken?.invoke(it.p)
            // the whole prompt as one chained sequence of recordings
            for (part in it.p.parts) {
                val mp = runCatching {
                    ctx.assets.openFd("sounds/ru_alice/${part.key}/${part.file}").use { fd ->
                        MediaPlayer().apply { setAudioAttributes(attrs); setDataSource(fd.fileDescriptor, fd.startOffset, fd.length); prepare() }
                    }
                }.getOrNull()
                if (mp == null) { android.util.Log.w("GmcNavi", "voice: missing sounds/ru_alice/${part.key}/${part.file}"); continue }
                players.lastOrNull()?.setNextMediaPlayer(mp)
                players.add(mp)
            }
            if (players.isEmpty()) continue
            val token = generation
            players.last().setOnCompletionListener { main.post { if (token == generation) playNext() } }
            players.first().start()
            return
        }
        current = null
        am.abandonAudioFocusRequest(focus)
    }

    fun release() { released = true; stopAll() }
}
