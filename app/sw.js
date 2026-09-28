// 오프라인용 서비스 워커. 앱 파일은 설치 때 저장하고, 글꼴은 처음 받을 때 저장한다.
const VER = 'nagyeong-v2';
const SHELL = ['./', './index.html', './layers.json', './manifest.webmanifest', './icon.svg', './icon-512.png', './vendor/qrcode.min.js', './vendor/jsQR.min.js'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(VER).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== VER).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', e => {
  const u = new URL(e.request.url);
  const own = u.origin === location.origin;
  const font = /fonts\.(googleapis|gstatic)\.com$/.test(u.hostname);
  if (e.request.method !== 'GET' || !(own || font)) return;
  // 저장본을 먼저 주고, 뒤에서 새 것을 받아 갈아 둔다.
  e.respondWith(caches.open(VER).then(async c => {
    const hit = await c.match(e.request, { ignoreSearch: own });
    const net = fetch(e.request).then(r => { if (r && (r.ok || r.type === 'opaque')) c.put(e.request, r.clone()); return r; }).catch(() => null);
    return hit || (await net) || Response.error();
  }));
});
