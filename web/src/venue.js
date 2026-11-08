import { navigatorEngine as engine } from "./venue-engine.js";
import { NativeMap, readMesh } from "./venue-map.js";
import { navigationSession as voice } from "./navigation-session.js";

const instances = new WeakMap();
const soundIcons = ["ya_sound_off", "ya_sound_important", "ya_sound_on"];
const soundLabels = ["Звук выкл", "Важные", "Все подсказки"];
const categoryIcons = {
  wc: "ya_cat_wc",
  lift: "ya_elevator_small",
  stair: "ya_stairs_glyph",
  escalator: "ya_escalator_small",
  entrance: "ya_cat_door",
  food: "ya_cat_food",
  hall: "ya_cat_hall",
  meet: "ya_cat_meet",
  home: "ya_cat_home",
};
export const fmtDist = (m) =>
  m < 10
    ? `${Math.max(1, Math.trunc(m))} м`
    : m < 1000
      ? `${Math.round(m / 5) * 5} м`
      : `${(m / 1000).toFixed(1)} км`;
const fmtMin = (s) => `${Math.max(1, Math.round(s / 60))} мин`;
const arrival = (seconds) =>
  new Date(Date.now() + seconds * 1000).toLocaleTimeString("ru-RU", {
    hour: "2-digit",
    minute: "2-digit",
  });

