'use strict';
/** Controles del núcleo del asistente. Sin red: Gemini y Open-Meteo son de mentira. */
const test = require('node:test');
const assert = require('node:assert/strict');
const N = require('../nucleo');

const almacenEnMemoria = () => { const m = new Map(); return { get: k => m.get(k) || null, set: (k, v) => m.set(k, v) }; };
function armar({ fecha = '2026-11-21T20:30:00Z', json = async () => null, conectores = [] } = {}) {
  const puestos = []; let cancelados = 0;
  const listas = N.crearListas(almacenEnMemoria());
  const herramientas = N.crearHerramientas({
    ahora: () => new Date(fecha), pedirJSON: json, listas, conectores,
    config: { lat: -34.58, lon: -58.63, lugar: 'Hurlingham' },
    temporizadores: { poner: (s, m) => puestos.push({ s, m }), cancelarTodos: () => { cancelados = puestos.length; puestos.length = 0; return cancelados; } }
  });
  const h = n => herramientas.find(x => x.nombre === n);
  return { herramientas, h, puestos, listas };
}

test('fecha_y_hora: hora y día de Argentina', async () => {
  const { h } = armar(); // 20:30 UTC = 17:30 en Argentina
  const r = await h('fecha_y_hora').ejecutar();
  assert.match(r.hora, /17:30/); assert.match(r.fecha, /sábado/); assert.match(r.fecha, /21/);
});

test('clima: lugar configurado, ciudad pedida, ciudad inexistente y servicio caído', async () => {
  const clima = { current: { temperature_2m: 22.4, weather_code: 2 }, daily: { weather_code: [61, 0], temperature_2m_max: [25.2, 28], temperature_2m_min: [17.1, 18], precipitation_probability_max: [70, 5] } };
  const urls = [];
  const { h } = armar({ json: async u => { urls.push(u); return /geocoding/.test(u) ? (/Nada/.test(u) ? {} : { results: [{ name: 'Mar del Plata', admin1: 'Buenos Aires', latitude: -38, longitude: -57.5 }] }) : clima; } });
  const a = await h('clima').ejecutar({});
  assert.equal(a.lugar, 'Hurlingham'); assert.equal(a.ahora.temperatura, 22); assert.equal(a.hoy.estado, 'con lluvia leve'); assert.equal(a.manana.maxima, 28);
  const b = await h('clima').ejecutar({ ciudad: 'Mar del Plata' });
  assert.equal(b.lugar, 'Mar del Plata, Buenos Aires'); assert.ok(urls.some(u => u.includes('latitude=-38')));
  assert.ok((await h('clima').ejecutar({ ciudad: 'Nada' })).error);
  assert.ok((await armar({ json: async () => null }).h('clima').ejecutar({})).error);
});

test('temporizador: límites, errores y cancelar', async () => {
  const { h, puestos } = armar();
  assert.deepEqual(await h('poner_temporizador').ejecutar({ minutos: 10, motivo: 'el horno' }), { ok: true, segundos: 600, motivo: 'el horno' });
  assert.equal((await h('poner_temporizador').ejecutar({ minutos: 99999 })).segundos, 1440 * 60);
  assert.equal((await h('poner_temporizador').ejecutar({ minutos: 0.01 })).segundos, 5);
  assert.ok((await h('poner_temporizador').ejecutar({ minutos: 0 })).error);
  assert.ok((await h('poner_temporizador').ejecutar({ minutos: 'abc' })).error);
  assert.equal(puestos.length, 3);
  assert.equal((await h('cancelar_temporizadores').ejecutar()).cancelados, 3);
});

test('listas: agregar, ver con números, quitar por número o por texto, vaciar; el nombre se normaliza', async () => {
  const { h } = armar();
  await h('agregar_a_lista').ejecutar({ lista: 'la lista de Compras', item: 'pan' });
  await h('agregar_a_lista').ejecutar({ lista: 'compras', item: 'leche entera' });
  await h('agregar_a_lista').ejecutar({ item: 'regalo de cumpleaños' });          // sin nombre → "notas"
  assert.deepEqual((await h('ver_lista').ejecutar({ lista: 'compras' })).items, ['1. pan', '2. leche entera']);
  assert.equal((await h('ver_lista').ejecutar({})).cantidad, 1);
  assert.deepEqual(await h('quitar_de_lista').ejecutar({ lista: 'compras', item: 'LECHE' }), { lista: 'compras', quitado: 'leche entera' });
  assert.deepEqual(await h('quitar_de_lista').ejecutar({ lista: 'compras', item: '1' }), { lista: 'compras', quitado: 'pan' });
  assert.ok((await h('quitar_de_lista').ejecutar({ lista: 'compras', item: 'pan' })).error);
  assert.ok((await h('agregar_a_lista').ejecutar({ lista: 'compras', item: '  ' })).error);
  assert.equal((await h('vaciar_lista').ejecutar({ lista: 'notas' })).quitados, 1);
});

