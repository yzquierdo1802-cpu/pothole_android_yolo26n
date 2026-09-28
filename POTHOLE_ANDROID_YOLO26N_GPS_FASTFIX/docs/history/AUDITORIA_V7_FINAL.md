# Auditoría V7 FINAL MOBILE

## Cambios técnicos verificados

- Modelo 416 INT8 conservado sin alteraciones.
- CameraX RGBA_8888 y KEEP_ONLY_LATEST conservados.
- CPU/XNNPACK conservado; no se introduce GPU delegate.
- Preprocesamiento directo al tensor conservado.
- NMS y deduplicación conservados.
- Tracker ampliado a 4 misses / 1200 ms para recuperación de ID sin cajas fantasma.
- Asociación añade bonus a tracks confirmados y recuperación breve.
- Área máxima histórica sustituida por EMA para no congelar severidad alta.
- Perspectiva compensada con factor acotado ~0.88-1.55 según posición vertical.
- Histeresis + 2 frames de evidencia para cambios de severidad.
- Score mostrado con un decimal y etiquetado como score IA, no probabilidad calibrada.
- Conteos de severidad restringidos a tracks confirmados.

## Pendiente para validación científica

- Evaluar 416 INT8 vs 512 INT8 sobre el mismo TEST independiente.
- Medir precisión/recall/mAP de segmentación después de la exportación 416.
- Calibrar severidad contra mediciones físicas si el estudio desea afirmar severidad estructural real.
