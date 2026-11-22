'use strict';
/**
 * DETECTOR DE "MORSIS" SIN PITIDOS (beta).
 *
 * El dictado de Google pita cada vez que Chrome abre el micrófono, y para escuchar siempre hay que abrirlo cada pocos
 * segundos. Este detector escucha DENTRO del celular, sin internet y sin pitar, con un reconocedor de voz offline
 * (Vosk, modelo chico en español). Solo cuando oye algo parecido a "Morsis" avisa; ahí recién se abre el dictado de
 * Google (un pitido) para entender el pedido con precisión.
 *
 * El modelo (unos 40 MB) se baja UNA sola vez y queda guardado en el celular. Se baja en .zip (así lo publica Vosk) y
 * acá se reempaqueta como .tar.gz, que es lo que pide la librería.
 */
(function (raiz) {
  const LIB = 'https://cdn.jsdelivr.net/npm/vosk-browser@0.0.8/dist/vosk.js';
  const FFLATE = 'https://cdn.jsdelivr.net/npm/fflate@0.8.2/umd/index.js';
  const MODELO_ZIP = 'https://huggingface.co/localstack/vosk-models/resolve/main/vosk-model-small-es-0.42.zip';
  const CACHE = 'morsis-modelo-v1';
  const CLAVE_CACHE = './modelo-es.tar.gz';

  const cargarScript = src => new Promise((ok, mal) => {
    if (document.querySelector(`script[src="${src}"]`)) return ok();
    const s = document.createElement('script'); s.src = src; s.onload = ok; s.onerror = () => mal(new Error('No se pudo cargar ' + src));
    document.head.appendChild(s);
  });

  /** Arma un .tar (formato ustar) con los archivos { nombre: Uint8Array }. */
  function armarTar(archivos) {
    const partes = [];
    const enc = new TextEncoder();
    const octal = (n, largo) => n.toString(8).padStart(largo - 1, '0') + '\0';
    for (const [nombre, datos] of Object.entries(archivos)) {
      const h = new Uint8Array(512);
      const poner = (txt, pos, largo) => { const b = enc.encode(txt); h.set(b.subarray(0, largo), pos); };
      let n = nombre, prefijo = '';
      if (n.length > 99) { const i = n.lastIndexOf('/', 154); prefijo = n.slice(0, i); n = n.slice(i + 1); }
      poner(n, 0, 100); poner(octal(0o644, 8), 100, 8); poner(octal(0, 8), 108, 8); poner(octal(0, 8), 116, 8);
      poner(octal(datos.length, 12), 124, 12); poner(octal(Math.floor(Date.now() / 1000), 12), 136, 12);
      poner('        ', 148, 8); h[156] = '0'.charCodeAt(0); poner('ustar\0', 257, 6); poner('00', 263, 2); poner(prefijo, 345, 155);
      let suma = 0; for (let i = 0; i < 512; i++) suma += h[i];
      poner(octal(suma, 7) + ' ', 148, 8);
      partes.push(h, datos);
      const resto = (512 - (datos.length % 512)) % 512;
      if (resto) partes.push(new Uint8Array(resto));
    }
    partes.push(new Uint8Array(1024));
    return new Blob(partes);
  }

  /** zip de Vosk → tar.gz con la carpeta "model/" (lo que espera vosk-browser). */
  async function prepararModelo(avisar) {
    const cache = await caches.open(CACHE);
    const guardado = await cache.match(CLAVE_CACHE);
    if (guardado) return URL.createObjectURL(await guardado.blob());
    avisar('⬇️ Bajando el detector de voz (40 MB, una sola vez)…');
    const r = await fetch(MODELO_ZIP);
    if (!r.ok) throw new Error('No se pudo bajar el modelo (' + r.status + ')');
    const total = Number(r.headers.get('content-length')) || 40e6;
    const lector = r.body.getReader(); const trozos = []; let bajado = 0;
    for (;;) {
      const { done, value } = await lector.read(); if (done) break;
      trozos.push(value); bajado += value.length;
      avisar(`⬇️ Bajando el detector de voz… ${Math.min(99, Math.round(bajado * 100 / total))}%`);
    }
    const zip = new Uint8Array(bajado); let pos = 0; for (const t of trozos) { zip.set(t, pos); pos += t.length; }
    avisar('📦 Preparando el detector…');
    await cargarScript(FFLATE);
    const archivos = raiz.fflate.unzipSync(zip);
    const salida = {};
    for (const [ruta, datos] of Object.entries(archivos)) {
      if (ruta.endsWith('/')) continue;
      const sinCarpeta = ruta.replace(/^[^/]+\//, '');   // "vosk-model-small-es-0.42/am/final.mdl" → "am/final.mdl"
      salida['model/' + sinCarpeta] = datos;
    }
    const tar = armarTar(salida);
    const gz = await new Response(tar.stream().pipeThrough(new CompressionStream('gzip'))).blob();
    await cache.put(CLAVE_CACHE, new Response(gz, { headers: { 'Content-Type': 'application/gzip' } }));
    return URL.createObjectURL(gz);
  }

  /**
   * crearDetector({ alOir(texto), avisar(texto) }) → { iniciar(), pausar(), reanudar(), activo }
   * alOir recibe cada frase (parcial o final) que reconoce; quien llama decide si es "Morsis".
   */
  function crearDetector({ alOir, avisar }) {
    let modelo = null, reconocedor = null, ctx = null, stream = null, nodo = null, fuente = null, pausado = false;
    async function abrirMicrofono() {
      stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, channelCount: 1 } });
      ctx = new (window.AudioContext || window.webkitAudioContext)();
      fuente = ctx.createMediaStreamSource(stream);
      nodo = ctx.createScriptProcessor(4096, 1, 1);
      nodo.onaudioprocess = e => { if (!pausado && reconocedor) { try { reconocedor.acceptWaveform(e.inputBuffer); } catch (_) {} } };
      fuente.connect(nodo); nodo.connect(ctx.destination);
    }
    function cerrarMicrofono() {
      try { nodo && nodo.disconnect(); fuente && fuente.disconnect(); } catch (_) {}
      if (stream) stream.getTracks().forEach(t => t.stop());
      if (ctx) ctx.close().catch(() => {});
      stream = ctx = nodo = fuente = null;
    }
    async function iniciar() {
      await cargarScript(LIB);
      const url = await prepararModelo(avisar);
      avisar('🧠 Cargando el detector…');
      // Si el modelo no carga en 3 minutos (o da error), se avisa en vez de quedarse "cargando" para siempre.
      modelo = await Promise.race([
        raiz.Vosk.createModel(url),
        new Promise((_, mal) => setTimeout(() => mal(new Error('el detector tardó demasiado en cargar')), 180000))
      ]);
      await abrirMicrofono();
      reconocedor = new modelo.KaldiRecognizer(ctx.sampleRate);
      let ultimo = '';
      reconocedor.on('partialresult', m => { const t = (m.result && m.result.partial) || ''; if (t && t !== ultimo) { ultimo = t; alOir(t, false); } });
      reconocedor.on('result', m => { const t = (m.result && m.result.text) || ''; ultimo = ''; if (t) alOir(t, true); });
      pausado = false;
    }
    return {
      iniciar,
      // Mientras habla Google (dictado) o Morsis responde, se suelta el micrófono para no chocar con el dictado.
      pausar() { pausado = true; cerrarMicrofono(); },
      async reanudar() { if (!modelo) return; if (!stream) await abrirMicrofono(); pausado = false; },
      get activo() { return !!modelo && !pausado; },
      detener() { pausado = true; cerrarMicrofono(); try { modelo && modelo.terminate(); } catch (_) {} modelo = reconocedor = null; }
    };
  }

  raiz.DetectorMorsis = { crearDetector, armarTar };
})(typeof window !== 'undefined' ? window : globalThis);