// IA de mentira: pide una herramienta y después redacta con su resultado.
function geminiFalso(herramienta, args) {
  const pedidos = [];
  const fetchFn = async (url, opciones) => {
    const cuerpo = JSON.parse(opciones.body); pedidos.push({ url, cuerpo, clave: opciones.headers['x-goog-api-key'] });
    const ultimo = cuerpo.contents[cuerpo.contents.length - 1];
    const resp = ultimo.parts.find(p => p.functionResponse);
    const candidato = resp ? { text: 'LISTO ' + JSON.stringify(resp.functionResponse.response) } : { functionCall: { name: herramienta, args } };
    return { ok: true, status: 200, json: async () => ({ candidates: [{ content: { role: 'model', parts: [candidato] } }] }) };
  };
  return { fetchFn, pedidos };
}

test('cerebro: la IA pide una herramienta, se ejecuta acá y la respuesta sale de su resultado', async () => {
  const { herramientas, puestos } = armar();
  const g = geminiFalso('poner_temporizador', { minutos: 2, motivo: 'la pizza' });
  const c = N.crearCerebro({ fetchFn: g.fetchFn, getClave: () => 'CLAVE', getNombre: () => 'Mauricio', herramientas, ahora: () => new Date('2026-11-21T20:30:00Z') });
  const r = await c.responder('acordate de la pizza en un ratito');
  assert.match(r.texto, /LISTO/); assert.deepEqual(puestos, [{ s: 120, m: 'la pizza' }]);
  assert.equal(g.pedidos[0].clave, 'CLAVE');
  assert.match(g.pedidos[0].cuerpo.systemInstruction.parts[0].text, /Mauricio/);
  assert.match(g.pedidos[0].cuerpo.systemInstruction.parts[0].text, /17:30/);
});

test('cerebro: herramienta inexistente o que falla no rompe la conversación; recuerda lo anterior; corta bucles', async () => {
  const { herramientas } = armar({ conectores: [{ nombre: 'rota', declaracion: { name: 'rota', description: 'x' }, ejecutar: async () => { throw new Error('boom'); } }] });
  let g = geminiFalso('no_existe', {});
  let c = N.crearCerebro({ fetchFn: g.fetchFn, getClave: () => 'K', herramientas });
  assert.match((await c.responder('hola')).texto, /no existe/);
  g = geminiFalso('rota', {});
  c = N.crearCerebro({ fetchFn: g.fetchFn, getClave: () => 'K', herramientas });
  assert.match((await c.responder('hola')).texto, /No se pudo hacer eso/);
  await c.responder('y ahora?');
  assert.ok(g.pedidos[g.pedidos.length - 1].cuerpo.contents.length >= 5, 'la segunda consulta incluye la primera');

  let n = 0;
  const siempre = async () => { n++; return { ok: true, status: 200, json: async () => ({ candidates: [{ content: { role: 'model', parts: [{ functionCall: { name: 'fecha_y_hora', args: {} } }] } }] }) }; };
  c = N.crearCerebro({ fetchFn: siempre, getClave: () => 'K', herramientas });
  const r = await c.responder('hola');
  assert.equal(n, 5); assert.equal(r.texto, N.MENSAJES.vacio);   // 4 vueltas con herramientas + 1 final sin herramientas
});

