const CACHE = 'sbs-shell-v9-native-core-__BUILD__';
const ARTWORK = ['news-avatar.png', 'splash-sbs-mss.png', 'sirius_logo.png', 'sirius_icon.jpg', 'sirius_theme.webp', 'icon-192.png', 'icon-512.png', 'apple-touch-icon.png', 'back.svg', 'exit.svg', 'calendar.svg', 'pin.svg', 'search.svg', 'auth-pin.svg', 'auth-welcome.svg', 'onboarding-1.svg', 'onboarding-2.svg', 'onboarding-3.svg', 'schedule.svg', 'people.svg', 'inbox.svg', 'meetings.svg', 'news.svg', 'partners.svg', 'venue.svg', 'profile.svg', 'favorites.svg'];
const SPEAKER_PHOTOS = __SPEAKER_PHOTOS__;
const SHELL = ['/app/', '/app/index.html', '/app/app.js', '/app/dialog-dismiss.js', '/app/native-scenes.js', '/app/chat-cache.js', '/app/styles.css', '/app/session-scenes.css', '/app/session-scenes.js', '/app/avatar-crop.js', '/app/avatar-crop.css', '/app/photo-upload.js', '/app/venue.js', '/app/session-rating.js', '/app/rating-star.js', '/app/venue-map.js', '/app/venue-engine.js', '/app/venue-worker.js', '/app/venue-native.css', '/app/navigation-session.js', '/app/shared.json', '/app/manifest.webmanifest', '/app/assets/icon-192.png?v=2', '/app/assets/icon-512.png?v=2', '/app/assets/apple-touch-icon.png?v=2', ...SPEAKER_PHOTOS, '/app/assets/menu-logo-2027.png', '/app/assets/roboto.ttf', ...['back','search','add','settings','clock','calendar','pin','time','complete','close','send'].map(name=>'/app/assets/native-'+name+'.svg'), ...ARTWORK.map(name => '/app/assets/' + name)];
self.addEventListener('install', event => {
  event.waitUntil((async () => {
    const cache = await caches.open(CACHE);
    let pack = [];
    try { const response = await fetch('/app/navigator/precache.json', {cache: 'reload'}); if (response.ok) pack = await response.json(); } catch {}
    if (typeof DecompressionStream === 'undefined') pack = pack.map(url => url.endsWith('.u16.gz') ? url.slice(0, -3) : url);
    const queue = [...SHELL, '/app/navigator/precache.json', ...pack];
    await Promise.all(Array.from({length: 6}, async () => {
      while (queue.length) {
        const url = queue.shift();
        try { const response = await fetch(url, {cache: 'reload'}); if (response.ok) await cache.put(url, response); }
        catch { /* Network failure can be retried on the next navigation. */ }
      }
    }));
    await self.skipWaiting();
  })());
});
self.addEventListener('activate', event => {
  event.waitUntil((async () => {
    for (const name of await caches.keys()) if (name.startsWith('sbs-shell-') && name !== CACHE) await caches.delete(name);
    await self.clients.claim();
  })());
});
const PORTRAITS = 'sbs-portraits-v1', PORTRAIT_LIMIT = 64 * 1024 * 1024;
let portraitWrites = Promise.resolve();
async function rememberPortrait(request, response) {
  const cache=await caches.open(PORTRAITS);await cache.put(request,response);
  const keys=await cache.keys();let total=0;const sizes=[];
  for(const key of keys){const item=await cache.match(key),size=Number(item.headers.get('content-length')) || (await item.clone().blob()).size;sizes.push(size);total+=size;}
  for(let i=0;total>PORTRAIT_LIMIT && i<keys.length;i++){await cache.delete(keys[i]);total-=sizes[i];}
}
self.addEventListener('fetch', event => {
  const url = new URL(event.request.url);
  // Avatar names are random immutable identifiers; changed photos receive a new URL.
  if(event.request.method==='GET' && url.origin===self.location.origin && /^\/media\/avatars\/[0-9a-f]{64}\.(?:png|webp)$/.test(url.pathname)){
    event.respondWith((async()=>{const cache=await caches.open(PORTRAITS),saved=await cache.match(event.request);if(saved)return saved;const response=await fetch(event.request);if(response.ok){const copy=response.clone();portraitWrites=portraitWrites.then(()=>rememberPortrait(event.request,copy)).catch(()=>{});event.waitUntil(portraitWrites);}return response;})());return;
  }
  // Authenticated API data never enters the public asset cache.
  if (event.request.method !== 'GET' || url.origin !== self.location.origin || !(url.pathname.startsWith('/app/') || SPEAKER_PHOTOS.includes(url.pathname))) return;
  const publicAsset = SPEAKER_PHOTOS.includes(url.pathname) || url.pathname.startsWith('/app/assets/') || url.pathname.startsWith('/app/navigator/');
  const shell = SHELL.includes(url.pathname);
  if (!publicAsset && !shell && event.request.mode !== 'navigate') return;
  event.respondWith((async () => {
    const cache = await caches.open(CACHE);
    if (publicAsset) {
      const saved = await cache.match(event.request);
      if (saved) {
        const range = event.request.headers.get('range');
        if (range && url.pathname.endsWith('.mp3')) {
          const bytes = await saved.arrayBuffer(), match = /^bytes=(\d+)-(\d*)$/.exec(range);
          if (match) {
            const start = Number(match[1]), end = Math.min(match[2] ? Number(match[2]) : bytes.byteLength - 1, bytes.byteLength - 1);
            if (start > end) return new Response(null, {status: 416, headers: {'Content-Range': `bytes */${bytes.byteLength}`}});
            return new Response(bytes.slice(start, end + 1), {status: 206, headers: {'Content-Type': 'audio/mpeg', 'Accept-Ranges': 'bytes', 'Content-Range': `bytes ${start}-${end}/${bytes.byteLength}`, 'Content-Length': String(end - start + 1)}});
          }
        }
        return saved;
      }
    }
    try {
      const response = await fetch(event.request);
      if (response.ok && response.status !== 206 && (publicAsset || shell)) await cache.put(event.request, response.clone());
      return response;
    } catch (error) {
      const saved = await cache.match(event.request);
      if (saved) return saved;
      if (event.request.mode === 'navigate') { const index = await cache.match('/app/index.html'); if (index) return index; }
      throw error;
    }
  })());
});
