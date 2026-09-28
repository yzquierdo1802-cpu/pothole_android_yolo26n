# Detector de Baches — V8 PRODUCTION

Base: YOLO26n-seg 416 INT8 + CameraX + TensorFlow Lite/XNNPACK.

## Funciones de producción incluidas

- Interfaz adaptable a teléfono/tablet y a orientación vertical/horizontal.
- Rotación sin destruir/cargar nuevamente el modelo: MainActivity maneja cambios de orientación y vuelve a enlazar CameraX.
- CameraX con RGBA, `KEEP_ONLY_LATEST`, ViewPort compartido y selección 640x480 con fallback para cámaras que no soporten exactamente ese tamaño.
- Detección, segmentación, tracking, NMS, severidad visual compensada, FPS y latencias.
- Evidencia automática: se guarda **una imagen por bache confirmado (track único)**, no una imagen por cada frame. Esto evita cientos de archivos duplicados del mismo bache.
- La imagen guardada contiene la segmentación y la caja/etiqueta del bache detectado.
- Android 10+ guarda imágenes en `Pictures/DetectorBaches` mediante MediaStore. Android 6–9 usa almacenamiento externo privado de la app, sin pedir permisos de almacenamiento.
- CSV maestro local persistente en la app. Cada bache confirmado agrega una fila.
- Módulo `REPORTES`: lista los 500 registros más recientes, permite abrir la imagen, exportar el CSV a Descargas y compartirlo.
- Android 10+ exporta CSV a `Downloads/DetectorBaches`.
- FileProvider seguro para compartir CSV y archivos privados en Android antiguos.
- Sin permiso de Internet y con `usesCleartextTraffic=false`.
- Release con minificación y reducción de recursos.

## Campos CSV

`event_id,timestamp_iso,session_id,track_id,severity,score_ai,raw_score_ai,mask_area_ratio,compensated_area_ratio,perspective_factor,frame_width,frame_height,orientation,inference_ms,total_ms,image_name,image_uri`

El CSV usa el punto como separador decimal y escapa campos según CSV.

## Comportamiento de captura

Un reporte se crea cuando un track pasa a estado **confirmado**. El mismo track no vuelve a guardarse en cada frame. Si el bache desaparece y el tracker recupera el mismo ID, tampoco se duplica. Si se pierde definitivamente y reaparece como un track nuevo, se considera un evento nuevo.

## Compilar

- Android Studio actualizado.
- Gradle JVM: JDK 17 o 21.
- `Build > Clean Project`
- `Build > Assemble Project`
- Para pruebas: `Build > Generate App Bundles or APKs > Generate APKs`.
- Para producción: `Build > Generate Signed App Bundle / APK`, usando el keystore del propietario.

## Antes de publicar

1. Probar en al menos un equipo Android 6/8, Android 10/12 y Android 14/15/16 si están disponibles.
2. Probar rotación vertical/horizontal durante la inferencia.
3. Verificar que las cajas sigan alineadas después de rotar.
4. Confirmar que una detección confirmada crea una sola imagen y una sola fila CSV.
5. Verificar apertura de imágenes desde REPORTES.
6. Exportar y abrir el CSV en Excel/LibreOffice/Google Sheets.
7. Hacer una prueba continua de 30–60 min para temperatura, memoria y estabilidad.
8. Generar un AAB/APK firmado con un keystore que se guarde fuera del repositorio.

## Nota de validación

El proyecto pasó verificaciones estáticas de estructura, XML, checksum del modelo y presencia de las funciones críticas. En el entorno de generación no se pudo ejecutar `assembleDebug` porque Gradle necesita descargar `gradle-8.11.1` y el acceso de red del entorno está bloqueado. La compilación final debe realizarse en Android Studio.