test('cerebro: sin clave, clave mala, límite, caída y respaldo con otro modelo', async () => {
  const { herramientas } = armar();
  const con = (fetchFn, clave = 'K') => N.crearCerebro({ fetchFn, getClave: () => clave, herramientas });
  assert.equal((await con(async () => { throw new Error('x'); }, '').responder('hola')).texto, N.MENSAJES.sinClave);
  assert.equal((await con(async () => ({ ok: false, status: 400 })).responder('hola')).texto, N.MENSAJES.claveMala);
  assert.equal((await con(async () => ({ ok: false, status: 403 })).responder('hola')).texto, N.MENSAJES.claveMala);
  assert.equal((await con(async () => ({ ok: false, status: 429 })).responder('hola')).texto, N.MENSAJES.limite);
  assert.equal((await con(async () => { throw new Error('sin red'); }).responder('hola')).texto, N.MENSAJES.error);
  const urls = [];
  const saturado = async url => { urls.push(url); return urls.length === 1 ? { ok: false, status: 503 } : { ok: true, status: 200, json: async () => ({ candidates: [{ content: { role: 'model', parts: [{ text: 'Hola' }] } }] }) }; };
  assert.equal((await con(saturado).responder('hola')).texto, 'Hola');
  assert.match(urls[0], /flash-lite-latest/); assert.match(urls[1], /models\/gemini-flash-latest/);
});

test('conectores: un objeto nuevo en la lista queda disponible para la IA sin tocar nada más', async () => {
  const conector = { nombre: 'precio', declaracion: { name: 'precio', description: 'Precio de un producto' }, ejecutar: async a => ({ producto: a.q, precio: 1234 }) };
  const { herramientas } = armar({ conectores: [conector] });
  const g = geminiFalso('precio', { q: 'cemento' });
  const r = await N.crearCerebro({ fetchFn: g.fetchFn, getClave: () => 'K', herramientas }).responder('cuánto sale el cemento');
  assert.match(r.texto, /1234/);
  assert.ok(g.pedidos[0].cuerpo.tools[0].functionDeclarations.some(f => f.name === 'precio'));
});

test('palabra de activación: acepta cómo la escribe el dictado y separa la orden', () => {
  const d = t => N.detectarActivacion(t, 'Morsis');
  assert.deepEqual(d('Morsis qué hora es'), { activado: true, orden: 'que hora es' });
  assert.deepEqual(d('morcis, cómo está el clima'), { activado: true, orden: 'como esta el clima' });
  assert.deepEqual(d('mor sis poné un temporizador'), { activado: true, orden: 'pone un temporizador' });
  assert.equal(d('Morris contame un chiste').activado, true);
  assert.deepEqual(d('Morsis'), { activado: true, orden: '' });
  assert.equal(d('che dame un tornillo de tres cuartos').activado, false);
  assert.equal(d('mostrame el precio').activado, false);
  assert.equal(d('mis cosas').activado, false);
  assert.equal(d('').activado, false);
});

test('respuestas rápidas sin IA: hora, temporizador, cancelar, clima, cómo llegar; lo demás sigue a la IA', async () => {
  const clima = { current: { temperature_2m: 22.4, weather_code: 2 }, daily: { weather_code: [61, 0], temperature_2m_max: [25.2, 28], temperature_2m_min: [17.1, 18], precipitation_probability_max: [70, 5] } };
  const enlaces = [];
  const puestos = [];
  const herramientas = N.crearHerramientas({
    ahora: () => new Date('2026-11-21T20:30:00Z'), pedirJSON: async () => clima, listas: N.crearListas({ get: () => null, set: () => {} }),
    config: { lat: -34.58, lon: -58.63, lugar: 'Hurlingham' }, mostrarEnlace: (u, t) => enlaces.push(u),
    temporizadores: { poner: (s, m) => puestos.push({ s, m }), cancelarTodos: () => { const n = puestos.length; puestos.length = 0; return n; } }
  });
  const r = t => N.respuestaRapida(t, herramientas);
  assert.equal((await r('¿Qué hora es?')).texto, 'Son las 17:30.');
  assert.match((await r('qué día es hoy')).texto, /sábado/);
  assert.match((await r('poné un temporizador de diez minutos para la pizza')).texto, /10 minutos para pizza/);
  assert.deepEqual(puestos[0], { s: 600, m: 'pizza' });
  assert.match((await r('temporizador de media hora')).texto, /30 minutos/);
  assert.match((await r('timer de 45 segundos')).texto, /45 segundos/);
  assert.match((await r('cancelá los temporizadores')).texto, /cancelé/);
  assert.match((await r('qué temperatura hace a esta hora')).texto, /Hurlingham hace 22 grados/);
  assert.match((await r('va a llover mañana')).texto, /Mañana en Hurlingham/);
  assert.match((await r('cómo llego a Rivadavia 5000, Morón')).texto, /Google Maps/);
  assert.match(enlaces[0], /destination=Rivadavia%205000%2C%20Mor%C3%B3n/);
  assert.equal(await r('contame un chiste'), null);
  assert.equal(await r('cuánto es el 25% de 1800'), null);
});

