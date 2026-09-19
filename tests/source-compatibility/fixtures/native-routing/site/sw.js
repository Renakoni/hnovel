self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', event => event.waitUntil(self.clients.claim()));
self.addEventListener('fetch', event => {
    if (new URL(event.request.url).pathname === '/sw-fetch') event.respondWith(fetch('/sw-network'));
});
self.addEventListener('message', event => {
    if (event.data !== 'pulse') return;
    event.waitUntil((async function () {
        for (let index = 0; index < 150; index++) {
            await fetch('/pulse?index=' + index);
            if (index === 0) event.source.postMessage('started');
            await new Promise(resolve => setTimeout(resolve, 200));
        }
    }()));
});
