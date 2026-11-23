/**
 * Morsis dentro de la APP de Android (window.MorsisNativo lo pone la app; en Chrome no existe y esto no hace nada).
 * Dentro de una app no hay dictado ni voz del navegador: se reemplazan por los del celu con la misma forma
 * (SpeechRecognition y speechSynthesis), así index.html funciona igual en los dos lados.
 * La app avisa cosas con window.__morsisNativo(evento): resultados del dictado, fin de la voz, "despertar"
 * (oyó «Morsis» aunque la pantalla estuviera apagada), lo que va oyendo y el estado del detector.
 */
(function () {
  'use strict';
  const N = window.MorsisNativo;
  if (!N) return;
  window.ES_APP = true;
  const recs = {}, voces = {};
  let cont = 0;

  window.__morsisNativo = function (ev) {
    if (!ev) return;
    if (ev.tipo === 'rec') {
      const r = recs[ev.id]; if (!r) return;
      if (ev.ev === 'start') { if (r.onstart) r.onstart(); }
      else if (ev.ev === 'result') {
        const alt = [{ transcript: ev.texto || '' }]; alt.isFinal = !!ev.final;
        if (r.onresult) r.onresult({ resultIndex: 0, results: [alt] });
      }
      else if (ev.ev === 'error') { if (r.onerror) r.onerror({ error: ev.error || 'aborted' }); }
      else if (ev.ev === 'end') { delete recs[ev.id]; if (r.onend) r.onend(); }
      return;
    }
    if (ev.tipo === 'tts') {
      const u = voces[ev.id]; delete voces[ev.id];
      if (u && u.onend) u.onend({});
      return;
    }
    if (typeof window.__morsisApp === 'function') window.__morsisApp(ev);   // despertar, oido, estado → index.html
  };

  class Dictado {
    constructor() { this.lang = 'es-AR'; this.continuous = false; this.interimResults = true; this.maxAlternatives = 1; this.id = ''; }
    start() { this.id = 'r' + (++cont); recs[this.id] = this; N.dictar(this.id, this.lang || 'es-AR'); }
    stop() { N.pararDictado(); }
    abort() { N.cancelarDictado(); }
  }
  window.SpeechRecognition = Dictado;
  window.webkitSpeechRecognition = Dictado;

  function Frase(t) { this.text = String(t || ''); this.lang = 'es-AR'; this.voice = null; this.onend = null; this.onerror = null; }
  window.SpeechSynthesisUtterance = Frase;
  const voz = {
    speak(u) { const id = 't' + (++cont); voces[id] = u; N.hablar(id, String(u.text || ''), u.lang || 'es-AR'); },
    cancel() { N.callar(); },
    getVoices() { return []; },
    speaking: false, pending: false, paused: false
  };
  try { Object.defineProperty(window, 'speechSynthesis', { value: voz, configurable: true }); } catch (_) { window.speechSynthesis = voz; }
})();
