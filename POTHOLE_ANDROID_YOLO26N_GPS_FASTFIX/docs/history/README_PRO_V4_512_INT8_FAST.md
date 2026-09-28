# Detector de Baches – YOLO26n-seg 512 INT8 FAST

Versión basada en la V3 FAST estable, sustituyendo únicamente el modelo 640 por la exportación LiteRT/TFLite 512 INT8 proporcionada por el usuario.

## Modelo verificado
- Tarea: segmentación
- Arquitectura: YOLO26n-seg
- Entrada: `[1, 3, 512, 512]` FLOAT32
- Salida detección: `[1, 37, 5376]` FLOAT32
- Salida prototipos: `[1, 32, 128, 128]` FLOAT32
- Tensores internos: 615 INT8, 157 INT32, 3 FLOAT32
- Clase: `Pothole`
- `end2end=false`
- Cuantización: 8-bit interna
- SHA-256: `9490fa2bdb5cb3cd17f09339df1ef4d5daac1f23ad2b0fe3d1f944119c434ee0`

## Qué conserva de V3 FAST
- CameraX RGBA_8888
- STRATEGY_KEEP_ONLY_LATEST
- CPU/XNNPACK estable
- Preprocesamiento directo RGBA -> tensor
- Buffers reutilizados
- NMS estricto y deduplicación
- Tracking temporal con confirmación
- Severidad visual estimada
- Métricas Cam / Pre / IA / Post / Total / promedio / p95
- Control térmico básico

## Objetivo de esta variante
Reducir la latencia de inferencia observada (~400 ms en 640) disminuyendo la entrada de 640 a 512 y usando cuantización INT8 interna, sin cambiar el pipeline estable.

## Prueba recomendada
Usar confianza inicial 0.35 y registrar durante 30–60 s:
- IA FPS
- IA ms
- Total ms
- Prom ms
- p95 ms
- Térmico
- Visibles / Confirmados

La mejora real debe medirse en el mismo teléfono y condiciones que la versión 640.
