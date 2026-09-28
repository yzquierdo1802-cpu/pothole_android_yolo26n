# Auditoría V10 LONG-RANGE PRODUCTION

## Objetivo
Mejorar detección de baches pequeños a mayor distancia (objetivo operativo 10–15 m) sin sustituir el modelo móvil 416 INT8 por un modelo 960 continuo demasiado costoso para CPU.

## Cambios aplicados
- Fuente CameraX preferida 1280×720, 16:9, con fallback automático.
- Modelo YOLO26n-seg 416 INT8 intacto.
- Inferencia principal sobre frame completo con preprocesado FAST.
- Inferencia secundaria cada 2 frames si el control térmico lo permite.
- ROI MEDIO 5–10 m y ROI LARGO 10–15 m; LONG se prioriza 2 de cada 3 pasadas secundarias.
- ROI secundario procesado con interpolación bilineal DETAIL para preservar mejor textura/bordes de objetos pequeños.
- Umbrales secundarios: 0.28 MEDIO y 0.24 LARGO.
- Fusión, NMS, deduplicación y tracking compartido entre frame completo y ROI.
- Proyección de máscara de segmentación al frame completo.
- HUD muestra banda activa, ganancia geométrica aproximada y resolución real entregada por la cámara.
- Se conservan rotación, UI responsive, evidencias JPG, CSV, exportación/compartición, eliminación individual, múltiple y seleccionar todo.

## Validaciones realizadas
- XML parseable.
- Checksum del modelo intacto.
- Verificador estático de producción: OK.
- Compilación aislada con kotlinc de YoloSegmentationEngine + DualRange + Detection + SeverityEstimator: OK usando stubs Android/TFLite.
- El build Gradle completo no puede certificarse en este entorno porque el wrapper necesita descargar Gradle desde Internet.

## Por qué no 960 continuo
960×960 multiplica fuertemente el costo de cómputo frente a 416×416. Para CPU móvil conviene conservar el tensor 416 y aumentar la densidad de píxeles del objeto mediante cámara HD + crop/zoom de la región útil.

## Limitación científica
10–15 m es un objetivo de diseño, no una medición garantizada. El alcance depende de FOV/focal, altura y ángulo del montaje, tamaño físico del bache, luz, desenfoque por movimiento y representación de baches lejanos en el dataset.
