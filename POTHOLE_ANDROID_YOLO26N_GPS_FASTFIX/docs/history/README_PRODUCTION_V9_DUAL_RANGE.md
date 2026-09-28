# Detector de Baches — V9 DUAL-RANGE PRODUCTION

Versión de producción basada en YOLO26n-seg 416 INT8. Mantiene el pipeline estable CPU/XNNPACK de V8 y agrega detección de mayor alcance más administración completa de evidencias.

## Detección dual-range

La app ejecuta una inferencia normal sobre el frame completo. Cada 3 frames ejecuta además una segunda inferencia sobre un ROI central de carretera media/lejana, ampliado al input 416x416. El objetivo es recuperar detalle de baches pequeños a aproximadamente 5–7 m sin volver al coste permanente de 512/640.

- Pasada normal: umbral configurable por el usuario (0.35 por defecto).
- Pasada lejana: hasta 0.30 para favorecer sensibilidad.
- Fusión cross-range con supresión de duplicados.
- Un solo tracker/ID para ambas pasadas.
- Máscara de segmentación del ROI proyectada sobre el frame original.
- La pasada lejana se omite si Android informa estrés térmico severo.

El HUD indica `Alcance: NORMAL` o `Alcance: NORMAL + LEJANO` en los frames donde se ejecuta la segunda pasada.

## Reportes y evidencias

Cuando un track queda confirmado se registra una sola evidencia:

- JPG anotado con máscara, caja, severidad e ID.
- Fila CSV persistente.
- Fecha/hora, score, severidad, área segmentada, orientación y latencias.

El módulo REPORTES permite:

- eliminar un registro individual;
- seleccionar varios registros mediante checkbox;
- `SELECCIONAR TODO`;
- limpiar la selección;
- eliminar masivamente los seleccionados;
- borrar también la imagen asociada para evitar archivos huérfanos;
- exportar y compartir CSV.

La eliminación pide confirmación. Si se seleccionan todos los registros, el diálogo advierte explícitamente que se eliminarán todos los registros administrados por la app y sus imágenes.

> Los CSV que el usuario haya exportado previamente a Descargas son copias externas y no se eliminan al borrar registros desde la app.

## Pantalla y compatibilidad

- vertical y horizontal;
- teléfonos y tablets;
- panel responsive;
- CameraX con resolución 640x480 y fallback automático;
- Android 6+ (`minSdk 23`);
- `targetSdk 36`;
- APK universal;
- sin permiso de Internet.

## Modelo

`app/src/main/assets/pothole_yolo26n_seg_416_int8.tflite`

SHA-256 esperado:

`72ac2067cf6495b168f6a8d550c9d36b8abf4bb387f6e5fedd72ee7b65b09f93`

## Rendimiento esperado

La pasada normal conserva el rendimiento de la V7/V8 (~166–190 ms en el dispositivo probado). Los frames dual-range realizan dos inferencias secuenciales, por lo que esos frames tardarán aproximadamente el doble. Al ejecutarlos cada 3 frames, el objetivo es conservar un promedio cercano a 4 FPS mientras mejora la sensibilidad a distancia.

El valor real debe medirse en cada dispositivo.

## Compilación

- Gradle JVM: 17 o 21
- Gradle: 8.11.1
- AGP: 8.9.1

En Android Studio:

1. `Build > Clean Project`
2. `Build > Assemble Project`
3. Para distribución: `Build > Generate Signed App Bundle / APK`

La firma de producción debe utilizar un keystore privado del propietario de la aplicación.
