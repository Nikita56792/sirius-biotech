package org.sirius.biotech;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.function.Consumer;

/** Explicit TTS adaptation, not MV's recorded Alice voice pack. Main-thread owned. */
final class EventVenueVoice {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private final HashSet<String> announced = new HashSet<>();
    private final AudioManager audio;
    private final Consumer<String> notice;
    private final boolean russian;
    private final AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change < 0) main.post(this::stop);
    };
    private AudioFocusRequest focus;
    private TextToSpeech tts;
    private boolean ready, disposed, speaking, unavailable;
    boolean muted;
    private long serial;
    private String active;

    EventVenueVoice(Context context, boolean russian, Consumer<String> notice) {
        this.russian = russian; this.notice = notice;
        audio = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes).setOnAudioFocusChangeListener(focusListener).build();
        tts = new TextToSpeech(context.getApplicationContext(), result -> main.post(() -> initialize(result)));
    }
    private void initialize(int result) {
        if (disposed) return;
        if (result != TextToSpeech.SUCCESS || tts == null) { fail(); return; }
        int language = tts.setLanguage(russian ? new Locale("ru", "RU") : Locale.US);
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) { fail(); return; }
        tts.setAudioAttributes(attributes);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}
            @Override public void onDone(String id) { main.post(() -> complete(id)); }
            @Override public void onError(String id) { main.post(() -> { if (id.equals(active)) fail(); }); }
        });
        ready = true; play();
    }
    void say(String text, String key) {
        if (disposed || muted || unavailable || !announced.add(key)) return;
        // Keep only the latest manually selected point, never a stale spoken backlog.
        pending.clear(); pending.add(text);
        if (speaking) { tts.stop(); speaking = false; active = null; }
        play();
    }
    private void play() {
        if (!ready || disposed || muted || speaking || pending.isEmpty()) return;
        int granted = audio == null ? AudioManager.AUDIOFOCUS_REQUEST_FAILED : Build.VERSION.SDK_INT >= 26
                ? audio.requestAudioFocus(focus)
                : audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { pending.clear(); return; }
        String text = pending.removeFirst(); active = "venue-" + (++serial); speaking = true;
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, active) == TextToSpeech.ERROR) fail();
    }
    private void complete(String id) {
        if (disposed || !id.equals(active)) return;
        speaking = false; active = null; abandon(); play();
    }
    private void abandon() {
        if (audio == null) return;
        if (Build.VERSION.SDK_INT >= 26) audio.abandonAudioFocusRequest(focus);
        else audio.abandonAudioFocus(focusListener);
    }
    void setMuted(boolean value) { muted = value; if (value) stop(); }
    void stop() {
        pending.clear(); announced.clear(); active = null; speaking = false;
        if (tts != null) tts.stop();
        abandon();
    }
    private void fail() {
        ready = false; unavailable = true; stop();
        if (!disposed) notice.accept(russian ? "Голос TTS недоступен: установите русский голос в настройках Android."
                : "TTS voice unavailable. Install an English voice in Android settings.");
    }
    void dispose() {
        if (disposed) return;
        disposed = true; stop(); main.removeCallbacksAndMessages(null);
        if (tts != null) { tts.shutdown(); tts = null; }
    }
}