test('ferretería: lo del negocio va directo al sistema con el código; sin vincular avisa; errores claros', async () => {
  const pedidos = [];
  const fetchFn = async (url, o) => { pedidos.push({ url, o }); return { ok: true, status: 200, json: async () => ({ texto: 'El codo sale $1.200.' }) }; };
  let cod = 'MRS-abc';
  const conector = N.crearConectorFerreteria({ url: () => 'https://sistema.x.com/', codigo: () => cod, fetchFn });
  const herramientas = N.crearHerramientas({ ahora: () => new Date(), pedirJSON: async () => null, listas: N.crearListas({ get: () => null, set: () => {} }),
    temporizadores: { poner() {}, cancelarTodos: () => 0 }, conectores: [conector.herramienta] });
  let geminiLlamado = false;
  const c = N.crearCerebro({ fetchFn: async () => { geminiLlamado = true; throw new Error('no'); }, getClave: () => 'K', herramientas });
  assert.equal((await c.responder('¿Cuánto sale el codo de fusión de tres cuartos?')).texto, 'El codo sale $1.200.');
  assert.equal(geminiLlamado, false);
  assert.equal(pedidos[0].url, 'https://sistema.x.com/api/morsis/consulta');
  assert.equal(pedidos[0].o.headers.Authorization, 'Bearer MRS-abc');
  assert.ok(N.esDelNegocio('cuánto debe García')); assert.ok(N.esDelNegocio('cuánto vendimos hoy')); assert.ok(!N.esDelNegocio('contame un chiste'));
  cod = '';
  assert.match((await conector.consultar('precio')).error, /Falta vincular/);
  cod = 'MRS-x';
  const malo = N.crearConectorFerreteria({ url: () => 'https://s', codigo: () => cod, fetchFn: async () => ({ ok: false, status: 401, json: async () => ({ error: 'Código inválido.' }) }) });
  assert.equal((await malo.consultar('precio')).error, 'Código inválido.');
  const caido = N.crearConectorFerreteria({ url: () => 'https://s', codigo: () => cod, fetchFn: async () => { throw new Error('x'); } });
  assert.match((await caido.consultar('precio')).error, /No pude conectarme/);
});

test('palabra de activación: formas comunes del dictado ("Moisés", "Norsis")', () => {
  assert.deepEqual(N.detectarActivacion('Moisés qué hora es', 'Morsis'), { activado: true, orden: 'que hora es' });
  assert.equal(N.detectarActivacion('norsis', 'Morsis').activado, true);
  assert.equal(N.detectarActivacion('mostrador', 'Morsis').activado, false);
});

test('palabra de activación: el detector offline escribe «Mercedes»', () => {
  assert.equal(N.detectarActivacion('mercedes', 'Morsis').activado, true);
});

test('frase cortada por una pausa: se detecta para seguir escuchando', () => {
  assert.equal(N.quedaIncompleta('decime el precio de'), true);
  assert.equal(N.quedaIncompleta('cuánto sale el'), true);
  assert.equal(N.quedaIncompleta('cuánto sale'), true);
  assert.equal(N.quedaIncompleta('precio del sellador hidro 3 chico'), false);
  assert.equal(N.quedaIncompleta('qué hora es'), false);
  assert.equal(N.quedaIncompleta(''), false);
});

