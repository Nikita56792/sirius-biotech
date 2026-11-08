// Browser rendering of MapGLView.kt / NaviMapView.kt. Same binary meshes,
// shader, palette, viewport equations and gesture thresholds as Android.
export class Viewport {
  constructor(density = 1) {
    this.density = density;
    this.cx = 0;
    this.cy = 0;
    this.scale = 2;
    this.rot = 0;
    this.w = 1;
    this.h = 1;
  }
  toScreen(x, y) {
    const c = Math.cos(this.rot),
      s = Math.sin(this.rot),
      dx = (x - this.cx) * this.scale,
      dy = -(y - this.cy) * this.scale;
    return [this.w / 2 + dx * c - dy * s, this.h / 2 + dx * s + dy * c];
  }
  toWorld(x, y) {
    const c = Math.cos(this.rot),
      s = Math.sin(this.rot),
      ux = x - this.w / 2,
      uy = y - this.h / 2;
    return [
      this.cx + (ux * c + uy * s) / this.scale,
      this.cy - (-ux * s + uy * c) / this.scale,
    ];
  }
  pan(x, y) {
    const c = Math.cos(this.rot),
      s = Math.sin(this.rot);
    this.cx -= (x * c + y * s) / this.scale;
    this.cy += (-x * s + y * c) / this.scale;
  }
  twoFinger(px, py, nx, ny, k, da) {
    const wp = this.toWorld(px, py);
    this.scale = Math.max(
      0.4 / this.density,
      Math.min(600 / this.density, this.scale * k),
    );
    this.rot += da;
    const c = Math.cos(this.rot),
      s = Math.sin(this.rot),
      ux = nx - this.w / 2,
      uy = ny - this.h / 2;
    this.cx = wp[0] - (ux * c + uy * s) / this.scale;
    this.cy = wp[1] + (-ux * s + uy * c) / this.scale;
  }
  ndc() {
    const c = Math.cos(this.rot),
      s = Math.sin(this.rot),
      a = (2 * this.scale * c) / this.w,
      b = (2 * this.scale * s) / this.w;
    const c2 = (-2 * this.scale * s) / this.h,
      d = (2 * this.scale * c) / this.h;
    return new Float32Array([
      a,
      c2,
      0,
      b,
      d,
      0,
      -(a * this.cx + b * this.cy),
      -(c2 * this.cx + d * this.cy),
      1,
    ]);
  }
}

export function readMesh(buffer) {
  const size = new DataView(buffer).getUint32(0, true);
  const header = JSON.parse(
      new TextDecoder().decode(new Uint8Array(buffer, 4, size)),
    ),
    base = (4 + size + 3) & ~3;
  const types = {
    float32: Float32Array,
    uint32: Uint32Array,
    uint16: Uint16Array,
    int32: Int32Array,
    uint8: Uint8Array,
  };
  // The original pack concatenates byte RGB and float layers without padding.
  return Object.fromEntries(
    header.map((h) => [
      h.k,
      new types[h.dtype](buffer.slice(base + h.off, base + h.off + h.len)),
    ]),
  );
}

