# Detector de Baches - YOLO26n 416 INT8 - V7 FINAL MOBILE

Versión móvil optimizada para detección y segmentación de baches desde una cámara de teléfono montado de forma fija.

## Qué cambia en V7

1. **Severidad visual compensada por perspectiva**
   - Ya no usa solamente el porcentaje bruto de máscara.
   - Reduce la inflación de severidad cuando el mismo bache se acerca a la cámara.
   - Aplica compensación acotada según la posición vertical del bache.
   - Mantiene histéresis y suavizado temporal.
   - Sigue siendo una estimación visual, no una medición física de profundidad.

2. **Severidad temporal estable**
   - Usa EMA del área observada y del área compensada.
   - Requiere evidencia en más de un frame para subir o bajar de nivel.
   - Elimina el comportamiento de "ALTA permanente" producido por un único frame cercano.

3. **Tracking reforzado**
   - Asociación global por IoU, distancia, escala y velocidad.
   - Da prioridad suave a tracks confirmados.
   - Puede recuperar el mismo ID tras pérdidas breves de hasta ~1 s.
   - Los tracks perdidos nunca se dibujan ni cuentan.
   - Fusiona tracks duplicados que representan el mismo bache.

4. **Score del modelo presentado correctamente**
   - Se conserva el score crudo del modelo y se suaviza el mostrado por track.
   - Se muestra con 1 decimal (ej. 49.7%) para no ocultar variación por redondeo.
   - El HUD usa el término **score IA** y no lo presenta como probabilidad calibrada.
   - El control inferior se llama **Umbral IA**.

5. **Conteos más rigurosos**
   - `Visibles`: detecciones observadas en el frame actual.
   - `Confirmados`: tracks vistos al menos en 2 observaciones.
   - Baja/Media/Alta se contabilizan solo en tracks confirmados.

## Modelo

- YOLO26n-seg
- LiteRT/TFLite
- Entrada 416x416
- INT8 interno, I/O FLOAT32
- CPU/XNNPACK
- Modelo no modificado respecto a V6
- SHA-256: `72ac2067cf6495b168f6a8d550c9d36b8abf4bb387f6e5fedd72ee7b65b09f93`

## Rendimiento de referencia medido antes de V7

En las pruebas del dispositivo se observaron aproximadamente:

- IA: 166-170 ms
- Total: 178-187 ms
- IA FPS: 5
- p95: ~198-202 ms
- Térmico: normal

V7 intenta conservar ese rendimiento; las mejoras son principalmente de estabilidad, severidad y rigor de presentación del score.

## Umbral recomendado

- Equilibrado: `0.35`
- Mayor sensibilidad: `0.25-0.30`

No bajar el umbral durante conducción salvo que se esté haciendo una prueba controlada, porque aumenta detecciones falsas.

## Importante sobre severidad

`BAJA / MEDIA / ALTA` es **severidad visual compensada**, no profundidad real ni clasificación estructural del pavimento. Para severidad física se requeriría calibración geométrica, distancia/escala real o sensor de profundidad.

## Compilación

- Gradle JVM: 17 o 21
- Android 6+ (`minSdk 23`)
- `targetSdk 36`

En Android Studio:

1. `Build -> Clean Project`
2. `Build -> Assemble Project`
3. `Build -> Generate App Bundles or APKs -> Generate APKs`

## Pruebas en carretera

El teléfono debe ir fijo. La app no debe manipularse mientras se conduce. Para pruebas dinámicas, usar un pasajero o un entorno controlado.