test('avisos de transferencias: la primera vez no lee lo viejo; después arma la frase', async () => {
  const urls = [];
  let resp = { ultimo: 40, avisos: [] };
  const fetchFn = async (u, o) => { urls.push({ u, auth: o.headers.Authorization }); return { ok: true, json: async () => resp }; };
  const a = N.crearAvisos({ url: () => 'https://s.com/', codigo: () => 'MRS-x', fetchFn });
  assert.deepEqual(await a.revisar(), []);
  assert.equal(urls[0].u, 'https://s.com/api/morsis/avisos'); assert.equal(urls[0].auth, 'Bearer MRS-x');
  resp = { ultimo: 41, avisos: [{ id: 41, monto: 15000, remitente: 'Juan García', origen: 'mercadopago' }] };
  assert.deepEqual(await a.revisar(), ['Entró una transferencia de 15.000 pesos de Juan García por Mercado Pago.']);
  assert.equal(urls[1].u, 'https://s.com/api/morsis/avisos?desde=40');
  resp = { ultimo: 41, avisos: [] };
  await a.revisar(); assert.equal(urls[2].u, 'https://s.com/api/morsis/avisos?desde=41');
  const sin = N.crearAvisos({ url: () => '', codigo: () => '', fetchFn });
  assert.deepEqual(await sin.revisar(), []);
});

test('lo de sumar al ticket va al sistema, con la compu elegida', async () => {
  assert.ok(N.esDelNegocio('sumá 3 codos de tres cuartos')); assert.ok(N.esDelNegocio('agregá un teflón al ticket'));
  const pedidos = [];
  const c = N.crearConectorFerreteria({ url: () => 'https://s', codigo: () => 'MRS-x', compu: () => 'Compu 1', fetchFn: async (u, o) => { pedidos.push(JSON.parse(o.body)); return { ok: true, json: async () => ({ texto: 'Listo' }) }; } });
  await c.consultar('sumá un codo');
  assert.equal(pedidos[0].compu, 'Compu 1');
});

test('códigos dictados: se arman (S030, M1234, PL25) sin romper medidas ni frases comunes', () => {
  const n = N.normalizarCodigos;
  assert.equal(n('sumá 2 s 030'), 'sumá 2 S030');
  assert.equal(n('sumá dos ese cero treinta'), 'sumá dos S030');
  assert.equal(n('cargá eme 12 34'), 'cargá M1234');
  assert.equal(n('precio de la pe ele 25'), 'precio de la PL25');
  assert.equal(n('sumá una cinta de 3 metros'), 'sumá una cinta de 3 metros');
  assert.equal(n('agregá una te 25'), 'agregá una te 25');
  assert.equal(n('sumá 4 a 5 tornillos'), 'sumá 4 a 5 tornillos');
  assert.equal(n('precio del codo de 3/4'), 'precio del codo de 3/4');
});

test('el tránsito y lo de "ahora" se buscan en internet (Google), y lo del negocio sigue yendo a la ferretería', async () => {
  const cuerpos = [];
  const fetchFn = async (u, o) => { cuerpos.push(JSON.parse(o.body)); return { ok: true, json: async () => ({ candidates: [{ content: { parts: [{ text: 'Hay un choque en la Panamericana a la altura de Pacheco.' }] } }] }) }; };
  const consultas = [];
  const conector = { nombre: 'consultar_ferreteria', declaracion: { name: 'consultar_ferreteria' }, ejecutar: async a => { consultas.push(a.pregunta); return { respuesta: 'Sale 5000 pesos.' }; } };
  const h = N.crearHerramientas({ ahora: () => new Date(), pedirJSON: async () => null, listas: N.crearListas(almacenEnMemoria()),
    temporizadores: { poner() {}, cancelarTodos: () => 0 }, conectores: [conector], buscarWeb: N.crearBuscadorWeb({ fetchFn, getClave: () => 'K' }) });
  const c = N.crearCerebro({ fetchFn, getClave: () => 'K', herramientas: h });
  const r = await c.responder('¿Cómo está la Panamericana?');
  assert.match(r.texto, /Pacheco/);
  assert.ok(cuerpos[0].tools[0].google_search);
  await c.responder('cuánto sale el disco de corte');
  assert.deepEqual(consultas, ['cuánto sale el disco de corte']);
});

