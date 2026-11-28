'use strict';
// Service worker mínimo: guarda la "cáscara" de la app para que abra rápido y sin conexión.
// Las consultas a Google, al clima y demás NO se guardan: siempre van a internet.
const VERSION = 'asistente-v49';
const ARCHIVOS = ['./', './index.html', './nativo.js?v=49', './nucleo.js?v=49', './detector.js?v=49', './manifest.json', './icon-192.png', './icon-512.png'];

self.addEventListener('install', e => { e.waitUntil(caches.open(VERSION).then(c => c.addAll(ARCHIVOS)).then(() => self.skipWaiting())); });
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== VERSION).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== self.location.origin) return;   // lo de afuera, directo a internet
  // Primero internet (para que las actualizaciones lleguen); si no hay conexión, lo guardado.
  e.respondWith(fetch(e.request).then(r => { const copia = r.clone(); caches.open(VERSION).then(c => c.put(e.request, copia)); return r; })
    .catch(() => caches.match(e.request).then(r => r || caches.match('./index.html'))));
});
