// One native-core worker per application session, retained when returning to the menu.
class NavigatorClient {
  constructor() {
    this.worker = null;
    this.sequence = 0;
    this.planSequence = 0;
    this.pending = new Map();
    this.initialized = null;
    this.state = {
      mode: "browse",
      from: null,
      to: null,
      routes: [],
      active: 0,
      closures: [],
      expanded: false,
    };
    this.listeners = new Set();
    try {
      this.settings = JSON.parse(
        localStorage.getItem("sbs-native-navigation") || "{}",
      );
    } catch {
      this.settings = {};
    }
    this.settings = {
      voice: 2,
      level: 1,
      accessible: false,
      showAccess: false,
      history: [],
      ...this.settings,
      level: 0,
      showAccess: false,
    };
  }
  async initialize() {
    if (this.initialized) return this.initialized;
    this.worker = new Worker(new URL("./venue-worker.js", import.meta.url), {
      type: "module",
    });
    this.worker.onmessage = ({ data }) => {
      const request = this.pending.get(data.id);
      if (!request) return;
      this.pending.delete(data.id);
      clearTimeout(request.timer);
      data.error
        ? request.reject(new Error(data.error))
        : request.resolve(data.result);
    };
    this.worker.onerror = () =>
      this.fail(new Error("Не удалось запустить навигацию"));
    this.initialized = this.call("init")
      .then(async (info) => {
        this.info = info;
        const voice = await fetch(
          new URL("./navigator/voice_variants.json", import.meta.url),
        );
        if (!voice.ok)
          throw new Error("Не удалось загрузить голосовые подсказки");
        const manifest = await (
          await fetch(new URL("./navigator/provenance.json", import.meta.url))
        ).json();
        this.recordings = Object.keys(manifest.assets).filter((p) =>
          p.startsWith("sounds/"),
        );
        await this.call("voiceInit", {
          text: await voice.text(),
          seed: Date.now(),
          rareDays: this.settings.rareDays || {},
          recordings: this.recordings
            .filter((p) => p.endsWith("/0.mp3"))
            .map((p) => p.split("/")[2]),
        });
        await this.call("voice", { mode: this.settings.voice });
        return info;
      })
      .catch((error) => {
        this.fail(error);
        throw error;
      });
    return this.initialized;
  }
  fail(error) {
    for (const request of this.pending.values()) {
      clearTimeout(request.timer);
      request.reject(error);
    }
    this.pending.clear();
    this.worker?.terminate();
    this.worker = null;
    this.initialized = null;
  }
  call(type, parameters = {}) {
    return new Promise((resolve, reject) => {
      const id = ++this.sequence;
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error("Навигация не ответила. Откройте карту снова."));
      }, 60000);
      this.pending.set(id, { resolve, reject, timer });
      this.worker.postMessage({ type, ...parameters, requestId: id });
    });
  }
  save() {
    try {
      localStorage.setItem(
        "sbs-native-navigation",
        JSON.stringify(this.settings),
      );
    } catch {}
  }
  notify() {
    for (const fn of this.listeners) fn(this.state);
  }
  subscribe(fn) {
    this.listeners.add(fn);
    fn(this.state);
    return () => this.listeners.delete(fn);
  }
  remember(place) {
    this.settings.history = [
      place.title,
      ...this.settings.history.filter((title) => title !== place.title),
    ].slice(0, 12);
    this.save();
  }
}
export const navigatorEngine = new NavigatorClient();