test('tránsito en vivo (TomTom) y dólar (dolarapi), sin gastar', async () => {
  const pedidas = [];
  const pedirJSON = async url => {
    pedidas.push(url);
    if (url.includes('incidentDetails')) return { incidents: [
      { properties: { iconCategory: 1, magnitudeOfDelay: 3, events: [{ description: 'Accidente' }], from: 'Panamericana (Ramal Pilar) - Av. Márquez', to: 'Panamericana - Ruta 197', delay: 900, length: 2300, roadNumbers: ['RN9'] } },
      { properties: { iconCategory: 6, magnitudeOfDelay: 1, events: [{ description: 'Tránsito lento' }], from: 'Av. General Paz', to: 'Av. Constituyentes', delay: 120, roadNumbers: [] } }
    ] };
    if (url.includes('/search/2/search/')) return { results: [{ position: { lat: -34.45, lon: -58.91 }, address: { freeformAddress: 'Pilar, Buenos Aires' } }] };
    if (url.includes('calculateRoute')) return { routes: [{ summary: { lengthInMeters: 41200, travelTimeInSeconds: 2700, noTrafficTravelTimeInSeconds: 1800, trafficDelayInSeconds: 900 } }] };
    if (url.includes('dolarapi')) return [{ nombre: 'Oficial', compra: 1400, venta: 1450, fechaActualizacion: 'x' }, { nombre: 'Blue', compra: 1480, venta: 1500 }, { nombre: 'Bolsa', compra: 1470, venta: 1475 }];
    return null;
  };
  const h = N.crearHerramientas({ ahora: () => new Date(), pedirJSON, listas: N.crearListas(almacenEnMemoria()), temporizadores: { poner() {}, cancelarTodos: () => 0 },
    tomtom: () => 'TT', config: { lat: -34.59, lon: -58.63, lugar: 'Hurlingham' } });
  const t = await h.find(x => x.nombre === 'transito').ejecutar({ via: 'la Panamericana' });
  assert.equal(t.via, 'panamericana'); assert.equal(t.incidentes.length, 1);
  assert.equal(t.incidentes[0].que, 'un accidente'); assert.equal(t.incidentes[0].minutos_de_demora, 15);
  const v = await h.find(x => x.nombre === 'tiempo_de_viaje').ejecutar({ destino: 'Pilar' });
  assert.equal(v.minutos_con_transito, 45); assert.equal(v.minutos_de_demora, 15); assert.equal(v.kilometros, 41.2);
  const sin = N.crearHerramientas({ ahora: () => new Date(), pedirJSON, listas: N.crearListas(almacenEnMemoria()), temporizadores: { poner() {}, cancelarTodos: () => 0 }, tomtom: () => '' });
  assert.match((await sin.find(x => x.nombre === 'transito').ejecutar({ via: 'General Paz' })).error, /TomTom/);
  const c = N.crearCerebro({ fetchFn: async () => { throw new Error('no debería llamar a Gemini'); }, getClave: () => 'K', herramientas: h });
  const d = await c.responder('a cuánto está el dólar hoy');
  assert.equal(d.texto, 'Dólar Oficial: compra 1400, venta 1450. Blue: compra 1480, venta 1500. Bolsa: compra 1470, venta 1475.');
});

test('"a cuánto está el dólar" va al dólar y no a la ferretería', async () => {
  const pedirJSON = async () => [{ nombre: 'Oficial', compra: 1400, venta: 1450 }, { nombre: 'Blue', compra: 1480, venta: 1500 }];
  const h = N.crearHerramientas({ ahora: () => new Date(), pedirJSON, listas: N.crearListas(almacenEnMemoria()), temporizadores: { poner() {}, cancelarTodos: () => 0 },
    conectores: [{ nombre: 'consultar_ferreteria', declaracion: { name: 'consultar_ferreteria' }, ejecutar: async () => ({ respuesta: 'No tengo esa información.' }) }] });
  const c = N.crearCerebro({ fetchFn: async () => { throw new Error('no'); }, getClave: () => 'K', herramientas: h });
  for (const p of ['A cuánto está el dólar?', 'precio del dólar blue']) assert.match((await c.responder(p)).texto, /^Dólar Oficial: compra 1400/);
});

