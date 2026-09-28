# Changelog

## 18.0-gps-fastfix
- Estado GPS explícito: permiso, servicio apagado, búsqueda, listo y error.
- Fix inmediato Android 11+ y uso conjunto de GPS/NETWORK/PASSIVE.
- Chip GPS interactivo para reintentar o abrir Ajustes del sistema.
- Persistencia mantiene requisito de fix reciente para no desplazar coordenadas del bache.
- MAPA con fallback app de mapas -> navegador.
- Reportes sin coordenadas muestran `SIN GPS` y explicación.

## 17.0-gps-georeport
- GPS opcional por bache con latitud, longitud, precisión, timestamp y proveedor.
- Fix GPS más fresco/preciso para vehículo en movimiento; stale limit 5 s.
- Reverse geocoding asíncrono en ruta de reportes para guardar vía/lugar/localidad/región/país.
- Room v4 y CSV ampliado con campos geoespaciales.
- Chip GPS en pantalla En vivo.
- Reportes muestran lugar/coordenadas y permiten abrir el punto en una app de mapas.
- Búsqueda por nombre de lugar y coordenadas.

## 16.0-professional-ui
- Interfaz En vivo rediseñada con cámara dominante y dashboard translúcido.
- Navegación inferior Galería / En vivo / Ajustes.
- Galería/Reportes oscura con miniaturas asíncronas y filtros.
- Ajustes y calibración con estética Material 3 coherente.
- Modelos y pipeline científico sin cambios.

## 15.0-scientific-mask-render
- Contorno vectorial por detección; se elimina el render borroso de bitmap como visualización principal.
- Threshold de máscara adaptativo, closing, opening, relleno de huecos y componente dominante.
- Suavizado Chaikin + estabilización temporal del polígono.
- Contorno sólido, relleno uniforme translúcido y bbox secundario.
- Transferencia de largo alcance basada en polígonos para reducir copias y blur.
- Evidencia científica: original JPG + máscara binaria PNG + overlay JPG.
- Room v3 y CSV con área/centroide/rutas de evidencia.
- Reportes permiten abrir OVERLAY / MÁSCARA / ORIGINAL.

## 14.0-clean-mask-pro
- Pipeline de máscara refinado con relleno de huecos internos.
- Render profesional: relleno uniforme, borde sólido y halo externo sutil.
- Colores de segmentación más claros y estéticos.
- Ajuste visual de overlay y etiquetas.

## 13.0-professional-mask-track-fusion
- Segmentación refinada: componente principal, cierre morfológico y suavizado conservador.
- Máscara translúcida con borde sólido; nueva estética de cajas/etiquetas.
- Handoff explícito 512 -> 416 conservando ID.
- Estados LEJANO / POSIBLE / APROX.
- Room v2 con first-seen, confirmación y demora.
- Exportación CSV ampliada.
- Menor tolerancia a resultados secundarios obsoletos.

## 12.0-professional-realtime

- Room como persistencia primaria.
- 416 principal no bloqueante + 512 ROI asíncrono.
- Perfil adaptativo por dispositivo/temperatura/latencia.
- Primera detección visual inmediata como POSIBLE.
- Calibración carretera/perspectiva.
- Georreferenciación opcional.
- Reportes con búsqueda, filtros y eliminación masiva.
- Material 3, modo operación y diagnóstico.
