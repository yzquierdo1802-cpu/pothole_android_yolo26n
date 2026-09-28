# Detector de Baches · YOLO26n 512 INT8 FAST+

Versión V5 enfocada en estabilidad de tracking y eliminación de detecciones duplicadas, manteniendo el modelo 512 INT8 y el pipeline rápido de la V4.

## Cambios de esta versión

- NMS más efectivo para una sola clase: IoU 0.38 y solapamiento sobre caja pequeña 0.58.
- Criterio adicional de centro/escala para eliminar propuestas casi concéntricas del mismo bache.
- Asociación global detección↔track: ya no depende del orden de las detecciones.
- Matching por IoU, distancia, tamaño y velocidad para conservar el mismo ID cuando el bache se acerca.
- Tracks perdidos se retienen brevemente sólo internamente para recuperar el ID, pero **no se dibujan**.
- Eliminadas las cajas fantasma con prefijo `~`.
- Fusión final de tracks fuertemente duplicados; se conserva el ID más estable.
- Conteos `Visibles` y `Confirmados` ahora se calculan sólo con detecciones observadas en el frame actual.
- El modelo y el runtime permanecen sin cambios: YOLO26n-seg 512 INT8 + CPU/XNNPACK.
- Botón consistente: INICIAR / PAUSAR / REANUDAR MODELO.

## Qué no se cambió

- Modelo `.tflite`.
- Resolución 512×512.
- CameraX RGBA y KEEP_ONLY_LATEST.
- Severidad visual estimada.
- Métricas Cam / Pre / IA / Post / Total / Prom / p95.
- CPU/XNNPACK estable; no se activa GPU automáticamente.

## Resultado esperado

La latencia de IA debería permanecer aproximadamente en el rango de la V4 (depende del dispositivo). Esta versión busca principalmente que un mismo bache conserve un único ID, que no queden cajas antiguas visibles y que el contador no se infle por duplicados.