test('cuentas, descuentos y cuotas, recordatorio a una hora y feriados', async () => {
  const puestos = [];
  const pedirJSON = async url => url.includes('argentinadatos') ? [{ fecha: '2026-11-23', tipo: 'trasladable', nombre: 'Día de la Soberanía Nacional' }, { fecha: '2026-12-08', tipo: 'inamovible', nombre: 'Inmaculada Concepción' }, { fecha: '2026-01-01', tipo: 'inamovible', nombre: 'Año nuevo' }] : null;
  const h = N.crearHerramientas({ ahora: () => new Date('2026-11-22T19:30:00Z'), pedirJSON, listas: N.crearListas(almacenEnMemoria()),
    temporizadores: { poner: (seg, motivo, op) => puestos.push({ seg, motivo, op }), cancelarTodos: () => 0 }, config: { cuotas: { 3: 10, 6: 20 } } });
  const ej = (n, a) => h.find(x => x.nombre === n).ejecutar(a);
  assert.equal((await ej('calcular', { expresion: '3/4 x 25,4' })).resultado, 19.05);
  assert.equal((await ej('calcular', { expresion: '15/4' })).redondeado_para_arriba, 4);
  assert.ok((await ej('calcular', { expresion: 'alert(1)' })).error);
  assert.deepEqual(await ej('precio_final', { monto: 10000, descuento_porcentaje: 10 }), { precio_lista: 10000, descuento_porcentaje: 10, con_descuento: 9000 });
  const c = await ej('precio_final', { monto: 9000, cuotas: 3 });
  assert.equal(c.total_en_cuotas, 9900); assert.equal(c.valor_de_cada_cuota, 3300);
  assert.match((await ej('precio_final', { monto: 9000, cuotas: 12 })).error, /12 cuotas/);
  // 19:30 UTC = 16:30 en Argentina → a las 17:00 faltan 30 minutos
  const r = await ej('poner_recordatorio', { hora: '17:00', texto: 'llamar a Gas Sur' });
  assert.equal(r.hora, '17:00'); assert.equal(puestos[0].seg, 1800); assert.equal(puestos[0].op.recordatorio, true);
  await ej('poner_recordatorio', { hora: '9:00', texto: 'abrir' });
  assert.equal(puestos[1].seg, (24 * 3600) - (7.5 * 3600));
  const f = await ej('feriados', {});
  assert.equal(f.proximos[0].fecha, '2026-11-23'); assert.match(f.proximos[0].dia, /lunes/);
  assert.ok(!f.proximos.some(x => x.fecha === '2026-01-01'));
});

test('con "descuento" o "cuotas" no va directo a la ferretería: pasa por la IA para poder hacer la cuenta', async () => {
  const preguntas = [];
  const conector = { nombre: 'consultar_ferreteria', declaracion: { name: 'consultar_ferreteria' }, ejecutar: async a => { preguntas.push(a.pregunta); return { respuesta: 'ok' }; } };
  const h = N.crearHerramientas({ ahora: () => new Date(), pedirJSON: async () => null, listas: N.crearListas(almacenEnMemoria()), temporizadores: { poner() {}, cancelarTodos: () => 0 }, conectores: [conector] });
  let llamoGemini = false;
  const c = N.crearCerebro({ fetchFn: async () => { llamoGemini = true; return { ok: true, json: async () => ({ candidates: [{ content: { parts: [{ text: 'Con 10% queda 9.000 pesos.' }] } }] }) }; }, getClave: () => 'K', herramientas: h });
  await c.responder('cuánto sale el codo con 10% de descuento');
  assert.equal(llamoGemini, true); assert.deepEqual(preguntas, []);
  llamoGemini = false;
  await c.responder('cuánto sale el codo de 1/2');
  assert.equal(llamoGemini, false); assert.equal(preguntas.length, 1);
});

test('"abramos una venta" va al sistema, y lo que se dice enseguida ("dos codos de 3/4") también', async () => {
  const preguntas = [];
  const conector = { nombre: 'consultar_ferreteria', declaracion: { name: 'consultar_ferreteria' }, ejecutar: async a => { preguntas.push(a.pregunta); return { respuesta: 'Dale, ¿qué sumo?' }; } };
  const h = N.crearHerramientas({ ahora: () => new Date(), pedirJSON: async () => null, listas: N.crearListas(almacenEnMemoria()), temporizadores: { poner() {}, cancelarTodos: () => 0 }, conectores: [conector] });
  let gemini = 0;
  const c = N.crearCerebro({ fetchFn: async () => { gemini++; return { ok: true, json: async () => ({ candidates: [{ content: { parts: [{ text: 'ok' }] } }] }) }; }, getClave: () => 'K', herramientas: h });
  assert.equal((await c.responder('abramos una venta')).texto, 'Dale, ¿qué sumo?');
  await c.responder('dos codos de tres cuartos');
  assert.deepEqual(preguntas, ['abramos una venta', 'dos codos de tres cuartos']);
  await c.responder('recordame a las 5 llamar a gas sur');
  assert.equal(preguntas.length, 2); assert.equal(gemini, 1);
});
