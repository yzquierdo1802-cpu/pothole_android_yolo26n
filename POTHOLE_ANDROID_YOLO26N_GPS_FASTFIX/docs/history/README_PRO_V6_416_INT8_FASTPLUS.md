# POTHOLE ANDROID YOLO26n PRO V6 — 416 INT8 FAST+

Versión experimental optimizada para comparar rendimiento contra V5 512 INT8 FAST+.

## Cambios
- Modelo reemplazado por `modelomovil-v416.tflite`.
- El motor sigue detectando dinámicamente la resolución de entrada y las dimensiones de salida.
- Se conserva el pipeline estable de V5 FAST+: CameraX RGBA, KEEP_ONLY_LATEST, CPU/XNNPACK, NMS reforzado, tracker global, fusión de tracks, severidad visual estimada y métricas Cam/Pre/IA/Post/Total/p95.
- No se añadió GPU delegate.
- Botones: INICIAR MODELO / PAUSAR MODELO / REANUDAR MODELO.

## Modelo integrado
- Archivo: `app/src/main/assets/pothole_yolo26n_seg_416_int8.tflite`
- SHA-256: `72ac2067cf6495b168f6a8d550c9d36b8abf4bb387f6e5fedd72ee7b65b09f93`
- Tamaño: 3,239,532 bytes.
- La inspección binaria muestra operadores/tensores internos cuantizados y cabeza `Segment26`, coherente con YOLO26n-seg INT8.

## Objetivo de prueba
Comparar contra V5 512 INT8 en el mismo teléfono y escenas similares:
- IA ms
- Total ms
- IA FPS
- p95
- Visibles / Confirmados
- Recall visual de baches pequeños/lejanos
- estabilidad de IDs

Usar 416 solo si la ganancia de velocidad compensa cualquier pérdida de detección de baches pequeños.
