# Detector de Baches — V10 LONG-RANGE PRODUCTION

Versión orientada a detectar baches más pequeños a mayor distancia sin reemplazar el modelo móvil 416 INT8 por un modelo 960 demasiado costoso para CPU.

## Estrategia

- Cámara de análisis preferida: **1280×720** con fallback automático al tamaño compatible más cercano.
- Modelo principal: **YOLO26n-seg 416 INT8** sin cambios.
- Inferencia normal sobre el frame completo.
- Cada 2 frames se ejecuta una pasada secundaria si el estado térmico lo permite.
- Dos bandas secundarias:
  - **MEDIO 5–10 m**: crop amplio.
  - **LARGO 10–15 m**: crop más estrecho sobre la zona de fuga de la carretera.
- Dos de cada tres pasadas secundarias priorizan LONG.
- El ROI secundario usa **interpolación bilineal** para conservar mejor bordes/textura de objetos pequeños.
- Umbral secundario: 0.28 para MEDIO y 0.24 para LARGO, combinado con tracking/confirmación temporal para limitar falsos positivos.
- Las detecciones de frame completo y ROI se fusionan con NMS/deduplicación y pasan por el mismo tracker.
- Se mantienen captura automática, reportes CSV, eliminación individual/masiva, rotación vertical/horizontal y severidad compensada.

## Por qué no usar 960 como entrada principal en el celular

Un tensor 960×960 contiene más de cinco veces los píxeles de 416×416. En el dispositivo probado, 416 ya requiere alrededor de 170 ms de inferencia; ejecutar 960 continuamente en CPU reduciría demasiado los FPS. Para largo alcance es más eficiente conservar una cámara HD y ampliar sólo la región útil antes de pasarla por el modelo 416.

## Importante

La distancia de 10–15 m es un objetivo operativo, no una garantía métrica: depende de focal/FOV de la cámara, altura y ángulo de montaje, iluminación, velocidad del vehículo, tamaño real del bache y presencia de ejemplos lejanos en el entrenamiento.

## Prueba recomendada

Comparar en el mismo tramo:
- distancia visual aproximada a la primera detección,
- IA FPS,
- IA ms / Total ms / p95,
- falsos positivos,
- continuidad del mismo ID desde lejos hasta cerca.