export class NativeMap {
  constructor(
    parent,
    meta,
    mesh,
    { signal, onTap, onClosure, onRoute, onChange, onError } = {},
  ) {
    this.meta = meta;
    this.floor = meta.floors.find((f) => f.n === 2);
    this.mesh = mesh;
    this.density = window.devicePixelRatio || 1;
    this.vp = new Viewport(this.density);
    this.routes = [];
    this.active = 0;
    this.closures = [];
    this.from = this.to = null;
    this.guiding = false;
    this.showAccess = false;
    this.access = [];
    this.level = 1;
    this.hitClosures = [];
    this.onChange = onChange;
    this.signal = signal;
    this.stopped = false;
    this.glCanvas = parent.appendChild(document.createElement("canvas"));
    this.glCanvas.className = "venue-geometry";
    this.canvas = parent.appendChild(document.createElement("canvas"));
    this.canvas.className = "venue-map";
    this.canvas.tabIndex = 0;
    this.canvas.setAttribute(
      "aria-label",
      "Карта площадки, 2 этаж. Перетаскивание, масштабирование и поворот двумя пальцами.",
    );
    this.ctx = this.canvas.getContext("2d");
    this.images = new Map();
    this.observer = new ResizeObserver(() => this.resize());
    this.observer.observe(parent);
    try {
      this.setupGL();
    } catch (error) {
      this.gl = null;
      onError?.(error);
    }
    this.glCanvas.addEventListener(
      "webglcontextlost",
      (e) => {
        e.preventDefault();
      },
      { signal },
    );
    this.glCanvas.addEventListener(
      "webglcontextrestored",
      () => {
        this.setupGL();
        this.draw();
      },
      { signal },
    );
    this.gestures({ signal, onTap, onClosure, onRoute });
    this.resize();
  }
  image(name) {
    if (!this.images.has(name)) {
      const image = new Image();
      image.src = new URL(
        `./navigator/icons/${name}.svg`,
        import.meta.url,
      ).href;
      image.onload = () => this.draw();
      this.images.set(name, image);
    }
    return this.images.get(name);
  }
  icon(name, x, y, half) {
    const image = this.image(name);
    if (image.complete && image.naturalWidth)
      this.ctx.drawImage(image, x - half, y - half, half * 2, half * 2);
  }
  screen(x, y) {
    return this.vp.toScreen(x - this.meta.center[0], y - this.meta.center[1]);
  }
  world(x, y) {
    const p = this.vp.toWorld(x, y);
    return [p[0] + this.meta.center[0], p[1] + this.meta.center[1]];
  }
  resize() {
    const rect = this.canvas.getBoundingClientRect();
    this.vp.w = rect.width;
    this.vp.h = rect.height;
    const dpr = Math.min(devicePixelRatio || 1, 3);
    for (const canvas of [this.canvas, this.glCanvas]) {
      canvas.width = Math.round(rect.width * dpr);
      canvas.height = Math.round(rect.height * dpr);
    }
    this.draw();
    this.onChange?.();
  }
  setupGL() {
    const gl = this.glCanvas.getContext("webgl2", {
      antialias: true,
      alpha: false,
    });
    if (!gl) return;
    this.gl = gl;
    const shader = (type, code) => {
      const s = gl.createShader(type);
      gl.shaderSource(s, code);
      gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS))
        throw new Error(gl.getShaderInfoLog(s));
      return s;
    };
    const p = gl.createProgram();
    gl.attachShader(
      p,
      shader(
        gl.VERTEX_SHADER,
        `#version 300 es
      uniform mat3 uM; uniform vec4 uColor; uniform int uUseCol; uniform float uAlpha;
      layout(location=0) in vec2 aPos; layout(location=1) in vec3 aCol;
      out vec4 vCol;
      void main(){vec3 p=uM*vec3(aPos,1.0);gl_Position=vec4(p.xy,0.0,1.0);
        vCol=uUseCol==1?vec4(aCol,1.0):uColor;vCol.a*=uAlpha;}`,
      ),
    );
    gl.attachShader(
      p,
      shader(
        gl.FRAGMENT_SHADER,
        `#version 300 es
      precision mediump float;in vec4 vCol;out vec4 o;void main(){o=vCol;}`,
      ),
    );
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS))
      throw new Error(gl.getProgramInfoLog(p));
    this.program = p;
    this.uniforms = Object.fromEntries(
      ["uM", "uColor", "uUseCol", "uAlpha"].map((n) => [
        n,
        gl.getUniformLocation(p, n),
      ]),
    );
    this.gpu = new Map();
    for (const [name, xy, indices, colors] of [
      ["rooms", "room_xy", "room_idx", "room_rgb"],
      ["walls", "wall_xy", "wall_idx"],
      ...[
        "door_xy",
        "stair_xy",
        "furn_xy",
        "furn_lines",
        "path_xy",
        "fence_xy",
        "edit_xy",
        "roof_xy",
      ].map((key) => [key, key]),
    ]) {
      const points = this.mesh[xy];
      if (!points?.length) continue;
      const vao = gl.createVertexArray();
      gl.bindVertexArray(vao);
      const bind = (kind, data) => {
        const buffer = gl.createBuffer();
        gl.bindBuffer(kind, buffer);
        gl.bufferData(kind, data, gl.STATIC_DRAW);
      };
      bind(gl.ARRAY_BUFFER, points);
      gl.enableVertexAttribArray(0);
      gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
      if (colors) {
        bind(gl.ARRAY_BUFFER, this.mesh[colors]);
        gl.enableVertexAttribArray(1);
        gl.vertexAttribPointer(1, 3, gl.UNSIGNED_BYTE, true, 0, 0);
      }
      if (indices) bind(gl.ELEMENT_ARRAY_BUFFER, this.mesh[indices]);
      this.gpu.set(name, {
        vao,
        count: indices ? this.mesh[indices].length : points.length / 2,
        indexed: !!indices,
        colored: !!colors,
      });
    }
    gl.bindVertexArray(null);
  }
  drawGeometry() {
    const gl = this.gl,
      v = this.vp;
    if (!gl) {
      this.drawFallback();
      return;
    }
    if (gl.isContextLost()) return;
    gl.viewport(0, 0, this.glCanvas.width, this.glCanvas.height);
    gl.clearColor(0.925, 0.937, 0.918, 1);
    gl.clear(gl.COLOR_BUFFER_BIT);
    gl.useProgram(this.program);
    gl.uniformMatrix3fv(this.uniforms.uM, false, v.ndc());
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
    gl.uniform1f(this.uniforms.uAlpha, 1);
    const draw = (key, color, width = 1) => {
      const layer = this.gpu.get(key);
      if (!layer) return;
      gl.bindVertexArray(layer.vao);
      gl.uniform1i(this.uniforms.uUseCol, layer.colored ? 1 : 0);
      gl.uniform4fv(this.uniforms.uColor, [...color, 1]);
      gl.lineWidth(width);
      if (layer.indexed)
        gl.drawElements(gl.TRIANGLES, layer.count, gl.UNSIGNED_INT, 0);
      else gl.drawArrays(gl.LINES, 0, layer.count);
    };
    draw("rooms", [1, 1, 1]);
    draw("path_xy", [1, 1, 0.98], v.scale * this.density > 6 ? 4 : 2.5);
    draw(
      "stair_xy",
      [0.55, 0.62, 0.7],
      v.scale * this.density > 15 ? 2.5 : 1.5,
    );
    draw("door_xy", [0.8, 0.82, 0.85], v.scale * this.density > 15 ? 2.5 : 1.5);
    draw(
      this.mesh.furn_xy ? "furn_xy" : "furn_lines",
      [0.78, 0.66, 0.52],
      v.scale * this.density > 15 ? 1.5 : 1,
    );
    draw("walls", [0.62, 0.65, 0.7]);
    draw("fence_xy", [0.3, 0.32, 0.35], 2.5);
    draw("edit_xy", [0.36, 0.4, 0.45], 3);
    draw("roof_xy", [0.58, 0.6, 0.63], 1.5);
    gl.bindVertexArray(null);
  }
  drawFallback() {
    // Same triangles and colours on browsers without WebGL2.
    const ctx = this.ctx,
      v = this.vp;
    ctx.fillStyle = "#ecefea";
    ctx.fillRect(0, 0, v.w, v.h);
    const triangle = (xy, ids, colors, color) => {
      for (let i = 0; i < ids.length; i += 3) {
        ctx.beginPath();
        for (let k = 0; k < 3; k++) {
          const j = ids[i + k] * 2,
            p = v.toScreen(xy[j], xy[j + 1]);
          k ? ctx.lineTo(...p) : ctx.moveTo(...p);
        }
        ctx.closePath();
        const j = ids[i] * 3;
        ctx.fillStyle = colors
          ? `rgb(${colors[j]},${colors[j + 1]},${colors[j + 2]})`
          : color;
        ctx.fill();
      }
    };
    triangle(this.mesh.room_xy, this.mesh.room_idx, this.mesh.room_rgb);
    const line = (key, color) => {
      const a = this.mesh[key];
      if (!a) return;
      ctx.strokeStyle = color;
      ctx.lineWidth = 1;
      ctx.beginPath();
      for (let i = 0; i + 3 < a.length; i += 4) {
        ctx.moveTo(...v.toScreen(a[i], a[i + 1]));
        ctx.lineTo(...v.toScreen(a[i + 2], a[i + 3]));
      }
      ctx.stroke();
    };
    line("stair_xy", "#8c9eb3");
    line("door_xy", "#ccd1d9");
    line("furn_xy", "#c7a885");
    triangle(this.mesh.wall_xy, this.mesh.wall_idx, null, "#9ea6b3");
    line("edit_xy", "#5c6673");
  }
  draw() {
    if (this.stopped || !this.vp.w || !this.ctx) return;
    const ctx = this.ctx,
      v = this.vp,
      dpr = this.canvas.width / v.w;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, v.w, v.h);
    this.drawGeometry();
    // Android uses separate Paint objects for text halos and rounded routes.
    // Keep route stroke settings from leaking into labels on the next redraw.
    ctx.lineJoin = "miter";
    ctx.lineCap = "butt";
    const occupied = [];
    ctx.font = "11px Roboto,Arial,sans-serif";
    ctx.textAlign = "left";
    ctx.textBaseline = "alphabetic";
    let count = 0;
    for (const l of [...this.floor.labels].sort((a, b) => b[4] - a[4])) {
      if (count > 220) break;
      const zone = l[4] >= 2;
      if (
        (!zone && v.scale * this.density < 6) ||
        (zone && v.scale * this.density < 2)
      )
        continue;
      const [x, y] = v.toScreen(l[0], l[1]);
      if (x < -50 || y < -20 || x > v.w + 50 || y > v.h + 20) continue;
      const text =
          !zone && v.scale * this.density >= 20 && l[3]
            ? `${l[2]} ${l[3]}`
            : l[2],
        w = ctx.measureText(text).width;
      const box = [x - w / 2, y - 11, x + w / 2, y + 3];
      if (
        occupied.some(
          (b) =>
            b[0] < box[2] && b[2] > box[0] && b[1] < box[3] && b[3] > box[1],
        )
      )
        continue;
      occupied.push(box);
      ctx.strokeStyle = "#ffffffe6";
      ctx.lineWidth = 3;
      ctx.strokeText(text, box[0], y);
      ctx.fillStyle = zone ? "#78808c" : "#464e58";
      ctx.fillText(text, box[0], y);
      count++;
    }
    if (v.scale * this.density >= 2.5)
      for (const c of this.meta.conns) {
        const p = c.pos["2"];
        if (!p) continue;
        const [x, y] = v.toScreen(...p);
        if (x < -20 || y < -20 || x > v.w + 20 || y > v.h + 20) continue;
        ctx.fillStyle =
          { lift: "#196eff", escalator: "#8c5ac8", stair: "#28a05a" }[c.kind] ||
          "#28a05a";
        this.roundRect(x - 9, y - 9, 18, 18, 4);
        ctx.fill();
        this.icon(
          ({
            lift: "ya_glyph_lift",
            escalator: "ya_glyph_escalator",
            stair: "ya_stairs_glyph",
          }[c.kind] || "ya_stairs_glyph") + "_white",
          x,
          y,
          7.02,
        );
      }
    this.hitClosures = [];
    for (const c of this.closures) {
      let p;
      if (c.connId) {
        const conn = this.meta.conns.find(
          (n) =>
            n.id === c.connId ||
            n.name.replace(/\s*\(.*\)/, "") === c.title.split(":")[0],
        );
        p = conn?.pos["2"];
        if (p) p = v.toScreen(...p);
        else if (c.floor === 2) p = this.screen(c.x, c.y);
      } else if (c.floor === 2) p = this.screen(c.x, c.y);
      if (!p) continue;
      const [x, y] = p;
      if (c.connId) {
        ctx.fillStyle = "#e539353c";
        ctx.beginPath();
        ctx.arc(x, y, 16, 0, Math.PI * 2);
        ctx.fill();
        this.icon("ya_no_entry", x + 9, y - 9, 8);
        this.hitClosures.push({ closure: c, x, y });
        continue;
      }
      ctx.fillStyle = "#e5393537";
      ctx.beginPath();
      ctx.arc(x, y, Math.max(12, c.radius * v.scale), 0, Math.PI * 2);
      ctx.fill();
      this.icon("ya_no_entry", x, y, 13);
      this.hitClosures.push({ closure: c, x, y });
    }
    const routeIds = this.routeAccessIds();
    for (const a of this.access) {
      if (a.floor !== 2) continue;
      const onRoute = routeIds.has(a.id);
      if (!onRoute && !(this.showAccess && v.scale * this.density >= 8))
        continue;
      ctx.beginPath();
      a.xs.forEach((x, i) => {
        const p = this.screen(x, a.ys[i]);
        i ? ctx.lineTo(...p) : ctx.moveTo(...p);
      });
      ctx.setLineDash([4, 4]);
      ctx.globalAlpha = onRoute ? 1 : 120 / 255;
      ctx.lineWidth = onRoute ? 2.5 : 1.5;
      ctx.strokeStyle = a.level > this.level ? "#e65100" : "#788291";
      ctx.stroke();
      ctx.globalAlpha = 1;
      ctx.setLineDash([]);
    }
    this.routes.forEach((r, i) => {
      if (i !== this.active && !this.guiding) this.drawRoute(r, false);
    });
    const active = this.routes[this.active];
    if (active) this.drawRoute(active, true);
    if (!this.guiding && active)
      this.routes.forEach((r, i) => {
        if (i === this.active) return;
        this.drawBalloon(r, active);
      });
    if (active) this.drawConnectors(active);
    this.drawAccessBadges(routeIds, occupied);
    if (this.from?.floor === 2) {
      const [x, y] = this.screen(this.from.x, this.from.y);
      ctx.fillStyle = "#fff";
      ctx.beginPath();
      ctx.arc(x, y, 9, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = "#f44336";
      ctx.beginPath();
      ctx.arc(x, y, 6.5, 0, Math.PI * 2);
      ctx.fill();
    }
    if (this.to?.floor === 2) {
      const [x, y] = this.screen(this.to.x, this.to.y);
      ctx.fillStyle = "#212121";
      ctx.beginPath();
      ctx.arc(x, y - 26, 17, 0, Math.PI * 2);
      ctx.moveTo(x - 7, y - 12);
      ctx.lineTo(x, y - 4);
      ctx.lineTo(x + 7, y - 12);
      ctx.fill();
      this.icon("ya_finish_white", x, y - 26, 10.54);
      ctx.beginPath();
      ctx.arc(x, y, 3, 0, Math.PI * 2);
      ctx.fill();
    }
    this.onChange?.();
  }
  routeAccessIds() {
    const route = this.routes[this.active];
    if (!route) return new Set();
    if (this.accessRoute === route) return this.accessRouteIds;
    this.accessRoute = route;
    const ids = new Set(route.accessStop ? [route.accessStop.id] : []);
    for (const line of this.access) {
      if (line.floor !== 2) continue;
      const near = (x, y) => {
        for (let i = 1; i < line.xs.length; i++)
          if (
            segmentDistance(
              x,
              y,
              line.xs[i - 1],
              line.ys[i - 1],
              line.xs[i],
              line.ys[i],
            ) < 2.5
          )
            return true;
        return false;
      };
      for (const leg of route.legs) {
        if (leg.floor !== 2) continue;
        let found = leg.xs.some((x, i) => near(x, leg.ys[i]));
        for (let i = 1; !found && i < leg.xs.length; i++) {
          const n = Math.max(
            1,
            Math.trunc(
              Math.hypot(leg.xs[i] - leg.xs[i - 1], leg.ys[i] - leg.ys[i - 1]),
            ),
          );
          for (let k = 1; k < n; k++)
            if (
              near(
                leg.xs[i - 1] + ((leg.xs[i] - leg.xs[i - 1]) * k) / n,
                leg.ys[i - 1] + ((leg.ys[i] - leg.ys[i - 1]) * k) / n,
              )
            ) {
              found = true;
              break;
            }
        }
        if (found) {
          ids.add(line.id);
          break;
        }
      }
    }
    this.accessRouteIds = ids;
    return ids;
  }
  drawAccessBadges(ids, occupied) {
    const done = [],
      route = this.routes[this.active],
      ctx = this.ctx;
    const all = this.access
      .filter((a) => a.floor === 2 && a.xs.length >= 2)
      .flatMap((line) =>
        (line.doors.length
          ? line.doors
          : [
              [
                (line.xs[0] + line.xs.at(-1)) / 2,
                (line.ys[0] + line.ys.at(-1)) / 2,
              ],
            ]
        ).map((p) => ({ line, p })),
      );
    const draw = (badge, force) => {
      const [x, y] = this.screen(...badge.p),
        r = force && route?.accessStop?.id === badge.line.id ? 10 : 8;
      if (
        x < -20 ||
        y < -20 ||
        x > this.vp.w + 20 ||
        y > this.vp.h + 20 ||
        done.some((p) => Math.hypot(p[0] - x, p[1] - y) < 56)
      )
        return false;
      if (
        !force &&
        occupied.some(
          (b) => b[0] < x + r && b[2] > x - r && b[1] < y + r && b[3] > y - r,
        )
      )
        return false;
      ctx.fillStyle = "#fff";
      ctx.shadowColor = "#00000046";
      ctx.shadowBlur = 2.5;
      ctx.shadowOffsetY = 1;
      ctx.beginPath();
      ctx.arc(x, y, r, 0, Math.PI * 2);
      ctx.fill();
      ctx.shadowBlur = ctx.shadowOffsetY = 0;
      this.icon(
        badge.line.level > this.level ? "ya_lock_orange" : "ya_lock",
        x,
        y,
        r * 0.68,
      );
      done.push([x, y]);
      return true;
    };
    for (const id of ids) {
      const candidates = all
        .filter((b) => b.line.id === id)
        .sort(
          (a, b) =>
            distanceToRoute(route, ...a.p) - distanceToRoute(route, ...b.p),
        );
      if (candidates[0]) draw(candidates[0], true);
    }
    if (this.showAccess && this.vp.scale * this.density >= 12) {
      let count = 0;
      for (const badge of all
        .filter((b) => !ids.has(b.line.id))
        .sort((a, b) => {
          const p = this.screen(...a.p),
            q = this.screen(...b.p);
          return (
            Math.hypot(p[0] - this.vp.w / 2, p[1] - this.vp.h / 2) -
            Math.hypot(q[0] - this.vp.w / 2, q[1] - this.vp.h / 2)
          );
        })) {
        if (count >= 6) break;
        if (draw(badge, false)) count++;
      }
    }
  }
  drawBalloon(route, best) {
    const delta = (route.time - best.time) / 60;
    if (Math.abs(delta) < 0.5) return;
    this.balloonAnchors ||= new WeakMap();
    let cached = this.balloonAnchors.get(route);
    if (cached?.best !== best) {
      let at = null,
        distance = -1;
      for (let s = 0; s < route.length; s += 4) {
        const p = pointOnRoute(route, s);
        if (p?.floor !== 2) continue;
        const d = distanceToRoute(best, p.x, p.y);
        if (d > distance) {
          distance = d;
          at = p;
        }
      }
      cached = { best, at };
      this.balloonAnchors.set(route, cached);
    }
    if (!cached.at) return;
    const ctx = this.ctx,
      [x, y] = this.screen(cached.at.x, cached.at.y),
      text = `${delta > 0 ? "+" : "−"}${Math.max(1, Math.round(Math.abs(delta)))} мин`;
    ctx.font = "500 15px Roboto,Arial,sans-serif";
    const w = ctx.measureText(text).width + 20;
    ctx.fillStyle = "#fff";
    ctx.shadowColor = "#0000003c";
    ctx.shadowBlur = 4;
    ctx.shadowOffsetY = 1;
    this.roundRect(x + 6, y - 34, w, 30, 15);
    ctx.fill();
    ctx.shadowBlur = ctx.shadowOffsetY = 0;
    ctx.fillStyle = "#e63c32";
    ctx.textAlign = "center";
    ctx.fillText(text, x + 6 + w / 2, y - 13);
  }
  drawConnectors(route) {
    const ctx = this.ctx;
    for (const step of route.connSteps) {
      const between =
        2 !== step.fromFloor &&
        2 !== step.toFloor &&
        2 >= Math.min(step.fromFloor, step.toFloor) &&
        2 <= Math.max(step.fromFloor, step.toFloor);
      if (step.fromFloor !== 2 && step.toFloor !== 2 && !between) continue;
      let p;
      if (between) {
        const c = this.meta.conns.find(
            (c) => c.name.replace(/\s*\(.*\)/, "") === step.name,
          ),
          xy = c?.pos["2"];
        if (!xy) continue;
        p = {
          floor: 2,
          x: xy[0] + this.meta.center[0],
          y: xy[1] + this.meta.center[1],
        };
      } else
        p = pointOnRoute(
          route,
          step.fromFloor === 2 ? step.s - 0.01 : step.s + 0.02,
        );
      if (p?.floor !== 2) continue;
      const [x, y] = this.screen(p.x, p.y);
      ctx.fillStyle = "#fff";
      ctx.beginPath();
      ctx.arc(x, y, 14, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = "#196eff";
      ctx.beginPath();
      ctx.arc(x, y, 12, 0, Math.PI * 2);
      ctx.fill();
      this.icon(
        ({ lift: "ya_glyph_lift", escalator: "ya_glyph_escalator" }[
          step.kind
        ] || "ya_stairs_glyph") + "_white",
        x,
        y,
        12 * 0.72,
      );
      const text =
        step.fromFloor === 2 || between
          ? `→ ${step.toFloor} эт.`
          : `с ${step.fromFloor} эт.`;
      ctx.font = "500 12px Roboto,Arial,sans-serif";
      ctx.textAlign = "center";
      const w = ctx.measureText(text).width;
      ctx.lineWidth = 3;
      ctx.strokeStyle = "#ffffffe6";
      ctx.strokeText(text, x + 14 + w / 2, y - 14);
      ctx.fillStyle = "#196eff";
      ctx.fillText(text, x + 14 + w / 2, y - 14);
    }
  }
  roundRect(x, y, w, h, r) {
    const c = this.ctx;
    c.beginPath();
    c.moveTo(x + r, y);
    c.arcTo(x + w, y, x + w, y + h, r);
    c.arcTo(x + w, y + h, x, y + h, r);
    c.arcTo(x, y + h, x, y, r);
    c.arcTo(x, y, x + w, y, r);
    c.closePath();
  }
  drawRoute(route, active) {
    const ctx = this.ctx,
      path = new Path2D();
    for (const l of route.legs) {
      if (l.floor !== 2) continue;
      l.xs.forEach((x, i) => {
        const p = this.screen(x, l.ys[i]);
        i ? path.lineTo(...p) : path.moveTo(...p);
      });
    }
    ctx.lineCap = "round";
    ctx.lineJoin = "round";
    ctx.lineWidth = (active ? 7 : 5.5) + 3.5;
    ctx.strokeStyle = "#fff";
    ctx.stroke(path);
    ctx.lineWidth = active ? 7 : 5.5;
    ctx.strokeStyle = active ? "#196eff" : "#8cafeb";
    ctx.stroke(path);
    if (active && this.vp.scale * this.density > 4) {
      ctx.lineWidth = 1.6;
      ctx.strokeStyle = "#ffffffc8";
      ctx.beginPath();
      for (let s = 6; s < route.length; s += Math.max(5, 60 / this.vp.scale)) {
        const p = pointOnRoute(route, s),
          q = pointOnRoute(route, Math.min(route.length, s + 0.1));
        if (p?.floor !== 2 || q?.floor !== 2) continue;
        const [x, y] = this.screen(p.x, p.y),
          [xx, yy] = this.screen(q.x, q.y),
          a = Math.atan2(yy - y, xx - x);
        ctx.moveTo(x - 3.2 * Math.cos(a - 0.6), y - 3.2 * Math.sin(a - 0.6));
        ctx.lineTo(x, y);
        ctx.lineTo(x - 3.2 * Math.cos(a + 0.6), y - 3.2 * Math.sin(a + 0.6));
      }
      ctx.stroke();
    }
  }
  fit(bounds, free, rot = this.northRot || 0) {
    const v = this.vp,
      c = Math.abs(Math.cos(rot)),
      s = Math.abs(Math.sin(rot)),
      bw = bounds[2] - bounds[0],
      bh = bounds[3] - bounds[1];
    const scale = Math.max(
      0.4 / this.density,
      Math.min(
        40 / this.density,
        Math.min(
          free.width / (c * bw + s * bh),
          free.height / (s * bw + c * bh),
        ) * 0.92,
      ),
    );
    const x = (bounds[0] + bounds[2]) / 2 - this.meta.center[0],
      y = (bounds[1] + bounds[3]) / 2 - this.meta.center[1];
    this.focus(
      x + this.meta.center[0],
      y + this.meta.center[1],
      free,
      scale,
      rot,
    );
  }
  focus(x, y, free, scale = this.vp.scale, rot = this.vp.rot) {
    const ux = free.x + free.width / 2 - this.vp.w / 2,
      uy = free.y + free.height / 2 - this.vp.h / 2,
      c = Math.cos(rot),
      s = Math.sin(rot);
    this.animate({
      cx: x - this.meta.center[0] - (ux * c + uy * s) / scale,
      cy: y - this.meta.center[1] + (-ux * s + uy * c) / scale,
      scale,
      rot,
    });
  }
  animate(goal, duration = 380) {
    this.stopMotion();
    const start = {
        cx: this.vp.cx,
        cy: this.vp.cy,
        scale: this.vp.scale,
        rot: this.vp.rot,
      },
      began = performance.now();
    const frame = (now) => {
      if (this.stopped) return;
      const t = Math.min(1, (now - began) / duration),
        k = 1 - Math.pow(1 - t, 3);
      for (const key of Object.keys(start))
        this.vp[key] = start[key] + (goal[key] - start[key]) * k;
      this.draw();
      if (t < 1) this.animation = requestAnimationFrame(frame);
    };
    this.animation = requestAnimationFrame(frame);
  }
  zoomBy(k) {
    this.animate(
      {
        ...this.vp,
        scale: Math.max(
          0.4 / this.density,
          Math.min(200 / this.density, this.vp.scale * k),
        ),
      },
      260,
    );
  }
  stopMotion() {
    cancelAnimationFrame(this.animation);
    cancelAnimationFrame(this.fling);
  }
  routeHit(x, y) {
    let found = null,
      best = 18;
    this.routes.forEach((r, i) => {
      if (i === this.active || this.guiding) return;
      for (const l of r.legs) {
        if (l.floor !== 2) continue;
        for (let k = 1; k < l.xs.length; k++) {
          const [a, b] = this.screen(l.xs[k - 1], l.ys[k - 1]),
            [c, d] = this.screen(l.xs[k], l.ys[k]),
            vx = c - a,
            vy = d - b,
            l2 = vx * vx + vy * vy,
            t = l2
              ? Math.max(0, Math.min(1, ((x - a) * vx + (y - b) * vy) / l2))
              : 0,
            dist = Math.hypot(x - a - vx * t, y - b - vy * t);
          if (dist < best) {
            best = dist;
            found = i;
          }
        }
      }
    });
    return found;
  }
  gestures({ signal, onTap, onClosure, onRoute }) {
    const canvas = this.canvas,
      on = (type, fn, opts = {}) =>
        canvas.addEventListener(type, fn, { ...opts, signal }),
      pointers = new Map();
    let start,
      last,
      moved = false,
      multi = false,
      longFired = false,
      longTimer,
      rotAcc = 0,
      rotOn = false,
      velocity = [];
    const local = (e) => {
      const r = canvas.getBoundingClientRect();
      return {
        x: e.clientX - r.left,
        y: e.clientY - r.top,
        t: performance.now(),
      };
    };
    const two = () => {
      const [a, b] = [...pointers.values()];
      return {
        x: (a.x + b.x) / 2,
        y: (a.y + b.y) / 2,
        d: Math.hypot(b.x - a.x, b.y - a.y),
        a: Math.atan2(b.y - a.y, b.x - a.x),
      };
    };
    const tap = (p, long) => {
      if (!long) {
        const hit = this.hitClosures.find(
          (h) => Math.hypot(h.x - p.x, h.y - p.y) < 26,
        );
        if (hit) {
          onClosure?.(hit.closure);
          return;
        }
        const ri = this.routeHit(p.x, p.y);
        if (ri !== null) {
          onRoute?.(ri);
          return;
        }
      }
      onTap?.(...this.world(p.x, p.y), long);
    };
    on("pointerdown", (e) => {
      if (e.button && e.pointerType === "mouse") return;
      canvas.setPointerCapture(e.pointerId);
      this.stopMotion();
      const p = local(e);
      pointers.set(e.pointerId, p);
      if (pointers.size === 1) {
        start = last = p;
        moved = multi = longFired = false;
        velocity = [p];
        longTimer = setTimeout(() => {
          if (!moved && !multi) {
            longFired = true;
            tap(start, true);
          }
        }, 550);
      }
      if (pointers.size === 2) {
        multi = true;
        clearTimeout(longTimer);
        last = two();
        rotAcc = 0;
        rotOn = false;
      }
    });
    on("pointermove", (e) => {
      if (!pointers.has(e.pointerId)) return;
      const p = local(e);
      pointers.set(e.pointerId, p);
      if (pointers.size >= 2) {
        const n = two();
        let da = n.a - last.a;
        if (da > Math.PI) da -= 2 * Math.PI;
        if (da < -Math.PI) da += 2 * Math.PI;
        rotAcc += da;
        if (!rotOn && Math.abs(rotAcc) > 0.2) rotOn = true;
        this.vp.twoFinger(
          last.x,
          last.y,
          n.x,
          n.y,
          last.d ? n.d / last.d : 1,
          rotOn ? da : 0,
        );
        last = n;
        this.draw();
      } else if (!multi) {
        if (!moved && Math.hypot(p.x - start.x, p.y - start.y) > 8) {
          moved = true;
          clearTimeout(longTimer);
        }
        if (moved) {
          this.vp.pan(p.x - last.x, p.y - last.y);
          this.draw();
        }
        last = p;
        velocity.push(p);
        velocity = velocity.filter((n) => p.t - n.t < 100);
      }
    });
    const end = (e) => {
      if (!pointers.has(e.pointerId)) return;
      clearTimeout(longTimer);
      const p = local(e);
      pointers.delete(e.pointerId);
      if (e.type === "pointercancel") {
        multi = true;
        return;
      }
      if (pointers.size) return;
      if (!moved && !multi && !longFired && p.t - start.t < 400) tap(p, false);
      else if (moved && !multi && velocity.length > 1) {
        const first = velocity[0],
          tail = velocity.at(-1),
          dt = (tail.t - first.t) / 1000;
        let vx = dt ? (tail.x - first.x) / dt : 0,
          vy = dt ? (tail.y - first.y) / dt : 0;
        if (Math.hypot(vx, vy) > 200) {
          let prev = performance.now();
          const frame = (now) => {
            const dt = Math.min(0.05, (now - prev) / 1000);
            prev = now;
            const k = Math.exp(-4.5 * dt),
              f = (1 - k) / 4.5;
            this.vp.pan(vx * f, vy * f);
            vx *= k;
            vy *= k;
            this.draw();
            if (Math.hypot(vx, vy) > 25 && !this.stopped)
              this.fling = requestAnimationFrame(frame);
          };
          this.fling = requestAnimationFrame(frame);
        }
      }
    };
    on("pointerup", end);
    on("pointercancel", end);
    on("lostpointercapture", () => clearTimeout(longTimer));
    on("contextmenu", (e) => e.preventDefault());
    on(
      "wheel",
      (e) => {
        e.preventDefault();
        this.stopMotion();
        const p = local(e);
        this.vp.twoFinger(
          p.x,
          p.y,
          p.x,
          p.y,
          Math.exp(-e.deltaY * 0.0015),
          e.shiftKey ? e.deltaY * 0.002 : 0,
        );
        this.draw();
      },
      { passive: false },
    );
    on("dblclick", (e) => {
      const p = local(e);
      this.vp.twoFinger(p.x, p.y, p.x, p.y, 1.6, 0);
      this.draw();
    });
    on("keydown", (e) => {
      const key = e.key;
      if (
        [
          "ArrowLeft",
          "ArrowRight",
          "ArrowUp",
          "ArrowDown",
          "+",
          "-",
          "=",
          "Home",
        ].includes(key)
      ) {
        e.preventDefault();
        this.stopMotion();
        if (key.startsWith("Arrow"))
          this.vp.pan(
            { ArrowLeft: 40, ArrowRight: -40 }[key] || 0,
            { ArrowUp: 40, ArrowDown: -40 }[key] || 0,
          );
        else if (key === "+" || key === "=") this.zoomBy(1.6);
        else if (key === "-") this.zoomBy(1 / 1.6);
        else this.onFit?.();
        this.draw();
      }
    });
    signal.addEventListener(
      "abort",
      () => {
        clearTimeout(longTimer);
        pointers.clear();
      },
      { once: true },
    );
  }
  dispose() {
    this.stopped = true;
    this.stopMotion();
    this.observer.disconnect();
    this.gl?.getExtension("WEBGL_lose_context")?.loseContext();
    this.images.clear();
  }
}
function segmentDistance(x, y, ax, ay, bx, by) {
  const dx = bx - ax,
    dy = by - ay,
    l2 = dx * dx + dy * dy,
    t = l2 ? Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / l2)) : 0;
  return Math.hypot(x - ax - dx * t, y - ay - dy * t);
}
function distanceToRoute(route, x, y) {
  let best = Infinity;
  for (const leg of route?.legs || []) {
    if (leg.floor !== 2) continue;
    for (let i = 1; i < leg.xs.length; i++)
      best = Math.min(
        best,
        segmentDistance(
          x,
          y,
          leg.xs[i - 1],
          leg.ys[i - 1],
          leg.xs[i],
          leg.ys[i],
        ),
      );
  }
  return best;
}
export function pointOnRoute(route, s) {
  for (const l of route.legs) {
    if (s > l.ss.at(-1) && l !== route.legs.at(-1)) continue;
    for (let i = 1; i < l.ss.length; i++) {
      if (s > l.ss[i] && i < l.ss.length - 1) continue;
      const t = Math.max(
        0,
        Math.min(1, (s - l.ss[i - 1]) / (l.ss[i] - l.ss[i - 1] || 1)),
      );
      return {
        floor: l.floor,
        x: l.xs[i - 1] + (l.xs[i] - l.xs[i - 1]) * t,
        y: l.ys[i - 1] + (l.ys[i] - l.ys[i - 1]) * t,
      };
    }
    return { floor: l.floor, x: l.xs[0], y: l.ys[0] };
  }
  return null;
}
