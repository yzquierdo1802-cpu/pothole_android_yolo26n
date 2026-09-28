# V11 20M ENHANCED PRODUCTION

Versión orientada a ampliar la detección anticipada de baches hasta un objetivo práctico de 15-20 m, dependiendo de cámara, FOV, luz, velocidad y representación del bache en el dataset. No constituye medición métrica de distancia.

## Estrategia
- Cámara preferida 1920x1080 con fallback al perfil inferior más cercano.
- Modelo móvil conservado: YOLO26n-seg 416 INT8.
- Inferencia normal sobre frame completo.
- ROI 5-10 m, 10-15 m y nuevo ROI EXTENDIDO 15-20 m.
- El ROI 15-20 m se prioriza en 3 de cada 5 pasadas secundarias.
- Barrido lateral IZQ/CENTRO/DER para cubrir mejor el ancho del carril a larga distancia.
- Umbral EXTENDIDO: hasta 0.21, protegido por tracking/confirmación temporal.
- Preprocesado DETAIL_ENHANCED sólo en 15-20 m: bilinear + realce local suave, evitando alterar el camino normal.
- Fusión NMS/tracking entre todas las bandas.

## Visualización
- Verde claro: BAJA (#00E676).
- Amarillo claro: MEDIA (#FFD60A).
- Rojo coral brillante: ALTA (#FF5252).
- Máscara de segmentación con mayor opacidad y cajas con contorno oscuro para exteriores.
- Evidencias guardadas en Reportes usan la misma paleta.

## Rendimiento
La pasada 15-20 m añade una segunda inferencia cada 2 frames. El rendimiento final depende del dispositivo. El análisis secundario se omite automáticamente si el controlador térmico lo requiere.

## Importante
Para lograr 20 m de forma consistente, el dataset debe contener suficientes baches pequeños/lejanos con perspectiva de parabrisas. El software puede ampliar detalle existente, pero no puede reconstruir características que la óptica/sensor no capturó.
