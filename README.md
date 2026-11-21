# Asistente de voz

Asistente personal por voz, tipo Alexa, como **app web instalable** (PWA) para el celular. Independiente de cualquier otro sistema.

## Qué hace hoy
- Se toca el micrófono, se habla y responde en voz alta.
- Hora y fecha, clima de cualquier ciudad, temporizadores y recordatorios, listas (compras, notas, pendientes).
- Preguntas generales cortas: cuentas, conversiones, traducciones, recetas, chistes.
- Todo se guarda solo en el celular (clave, listas, ajustes).

## Cómo se usa
1. Publicar estos archivos con HTTPS (el micrófono del navegador lo exige). Lo más simple: GitHub Pages.
2. Abrir la dirección en Chrome del celular y tocar **Instalar app** / **Agregar a pantalla de inicio**.
3. En **Ajustes** pegar la clave gratis de Google (https://aistudio.google.com/apikey), el nombre y la ciudad.

## Archivos
- `index.html`: la pantalla. `nucleo.js`: la lógica (IA, herramientas, listas). `sw.js` y `manifest.json`: app instalable.
- `tests/`: controles sin conexión (`node --test tests/nucleo.test.js`).

## Cómo enchufarle otro sistema más adelante (por ejemplo, la ferretería)
Cada habilidad es un objeto `{ nombre, declaracion, ejecutar }`. Para sumar una, definir `window.CONECTORES = [ ... ]`
antes de cargar la pantalla (ver `herramientas` en `nucleo.js`). La IA la ofrece sola; no hay que tocar nada más.
Ejemplo: un conector `precio_producto` que llame a la API del sistema con un token propio.

## Límites de esta versión
- Se toca el micrófono para hablar (sin palabra de activación manos libres).
- Los temporizadores suenan con la pantalla abierta; hay una opción para mantenerla encendida.
- La clave de Google queda guardada en el navegador de ese celular: usar una clave propia y sin cobro asociado.