export async function renderVenue(
  container,
  { onBack = () => {}, language = "ru", onPickPlace = null } = {},
) {
  instances.get(container)?.();
  if (!document.querySelector("link[data-venue-native]")) {
    const link = document.createElement("link");
    link.rel = "stylesheet";
    link.href = new URL("./venue-native.css", import.meta.url).href;
    link.dataset.venueNative = "";
    document.head.append(link);
  }
  const abort = new AbortController(),
    { signal } = abort;
  const on = (el, name, fn, options = {}) =>
    el.addEventListener(name, fn, { ...options, signal });
  const root = document.createElement("section");
  root.className = "venue-event";
  root.lang = "ru";
  root.setAttribute("aria-label", "Карта площадки");
  container.append(root);
  let disposed = false,
    map,
    overlay,
    sheet,
    dim,
    unsubscribe,
    voiceUnsubscribe,
    panelObserver,
    info,
    meta,
    mesh;
  let searchTicket = 0,
    etaTimer,
    toastTimer,
    sheetReturnFocus;
  const node = (tag, parent, text, cls) => {
    const el = document.createElement(tag);
    if (cls) el.className = cls;
    if (text != null) el.textContent = text;
    parent?.append(el);
    return el;
  };
  const safe =
    (fn) =>
    async (...args) => {
      try {
        await fn(...args);
      } catch (error) {
        if (!disposed) toast(error.message);
      }
    };
  const icon = (parent, name) => {
    const img = node("img", parent, null, "venue-icon");
    img.src = new URL(`./navigator/icons/${name}.svg`, import.meta.url).href;
    img.alt = "";
    img.draggable = false;
    return img;
  };
  const button = (parent, label, fn, cls = "", glyph) => {
    const el = node("button", parent, null, cls);
    el.type = "button";
    el.setAttribute("aria-label", label);
    if (glyph) icon(el, glyph);
    else el.textContent = label;
    on(el, "click", safe(fn));
    return el;
  };
  const labelButton = (parent, label, glyph, fn, cls = "") => {
    const el = button(parent, label, fn, cls, glyph);
    node("span", el, label);
    return el;
  };
  button(root, "В меню SBS", onBack, "venue-menu-back");
  const status = button(
    root,
    "Загрузка карты…",
    () => openSettings(),
    "venue-status",
  );
  status.disabled = true;
  const message = node("div", root, "", "venue-toast");
  message.hidden = true;
  message.setAttribute("role", "status");
  function toast(text) {
    if (disposed) return;
    message.textContent = text;
    message.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => {
      message.hidden = true;
    }, 4500);
  }
  const loading = node("div", root, null, "venue-loading");
  const logo = node("img", loading);
  logo.src = "/app/assets/sirius_icon.jpg";
  logo.alt = "";
  node("strong", loading, "НТУ «Сириус»");
  const loadStatus = node("p", loading, "Загрузка карты…");
  const dispose = () => {
    if (disposed) return;
    disposed = true;
    if (map) {
      const { cx, cy, scale, rot } = map.vp;
      engine.state.camera = { cx, cy, scale, rot };
    }
    searchTicket++;
    abort.abort();
    map?.dispose();
    unsubscribe?.();
    voiceUnsubscribe?.();
    panelObserver?.disconnect();
    clearInterval(etaTimer);
    clearTimeout(toastTimer);
    removal.disconnect();
    root.remove();
    if (instances.get(container) === dispose) instances.delete(container);
  };
  instances.set(container, dispose);
  const removal = new MutationObserver(() => {
    if (!root.isConnected) dispose();
  });
  removal.observe(document.body, { childList: true, subtree: true });
  try {
    [info, meta, mesh] = await Promise.all([
      engine.initialize(),
      fetch(new URL("./navigator/map/meta.json", import.meta.url), {
        signal,
      }).then((r) => {
        if (!r.ok) throw new Error("Карта недоступна");
        return r.json();
      }),
      fetch(new URL("./navigator/map/floor2.bin", import.meta.url), { signal })
        .then((r) => {
          if (!r.ok) throw new Error("Карта недоступна");
          return r.arrayBuffer();
        })
        .then(readMesh),
    ]);
  } catch (error) {
    if (!disposed) {
      loadStatus.textContent =
        "Не удалось загрузить карту. Проверьте подключение.";
      button(
        loading,
        "Повторить",
        () => {
          dispose();
          renderVenue(container, { onBack, language });
        },
        "venue-primary",
      );
    }
    return dispose;
  }
  if (disposed) return dispose;
  map = new NativeMap(root, meta, mesh, {
    signal,
    onTap: safe(async (x, y, long) => {
      if (engine.state.mode === "guide" && !long) return;
      const place = await engine.call("point", { x, y, floor: 2, long });
      if (!disposed) place ? showPlace(place) : closeSheet();
    }),
    onRoute: selectRoute,
    onChange: () => layoutHud(),
  });
  map.northRot = -info.northHeading;
  if (engine.state.camera) Object.assign(map.vp, engine.state.camera);
  const browse = node("div", root, null, "venue-browse-dock");
  labelButton(
    browse,
    "Куда?",
    "ya_route",
    () => openSearch(false),
    "venue-where",
  );
  const bar = node("div", browse, null, "venue-browse-bar");
  labelButton(bar, "Поиск", "ya_search", () => openSearch(false));
  labelButton(bar, "История", "ya_bookmark", () => openSearch(false));
  labelButton(bar, "Настройки", "ya_gear", openSettings);
  const floor = node("div", root, null, "venue-floor-box");
  button(
    floor,
    "2 этаж",
    () =>
      toast(
        "На карте показан 2 этаж. Переходы между этажами указаны в шагах маршрута.",
      ),
    "venue-floor-button",
  ).textContent = "2";
  const zoom = node("div", root, null, "venue-zoom");
  button(
    zoom,
    "Приблизить",
    () => map.zoomBy(1.6),
    "venue-map-button",
    "ya_plus",
  );
  button(
    zoom,
    "Отдалить",
    () => map.zoomBy(1 / 1.6),
    "venue-map-button",
    "ya_minus",
  );
  const compass = button(
    root,
    "На север",
    () => map.animate({ ...map.vp, rot: map.northRot }),
    "venue-map-button venue-compass",
    "ya_compass",
  );
  const planPanel = node("div", root, null, "venue-plan venue-bottom-panel");
  planPanel.hidden = true;
  const etaPanel = node("div", root, null, "venue-eta venue-bottom-panel");
  etaPanel.hidden = true;
  function dragHandle(panel, dismiss) {
    const handle = button(
      panel,
      "Развернуть панель",
      () => setExpanded(!engine.state.expanded),
      "venue-handle",
    );
    handle.replaceChildren();
    node("span", handle);
    panelDrag(panel, handle, dismiss);
  }
  dragHandle(planPanel, closePlan);
  const planHeader = node("div", planPanel, null, "venue-plan-header");
  button(planHeader, "Отменить маршрут", closePlan, "venue-close", "ya_close");
  const planTitles = button(
    planHeader,
    "Изменить маршрут",
    () => openSearch(false),
    "venue-plan-titles",
  );
  planTitles.replaceChildren();
  const planFrom = node("span", planTitles, "", "venue-plan-from"),
    planTo = node("strong", planTitles, "", "venue-plan-to");
  node("div", planHeader, null, "venue-header-spacer");
  const cards = node("div", planPanel, null, "venue-route-cards"),
    planInfo = node("p", planPanel, "", "venue-plan-info"),
    planRow = node("div", planPanel, null, "venue-plan-actions");
  const accessible = button(
    planRow,
    "Без лестниц и эскалаторов",
    async () => {
      engine.settings.accessible = !engine.settings.accessible;
      engine.save();
      await plan();
    },
    "venue-map-button venue-accessible",
    "ya_accessible",
  );
  const go = button(
    planRow,
    "Поехали",
    startGuidance,
    "venue-primary venue-go",
  );
  button(planRow, "Настройки", openSettings, "venue-map-button", "ya_gear");
  const steps = node("div", planPanel, null, "venue-plan-steps");
  dragHandle(etaPanel, null);
  const etaRow = node("div", etaPanel, null, "venue-eta-row");
  button(
    etaRow,
    "Обзор маршрута",
    () => fitRoute(),
    "venue-map-button",
    "ya_route",
  );
  const etaMiddle = node("div", etaRow, null, "venue-eta-middle"),
    etaNumbers = node("div", etaMiddle, null, "venue-eta-numbers");
  const etaDistance = node("strong", etaNumbers),
    etaArrival = node("strong", etaNumbers),
    etaMinutes = node("strong", etaNumbers);
  const etaProgress = node("progress", etaMiddle);
  etaProgress.max = 1000;
  etaProgress.value = 0;
  etaProgress.setAttribute("aria-label", "Пройденная часть маршрута");
  button(
    etaRow,
    "Завершить маршрут",
    askFinish,
    "venue-map-button",
    "ya_close",
  );
  const etaExtra = node("div", etaPanel, null, "venue-eta-extra"),
    etaTiles = node("div", etaExtra, null, "venue-eta-tiles");
  labelButton(etaTiles, "Обзор", "ya_route", () => {
    setExpanded(false);
    fitRoute();
  });
  const sound = labelButton(
    etaTiles,
    soundLabels[voice.mode],
    soundIcons[voice.mode],
    cycleVoice,
  );
  button(etaExtra, "Завершить маршрут", askFinish, "venue-danger");
  const resume = labelButton(
    root,
    "Продолжить подсказку",
    "ya_sound_on",
    () => voice.resume(),
    "venue-resume",
  );
  resume.hidden = true;
  status.disabled = false;
  status.textContent =
    "Позиционирование недоступно. Выберите начало вручную: поиск или карта → «Отсюда»";
  status.setAttribute("aria-label", status.textContent);
  const statusText = status.textContent;
  status.replaceChildren();
  node("span", status, statusText);
  voice.configure({
    baseUrl: "/app/navigator/sounds/ru_alice/",
    files: Object.fromEntries(
      engine.recordings.map((p) => [p.replace("sounds/ru_alice/", ""), {}]),
    ),
  });
  voice.setSettings({ mode: engine.settings.voice });
  voiceUnsubscribe = voice.subscribe((state) => {
    resume.hidden = !["paused", "blocked"].includes(state.audioState);
    sound.querySelector("img").src = new URL(
      `./navigator/icons/${soundIcons[state.mode]}.svg`,
      import.meta.url,
    ).href;
    sound.querySelector("span").textContent = soundLabels[state.mode];
  });
  unsubscribe = engine.subscribe(renderState);
  panelObserver = new ResizeObserver(layoutHud);
  for (const el of [root, browse, planPanel, etaPanel])
    panelObserver.observe(el);
  map.onFit = () => map.fit(info.footprint, freeRect());
  loading.remove();
  root.dataset.ready = "true";
  requestAnimationFrame(() => {
    if (!disposed && !engine.state.camera) map.fit(info.footprint, freeRect());
  });
  etaTimer = setInterval(updateEta, 10000);
  on(root, "keydown", (event) => {
    if (event.key === "Escape") {
      event.preventDefault();
      if (overlay) closeOverlay();
      else if (sheet) closeSheet();
      else if (engine.state.expanded) setExpanded(false);
      else if (engine.state.mode === "plan") closePlan();
      else if (engine.state.mode === "guide") askFinish();
      else onBack();
    }
  });

  function freeRect() {
    const w = root.clientWidth,
      h = root.clientHeight,
      landscape = w > h;
    let left = 16,
      top = 108,
      bottom = h - 12;
    const panel = !planPanel.hidden
        ? planPanel
        : !etaPanel.hidden
          ? etaPanel
          : browse,
      r = panel.getBoundingClientRect(),
      rr = root.getBoundingClientRect();
    if (landscape && r.width < w * 0.7)
      left = Math.max(left, r.right - rr.left + 16);
    else bottom = r.top - rr.top - 12;
    return {
      x: left,
      y: top,
      width: Math.max(80, w - 76 - left),
      height: Math.max(120, bottom - top),
    };
  }
  function layoutHud() {
    if (!map || disposed || !root.querySelector(".venue-zoom")) return;
    const w = root.clientWidth,
      h = root.clientHeight,
      rr = root.getBoundingClientRect();
    let blockR = 0,
      blockL = 0;
    for (const panel of [browse, planPanel, etaPanel, sheet].filter(Boolean)) {
      if (panel.hidden) continue;
      const r = panel.getBoundingClientRect(),
        visible = Math.max(0, rr.bottom - r.top);
      if (r.right > rr.right - 80)
        blockR = Math.max(blockR, visible + (panel === browse ? 4 : 0));
      if (r.left < rr.left + 80)
        blockL = Math.max(blockL, visible + (panel === browse ? 4 : 0));
    }
    compass.style.bottom = `${blockR + 12}px`;

    const floorBottom = 120,
      high = h - 12 - blockR - 56 - 12,
      low = floorBottom + 12;
    zoom.hidden = high - low < 120;
    if (!zoom.hidden)
      zoom.style.top = `${Math.max(low + 60, Math.min(high - 60, h / 2)) - 60}px`;
    let angle = map.vp.rot - map.northRot;
    while (angle > Math.PI) angle -= Math.PI * 2;
    while (angle < -Math.PI) angle += Math.PI * 2;
    compass.hidden = Math.abs(angle) <= 0.05;
    compass.querySelector("img").style.transform = `rotate(${angle}rad)`;
    root.classList.toggle("venue-landscape", w > h);
    root.classList.toggle("venue-wide", w >= 600);
    root.classList.toggle("venue-compact", h < 520);
  }
  function renderState(state) {
    if (disposed) return;
    root.dataset.mode = state.mode;
    browse.hidden = state.mode !== "browse";
    planPanel.hidden = state.mode !== "plan";
    etaPanel.hidden = state.mode !== "guide";
    planPanel.classList.toggle("expanded", state.expanded);
    etaPanel.classList.toggle("expanded", state.expanded);
    Object.assign(map, {
      routes: state.routes,
      active: state.active,
      guiding: state.mode === "guide",
      from: state.from,
      to: state.to,
      closures: [],
      showAccess: engine.settings.showAccess,
      access: info.access,
      level: engine.settings.level,
    });
    if (state.mode === "plan") fillPlan();
    if (state.mode === "guide") updateEta();
    map.draw();
    requestAnimationFrame(layoutHud);
  }
  function setExpanded(value) {
    engine.state.expanded = value;
    engine.notify();
  }
  function panelDrag(panel, handle, dismiss, dismissImmediately = false) {
    let first,
      delta = 0,
      moving = false,
      source;
    on(panel, "pointerdown", (event) => {
      if (event.button) return;
      const target = event.target;
      if (
        !handle.contains(target) &&
        target.closest("button,input,select,a,.venue-route-cards")
      )
        return;
      if (!handle.contains(target)) { for (let element=target; element && element!==panel; element=element.parentElement) if(element.scrollTop>0 && element.scrollHeight>element.clientHeight) return; }
      first = { y: event.clientY, t: performance.now() };
      delta = 0;
      moving = false;
      source = event.pointerId;
    });
    on(panel, "pointermove", (event) => {
      if (!first || event.pointerId !== source) return;
      delta = event.clientY - first.y;
      if (Math.abs(delta) > 8) {
        moving = true;
        panel.setPointerCapture(event.pointerId);
        panel.style.setProperty("--drag-y", `${Math.max(-32, delta)}px`);
      }
    });
    on(panel, "pointerup", (event) => {
      if (!first || event.pointerId !== source) return;
      const elapsed = performance.now() - first.t;
      first = null;
      panel.style.removeProperty("--drag-y");
      if (moving) {
        event.preventDefault();
        if (delta < -40 || (delta < -15 && elapsed < 200)) setExpanded(true);
        else if (delta > 60 || (delta > 20 && elapsed < 200)) {
          if (!dismissImmediately && engine.state.expanded) setExpanded(false);
          else dismiss?.();
        }
      }
    });
    on(panel, "pointercancel", () => {
      first = null;
      panel.style.removeProperty("--drag-y");
    });
    on(
      handle,
      "click",
      (event) => {
        if (moving) {
          event.preventDefault();
          event.stopImmediatePropagation();
          moving = false;
        }
      },
      { capture: true },
    );
  }
  function closeSheet() {
    const previousSheet=sheet, previousDim=dim;
    sheet = dim = null;
    for (const element of [previousSheet,previousDim]) if(element) {
      element.style.pointerEvents='none';
      if(!matchMedia('(prefers-reduced-motion: reduce)').matches && element.animate){const animation=element.animate([{opacity:1},{opacity:0}],{duration:160,easing:'ease-out'});animation.onfinish=()=>element.remove();animation.oncancel=()=>element.remove();}else element.remove();
    }
    layoutHud();
    sheetReturnFocus?.focus({ preventScroll: true });
  }
  function openSheet(title, modal = true) {
    closeSheet();
    sheetReturnFocus = document.activeElement;
    if (modal) {
      dim = node("button", root, null, "venue-dim");
      dim.setAttribute("aria-label", "Закрыть");
      on(dim, "click", closeSheet);
    }
    sheet = node("section", root, null, "venue-sheet venue-bottom-panel");
    sheet.setAttribute("role", "dialog");
    sheet.setAttribute("aria-label", title);
    const handle = button(sheet, "Закрыть", closeSheet, "venue-handle");
    handle.replaceChildren();
    node("span", handle);
    panelDrag(sheet, handle, closeSheet, true);
    const body = node("div", sheet, null, "venue-sheet-content");
    node("h2", body, title);
    const close = button(
      sheet,
      "Закрыть",
      closeSheet,
      "venue-sheet-close",
      "ya_close",
    );
    close.focus({ preventScroll: true });
    requestAnimationFrame(layoutHud);
    return body;
  }
  function closeOverlay() {
    overlay?.remove();
    overlay = null;
    searchTicket++;
    map.canvas.focus({ preventScroll: true });
  }
  async function openSearch(editFrom) {
    closeSheet();
    closeOverlay();
    const current = node("section", root, null, "venue-search-screen");
    overlay = current;
    const head = node("div", overlay, null, "venue-search-header");
    button(head, "Назад", closeOverlay, "venue-close", "ya_back");
    node("h2", head, "Маршрут");
    node("div", head, null, "venue-header-spacer");
    labelButton(
      overlay,
      engine.state.from?.title || "Откуда",
      "ya_dot_from",
      () => openSearch(true),
      "venue-endpoint " + (editFrom ? "selected" : ""),
    );
    labelButton(
      overlay,
      engine.state.to?.title || "Куда",
      "ya_dot_to",
      () => openSearch(false),
      "venue-endpoint " + (!editFrom ? "selected" : ""),
    );
    const input = node("input", overlay, null, "venue-search-input");
    input.type = "search";
    input.autocomplete = "off";
    input.placeholder = editFrom
      ? "Откуда: номер, название, лифт…"
      : "Куда: номер, название, лифт…";
    input.setAttribute("aria-label", editFrom ? "Откуда" : "Куда");
    const results = node("div", overlay, null, "venue-search-results");
    results.setAttribute("aria-live", "polite");
    const pick = async (item) => {
      const place = await engine.call("pick", { key: item.key });
      if (disposed || overlay !== current || !place) return;
      input.blur();
      closeOverlay();
      if (onPickPlace) { onPickPlace(place); return; }
      if (editFrom) engine.state.from = place;
      else engine.state.to = place;
      await plan();
    };
    const resultRow = (item) => {
      const row = button(
        results,
        item.title,
        () => pick(item),
        "venue-search-result",
      );
      row.replaceChildren();
      node("span", row, item.title);
      node("small", row, item.sub);
    };
    async function query(parameters) {
      const ticket = ++searchTicket,
        items = await engine.call("search", parameters);
      if (disposed || overlay !== current || ticket !== searchTicket) return;
      results.replaceChildren();
      if (!items.length)
        node("p", results, "Ничего не найдено", "venue-search-empty");
      items.forEach(resultRow);
    }
    async function home() {
      const ticket = ++searchTicket;
      results.replaceChildren();
      const chips = node("div", results, null, "venue-category-grid");
      for (const [id, title] of info.categories)
        labelButton(chips, title, categoryIcons[id], () =>
          query({ category: id }),
        );
      if (engine.settings.history.length) {
        node("p", results, "История", "venue-history-title");
        for (const title of engine.settings.history) {
          const list = await engine.call("search", { query: title });
          if (disposed || overlay !== current || ticket !== searchTicket)
            return;
          const item = list.find((it) => it.title === title);
          if (item) resultRow(item);
        }
      }
    }
    on(
      input,
      "input",
      safe(() => (input.value.trim() ? query({ query: input.value }) : home())),
    );
    on(input, "keydown", (event) => {
      if (event.key === "Enter") {
        event.preventDefault();
        input.blur();
      }
    });
    await home();
    if (!disposed && overlay === current) input.focus({ preventScroll: true });
  }
  function showPlace(place) {
    const body = openSheet(place.title, false);
    node("p", body, place.sub, "venue-place-sub");
    if (onPickPlace) { button(body, "Выбрать место встречи", () => onPickPlace(place), "venue-primary"); return; }
    if (engine.state.mode !== "guide") {
      const actions = node("div", body, null, "venue-place-actions");
      button(
        actions,
        "Отсюда",
        async () => {
          closeSheet();
          engine.state.from = place;
          await plan();
        },
        "venue-secondary",
      );
      button(
        actions,
        "Сюда",
        async () => {
          closeSheet();
          engine.state.to = place;
          await plan();
        },
        "venue-primary",
      );
    }

  }
  async function plan() {
    const state = engine.state,
      ticket = ++engine.planSequence;
    state.mode = "plan";
    state.routes = [];
    state.active = 0;
    state.expanded = false;
    state.message = !state.to
      ? "Выберите, куда идти: поиск или точка на карте → «Сюда»"
      : !state.from
        ? "Местоположение не определено. Укажите, откуда идти: поиск или точка на карте → «Отсюда»"
        : "Строим маршрут…";
    engine.notify();
    if (!state.from || !state.to) {
      if (state.to)
        map.focus(
          state.to.x,
          state.to.y,
          freeRect(),
          Math.max(map.vp.scale, 6 / map.density),
        );
      return;
    }
    try {
      const routes = await engine.call("plan", {
        from: state.from.key,
        to: state.to.key,
        accessible: engine.settings.accessible,
        level: engine.settings.level,
      });
      if (ticket !== engine.planSequence || state.mode !== "plan") return;
      state.routes = routes;
      state.message = routes.length
        ? ""
        : engine.settings.accessible
          ? "Маршрут без лестниц и эскалаторов не найден"
          : "Маршрут не найден (проход перекрыт или не распознан на плане)";
      engine.notify();
      if (routes.length && !disposed)
        requestAnimationFrame(() => {
          if (!disposed) fitRoute();
        });
    } catch (error) {
      if (ticket === engine.planSequence) {
        state.message = "Ошибка построения маршрута. Повторите попытку.";
        engine.notify();
        toast(error.message);
      }
    }
  }
  function fillPlan() {
    const state = engine.state;
    planFrom.textContent = "Откуда: " + (state.from?.title || "не выбрано");
    planTo.textContent = "Куда: " + (state.to?.title || "не выбрано") + " ▾";
    accessible.classList.toggle("selected", engine.settings.accessible);
    accessible.setAttribute("aria-pressed", String(engine.settings.accessible));
    go.disabled = !state.routes.length;
    cards.replaceChildren();
    steps.replaceChildren();
    if (!state.routes.length) {
      const missing = node("div", cards, null, "venue-route-message");
      node("p", missing, state.message);
      if (!state.to || !state.from)
        button(
          missing,
          state.to ? "Выбрать, откуда" : "Выбрать, куда",
          () => openSearch(Boolean(state.to)),
          "venue-primary",
        );
      else if (!state.message.includes("Строим"))
        button(missing, "Повторить", plan, "venue-secondary");
      planInfo.hidden = true;
      return;
    }
    state.routes.forEach((route, index) => {
      const card = button(
        cards,
        `${route.label}, ${fmtMin(route.time)}, ${fmtDist(route.walk)}`,
        () => selectRoute(index),
        "venue-route-card" + (index === state.active ? " selected" : ""),
      );
      card.setAttribute("aria-pressed", String(index === state.active));
      card.replaceChildren();
      node("span", card, route.label, "venue-card-label");
      node("strong", card, fmtMin(route.time), "venue-card-time");
      node("span", card, fmtDist(route.walk), "venue-card-dist");
      node(
        "small",
        card,
        route.connSteps.length
          ? route.connSteps.map((c) => c.name || c.kind).join(" → ")
          : "без смены этажа",
        "venue-card-via",
      );
    });
    const route = state.routes[state.active],
      turns = route.maneuvers.filter(
        (m) => !["START", "FINISH"].includes(m.type),
      ).length;
    const floorNotice = route.legs.some((l) => l.floor !== 2)
      ? " · Маршрут проходит по другим этажам; карта показывает только 2 этаж"
      : "";
    planInfo.hidden = false;
    planInfo.textContent =
      (route.unreachable
        ? "Прохода к цели нет — маршрут ведёт как можно ближе"
        : route.accessStop
          ? route.accessStop.note + " — маршрут ведёт до прохода"
          : (engine.settings.accessible ? "Без лестниц и эскалаторов · " : "") +
            (turns
              ? `${turns} ${plural(turns, "манёвр", "манёвра", "манёвров")}`
              : "без поворотов") +
            (state.from.approx || state.to.approx
              ? " · вход в помещение на плане не распознан, ведём к ближайшей точке"
              : "") +
            (state.closures.length
              ? ` · учтены перекрытия: ${state.closures.length}`
              : "") +
            " · ↑ по шагам") + floorNotice;
    planInfo.classList.toggle(
      "warning",
      route.unreachable || Boolean(route.accessStop),
    );
    node("h3", steps, "Маршрут по шагам");
    let previous = 0;
    route.maneuvers.forEach((m) => {
      if (m.type === "START") return;
      const row = button(
        steps,
        m.text,
        () => {
          setExpanded(false);
          if (m.floor !== 2)
            toast(
              `Шаг проходит на другом этаже (${m.floor}). Карта показывает только 2 этаж.`,
            );
          map.focus(
            m.x,
            m.y,
            freeRect(),
            Math.max(map.vp.scale, 10 / map.density),
          );
        },
        "venue-step",
      );
      row.replaceChildren();
      icon(
        row,
        m.type === "CONNECTOR"
          ? m.icon.includes("lift")
            ? "ya_elevator_small"
            : m.icon.includes("escalator")
              ? "ya_escalator_small"
              : "ya_stairs_glyph"
          : m.icon,
      );
      const col = node("span", row, null, "venue-step-text");
      node(
        "span",
        col,
        m.type === "FINISH" ? "Прибытие · " + state.to.title : m.text,
      );
      const hint =
        m.type === "CONNECTOR" ? m.conn : m.type === "FINISH" ? "" : m.hint;
      if (hint) node("small", col, hint);
      node("small", row, fmtDist(Math.max(1, m.s - previous)));
      previous = m.s;
    });
  }
  function selectRoute(index) {
    engine.state.active = index;
    engine.notify();
  }
  function fitRoute() {
    const route = engine.state.routes[engine.state.active];
    if (!route) return;
    const legs = route.legs.filter((l) => l.floor === route.legs[0].floor);
    if (route.legs[0].floor !== 2)
      toast(
        "Маршрут начинается на другом этаже. Карта показывает только 2 этаж.",
      );
    const xs = legs.flatMap((l) => l.xs),
      ys = legs.flatMap((l) => l.ys);
    map.fit(
      [
        Math.min(...xs) - 10,
        Math.min(...ys) - 10,
        Math.max(...xs) + 10,
        Math.max(...ys) + 10,
      ],
      freeRect(),
    );
  }
  async function startGuidance() {
    const state = engine.state;
    if (!state.routes.length || state.mode !== "plan") return;
    await voice.unlock();
    const result = await engine.call("start", {
      index: state.active,
      timeMs: performance.now(),
    });
    if (disposed || state.mode !== "plan") return;
    state.mode = "guide";
    state.expanded = false;
    voice.setNativeRoute(
      state.routes[state.active],
      state.from.key,
      state.to.key,
    );
    for (const prompt of result.prompts) voice.say(prompt);
    engine.settings.rareDays = result.rareDays;
    engine.remember(state.to);
    engine.notify();
  }
  function updateEta() {
    const route = engine.state.routes[engine.state.active];
    if (!route) return;
    etaDistance.textContent = fmtDist(route.walk);
    etaArrival.textContent = arrival(route.time);
    etaMinutes.textContent = fmtMin(route.time);
  }
  function closePlan() {
    engine.planSequence++;
    engine.state.mode = "browse";
    engine.state.routes = [];
    engine.state.from = engine.state.to = null;
    engine.state.expanded = false;
    voice.stop();
    engine.call("stop").catch(() => {});
    engine.notify();
  }
  function askFinish() {
    const body = openSheet("Завершить маршрут?");
    button(
      body,
      "Да, завершить",
      () => {
        closeSheet();
        closePlan();
      },
      "venue-danger",
    );
    button(body, "Продолжить", closeSheet, "venue-secondary");
  }
  function cycleVoice() {
    const mode = (voice.mode + 2) % 3;
    engine.settings.voice = mode;
    engine.save();
    voice.setSettings({ mode });
    engine.call("voice", { mode }).catch(() => {});
    toast(soundLabels[mode]);
  }
  function openSettings() {
    if (!info) return;
    const body = openSheet("Настройки"),
      heading = (text) => node("h3", body, text, "venue-setting-heading"),
      note = (text) => node("p", body, text, "venue-note");
    const segmented = (labels, selected, change) => {
      const row = node("div", body, null, "venue-segmented");
      labels.forEach((text, index) => {
        const control = button(row, text, async () => {
          row.querySelectorAll("button").forEach((el, i) => {
            el.classList.toggle("selected", i === index);
            el.setAttribute("aria-pressed", String(i === index));
          });
          await change(index);
        });
        control.classList.toggle("selected", selected === index);
        control.setAttribute("aria-pressed", String(selected === index));
      });
    };
    const toggle = (text, checked, change) => {
      const row = node("label", body, null, "venue-switch-row");
      node("span", row, text);
      const control = node("input", row);
      control.type = "checkbox";
      control.checked = checked;
      on(
        control,
        "change",
        safe(() => change(control.checked)),
      );
    };
    heading("Голосовые подсказки");
    segmented(["Выкл", "Важные", "Все"], voice.mode, async (mode) => {
      engine.settings.voice = mode;
      engine.save();
      voice.setSettings({ mode });
      await engine.call("voice", { mode });
    });
    toggle(
      "Без лестниц и эскалаторов (маломобильным)",
      engine.settings.accessible,
      async (checked) => {
        engine.settings.accessible = checked;
        engine.save();
        engine.notify();
        if (engine.state.mode === "plan") await plan();
      },
    );
    note(
      "Позиционирование недоступно. Выберите начало вручную: поиск или карта → «Отсюда».",
    );

  }
  return dispose;
}
function plural(n, a, b, c) {
  const m = n % 10,
    h = n % 100;
  return m === 1 && h !== 11
    ? a
    : m >= 2 && m <= 4 && (h < 10 || h >= 20)
      ? b
      : c;
}
