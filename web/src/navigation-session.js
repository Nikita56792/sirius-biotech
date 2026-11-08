// DOM-independent manual route session. Original MV recordings only; no positioning or TTS.
export class NavigationSession {
  constructor({audioFactory = () => new Audio(), storage, mediaSession, eventTarget = globalThis.window, now = Date.now, random = Math.random} = {}) {
    this.audioFactory = audioFactory; this.storage = storage; this.mediaSession = mediaSession;
    this.eventTarget = eventTarget; this.now = now; this.random = random;
    this.listeners = new Set(); this.route = null; this.start = ''; this.destination = '';
    this.progress = 0; this.preview = false; this.queue = []; this.current = null; this.generation = 0;
    this.audioState = 'idle'; this.error = ''; this.mode = 2; this.volume = 1; this.lastVariant = null;
    try { const s = JSON.parse(storage?.getItem('sbs-navigation-audio') || '{}'); this.mode = [0,1,2].includes(s.mode) ? s.mode : 2; this.volume = Number.isFinite(s.volume) ? Math.max(0, Math.min(1, s.volume)) : 1; } catch {}
    this.audio = null; this.manifest = null;
    for (const [action, handler] of Object.entries({play: () => this.resume(), pause: () => this.pause(), stop: () => this.stop()})) {
      try { mediaSession?.setActionHandler(action, handler); } catch {}
    }
  }
  configure(manifest) { this.manifest = manifest; }
  snapshot() { return {active: Boolean(this.route), route: this.route, start: this.start, destination: this.destination, progress: this.progress, preview: this.preview, mode: this.mode, volume: this.volume, audioState: this.audioState, error: this.error}; }
  subscribe(fn) { this.listeners.add(fn); fn(this.snapshot()); return () => this.listeners.delete(fn); }
  notify() {
    const state = this.snapshot(); for (const fn of this.listeners) fn(state);
    if (this.eventTarget && typeof CustomEvent !== 'undefined') this.eventTarget.dispatchEvent(new CustomEvent('sbs-navigation-session', {detail: state}));
    try { if (this.mediaSession) this.mediaSession.playbackState = this.audioState === 'playing' ? 'playing' : this.current ? 'paused' : 'none'; } catch {}
  }
  setSettings({mode = this.mode, volume = this.volume}) {
    this.mode = mode; this.volume = Math.max(0, Math.min(1, volume));
    try { this.storage?.setItem('sbs-navigation-audio', JSON.stringify({mode: this.mode, volume: this.volume})); } catch {}
    if (!mode) this.cancelAudio();
    if (this.audio) this.audio.volume = this.volume;
    this.notify();
  }
  setEndpoints(start, destination) { this.start = start; this.destination = destination; this.notify(); }
  setRoute(route, start, destination) {
    this.cancelAudio(); this.route = route; this.start = start; this.destination = destination;
    this.progress = 0; this.preview = false; this.notify();
    if (route) this.announceRoute();
  }
  setNativeRoute(route, start, destination) {
    this.cancelAudio(); this.route = route; this.start = start; this.destination = destination;
    this.progress = 0; this.preview = false; this.notify();
  }
  async unlock() {
    if (!this.audio) { this.audio = this.audioFactory(); this.audio.preload = 'auto'; }
    this.audio.src = '/app/navigator/silence.wav';
    try { await this.audio.play(); this.audio.pause(); } catch { /* A later gesture can resume. */ }
  }
  setPreview(value) { this.preview = Boolean(this.route && value); this.notify(); }
  step(delta) { if (!this.route || !this.preview) return; this.progress = Math.max(0, Math.min(this.route.positions.length - 1, this.progress + delta)); this.notify(); }
  // VoiceVariants.pick's default/non-repeating weighting for RouteCalculated, unchanged mapping.
  announceRoute() {
    if (!this.route || !this.mode) return;
    const options = this.manifest?.events?.RouteCalculated;
    if (!options?.length) { this.error = 'recordings-unavailable'; this.notify(); return; }
    const total = options.reduce((sum, o) => sum + o.w, 0), others = options.filter(o => !o.default);
    const weights = others.map(o => (o.w / total) / (1 - o.w / total));
    const scale = Math.min(1, 1 / weights.reduce((a, b) => a + b, 0));
    let u = this.random(), chosen = options.find(o => o.default);
    others.some((o, i) => { if (o.file === this.lastVariant) return false; u -= weights[i] * scale; if (u < 0) { chosen = o; return true; } return false; });
    this.lastVariant = chosen.file;
    this.say({parts: [chosen], urgent: true});
  }
  say(prompt) {
    if (!this.route || !this.mode || (this.mode === 1 && !prompt.urgent)) return;
    const parts = prompt.parts.map(p => `${p.key}/${p.file || '0.mp3'}`);
    if (!parts.length || parts.some(p => !this.manifest?.files?.[p])) { this.error = 'recordings-unavailable'; this.notify(); return; }
    // Match MV VoicePlayer: urgent replaces nonurgent, but does not cut another urgent prompt.
    if (prompt.urgent) { this.queue = []; if (this.current && !this.current.urgent) this.cancelAudio(); }
    this.queue.push({parts, urgent: prompt.urgent, queuedAt: this.now(), index: 0});
    if (!this.current) this.next();
  }
  next() {
    this.current = null;
    while (this.queue.length) {
      const item = this.queue.shift(); if (!item.urgent && this.now() - item.queuedAt > 3000) continue;
      this.current = item; this.playPart(); return;
    }
    this.audioState = 'idle'; this.notify();
  }
  playPart() {
    if (!this.current) return;
    if (!this.audio) { this.audio = this.audioFactory(); this.audio.preload = 'auto'; }
    const token = ++this.generation;
    this.audio.onended = () => {
      if (token !== this.generation || !this.current) return;
      if (++this.current.index < this.current.parts.length) this.playPart(); else this.next();
    };
    this.audio.onerror = () => { if (token === this.generation && this.current) { this.audioState = 'blocked'; this.error = 'recording-playback-failed'; this.notify(); } };
    this.audio.onpause = () => { if (token === this.generation && this.current && !this.audio.ended) { this.audioState = 'paused'; this.notify(); } };
    this.audio.src = (this.manifest?.baseUrl || '/app/assets/voice/ru_alice/') + this.current.parts[this.current.index];
    this.audio.volume = this.volume; this.play(token);
  }
  play(token = this.generation) {
    this.error = ''; this.audioState = 'playing'; this.notify();
    try { Promise.resolve(this.audio.play()).catch(() => { if (token === this.generation && this.current) { this.audioState = 'blocked'; this.error = 'tap-to-resume'; this.notify(); } }); }
    catch { if (token === this.generation) { this.audioState = 'blocked'; this.error = 'tap-to-resume'; this.notify(); } }
  }
  pause() { if (this.current) { this.generation++; this.audio.pause(); this.audioState = 'paused'; this.notify(); } }
  resume() {
    if (!this.current || !this.mode || this.audioState === 'playing') return;
    // A gesture retries the current part, never restarts an already completed announcement.
    const index = this.current.index, time = this.audio?.currentTime || 0;
    this.playPart(); if (this.current?.index === index && time) { try { this.audio.currentTime = time; } catch {} }
  }
  cancelAudio() {
    this.generation++; this.queue = []; this.current = null;
    if (this.audio) { this.audio.onended = this.audio.onerror = this.audio.onpause = null; this.audio.pause(); this.audio.removeAttribute('src'); this.audio.load(); }
    this.audioState = 'idle'; this.error = '';
  }
  stop() { this.cancelAudio(); this.route = null; this.progress = 0; this.preview = false; this.notify(); }
}
let storage; try { storage = globalThis.localStorage; } catch {}
export const navigationSession = new NavigationSession({storage, mediaSession: globalThis.navigator?.mediaSession});
