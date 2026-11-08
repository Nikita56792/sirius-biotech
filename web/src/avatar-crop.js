import {makeDismissible} from './dialog-dismiss.js';
import {decodePhoto} from './photo-upload.js';
// Entirely local: the caller decides whether to upload the confirmed square JPEG.
let stylesheetReady;
function loadStylesheet() {
  if (!stylesheetReady) stylesheetReady = new Promise((resolve, reject) => {
    const link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = new URL('./avatar-crop.css', import.meta.url).href;
    link.onload = resolve;
    link.onerror = () => { link.remove(); stylesheetReady = null; reject(new Error('Crop styles unavailable')); };
    document.head.append(link);
  });
  return stylesheetReady;
}

/** @returns {Promise<Blob|null>} A 512 x 512 JPEG, or null when cancelled. */
export async function cropAvatar(file, { language = 'ru' } = {}) {
  if (!(file instanceof Blob) || !['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) {
    throw new TypeError('JPEG or PNG required');
  }
  if (file.size > 20 * 1024 * 1024) throw new Error('Photo exceeds 20 MiB');
  await loadStylesheet();
  // createImageBitmap applies EXIF orientation and keeps the original aspect ratio.
  const image = await decodePhoto(file);
  if (!image.width || !image.height || image.width * image.height > 64000000) {
    image.close?.(); throw new Error('Photo dimensions are too large');
  }
  const ru = language.toLowerCase().startsWith('ru');
  const words = ru ? {
    title: 'Обрезать фото', help: 'Переместите фото и измените масштаб для аватара.',
    cancel: 'Отмена', use: 'Использовать фото', zoom: 'Масштаб', error: 'Не удалось обработать фото. Попробуйте ещё раз.'
  } : {
    title: 'Crop photo', help: 'Drag to position and zoom. The circle previews your avatar.',
    cancel: 'Cancel', use: 'Use photo', zoom: 'Zoom', error: 'Could not process photo. Please try again.'
  };
  return new Promise((resolve, reject) => {
    const previousFocus = document.activeElement;
    const dialog = document.createElement('dialog');
    dialog.className = 'avatar-crop';
    dialog.setAttribute('aria-label', words.title);
    const panel = document.createElement('section'); panel.className = 'avatar-crop__panel';
    const title = document.createElement('h2'); title.textContent = words.title;
    const help = document.createElement('p'); help.textContent = words.help;
    const canvas = document.createElement('canvas');
    canvas.className = 'avatar-crop__canvas'; canvas.width = canvas.height = 640;
    canvas.tabIndex = 0; canvas.setAttribute('aria-label', words.help);
    const label = document.createElement('label'); label.className = 'avatar-crop__zoom';
    const labelText = document.createElement('span'); labelText.textContent = words.zoom;
    const range = document.createElement('input');
    range.type = 'range'; range.min = '1'; range.max = '5'; range.step = '.01'; range.value = '1';
    label.append(labelText, range);
    const actions = document.createElement('div'); actions.className = 'avatar-crop__actions';
    const cancel = document.createElement('button'); cancel.type = 'button'; cancel.textContent = words.cancel;
    const use = document.createElement('button'); use.type = 'button'; use.textContent = words.use;
    use.className = 'avatar-crop__confirm';
    const error = document.createElement('p'); error.className = 'avatar-crop__error'; error.setAttribute('role', 'alert');
    actions.append(cancel, use); panel.append(title, help, canvas, label, error, actions); dialog.append(panel);
    const ctx = canvas.getContext('2d');
    const size = 640, side = 512, edge = (size - side) / 2;
    const base = side / Math.min(image.width, image.height);
    let zoom = 1, x = (size - image.width * base) / 2, y = (size - image.height * base) / 2;
    let finished = false, exporting = false;
    const pointers = new Map();
    function draw() {
      x = Math.min(edge, Math.max(edge + side - image.width * base * zoom, x));
      y = Math.min(edge, Math.max(edge + side - image.height * base * zoom, y));
      ctx.clearRect(0, 0, size, size);
      ctx.fillStyle = '#17191e'; ctx.fillRect(0, 0, size, size);
      ctx.drawImage(image, x, y, image.width * base * zoom, image.height * base * zoom);
      ctx.beginPath(); ctx.rect(0, 0, size, size); ctx.arc(size / 2, size / 2, side / 2, 0, Math.PI * 2);
      ctx.fillStyle = 'rgba(0,0,0,.64)'; ctx.fill('evenodd');
      ctx.strokeStyle = '#fff'; ctx.lineWidth = 2;
      ctx.strokeRect(edge, edge, side, side);
      ctx.beginPath(); ctx.arc(size / 2, size / 2, side / 2, 0, Math.PI * 2); ctx.stroke();
    }
    function zoomAt(value, px = size / 2, py = size / 2) {
      const next = Math.max(1, Math.min(5, value)), ratio = next / zoom;
      x = px - (px - x) * ratio; y = py - (py - y) * ratio;
      zoom = next; range.value = String(zoom); draw();
    }
    function point(event) {
      const rect = canvas.getBoundingClientRect();
      return { x: (event.clientX - rect.left) * size / rect.width, y: (event.clientY - rect.top) * size / rect.height };
    }
    function pairState() {
      const [a, b] = [...pointers.values()];
      return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2, distance: Math.hypot(a.x - b.x, a.y - b.y) };
    }
    canvas.addEventListener('pointerdown', event => {
      if (event.pointerType === 'mouse' && event.button !== 0) return;
      canvas.setPointerCapture(event.pointerId); pointers.set(event.pointerId, point(event)); event.preventDefault();
    });
    canvas.addEventListener('pointermove', event => {
      if (!pointers.has(event.pointerId)) return;
      const before = pointers.get(event.pointerId), next = point(event);
      const oldPair = pointers.size === 2 ? pairState() : null;
      pointers.set(event.pointerId, next);
      if (oldPair) {
        const current = pairState();
        zoomAt(zoom * (oldPair.distance > 0 ? current.distance / oldPair.distance : 1), oldPair.x, oldPair.y);
        x += current.x - oldPair.x; y += current.y - oldPair.y;
      } else if (pointers.size === 1) { x += next.x - before.x; y += next.y - before.y; }
      draw(); event.preventDefault();
    });
    for (const event of ['pointerup', 'pointercancel', 'lostpointercapture']) {
      canvas.addEventListener(event, e => pointers.delete(e.pointerId));
    }
    canvas.addEventListener('wheel', event => {
      event.preventDefault(); const p = point(event); zoomAt(zoom * Math.exp(-event.deltaY * .002), p.x, p.y);
    }, { passive: false });
    canvas.addEventListener('keydown', event => {
      const step = event.shiftKey ? 24 : 8;
      if (event.key === 'ArrowLeft') x -= step;
      else if (event.key === 'ArrowRight') x += step;
      else if (event.key === 'ArrowUp') y -= step;
      else if (event.key === 'ArrowDown') y += step;
      else return;
      event.preventDefault(); draw();
    });
    range.addEventListener('input', () => zoomAt(Number(range.value)));
    function finish(result) {
      if (finished) return;
      finished = true; image.close?.(); dialog.close(); dialog.remove();
      if (previousFocus?.isConnected) previousFocus.focus();
      resolve(result);
    }
    cancel.addEventListener('click', () => finish(null));
    dialog.addEventListener('cancel', event => { event.preventDefault(); finish(null); });
    dialog.addEventListener('close', () => finish(null));
    use.addEventListener('click', () => {
      if (exporting || finished) return;
      exporting = true; use.disabled = true; error.textContent = '';
      try {
        const output = document.createElement('canvas'); output.width = output.height = 512;
        const out = output.getContext('2d');
        out.fillStyle = '#fff'; out.fillRect(0, 0, 512, 512);
        out.drawImage(image, x - edge, y - edge, image.width * base * zoom, image.height * base * zoom);
        output.toBlob(blob => {
          if (finished) return;
          if (blob) finish(blob);
          else { exporting = false; use.disabled = false; error.textContent = words.error; }
        }, 'image/jpeg', .92);
      } catch {
        exporting = false; use.disabled = false; error.textContent = words.error;
      }
    });
    try { makeDismissible(dialog);document.body.append(dialog); dialog.showModal(); draw(); cancel.focus(); }
    catch (err) { image.close?.(); dialog.remove(); reject(err); }
  });
}
