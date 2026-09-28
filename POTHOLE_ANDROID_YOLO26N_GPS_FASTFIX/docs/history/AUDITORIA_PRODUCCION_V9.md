# Auditoría técnica V9 DUAL-RANGE PRODUCTION

## Cambios validados estáticamente

- Modelo 416 INT8 no modificado.
- CameraX RGBA y `KEEP_ONLY_LATEST` conservados.
- Rotación vertical/horizontal sin recargar el modelo.
- ROI lejano reutilizable sin crear Bitmap por frame.
- Segunda inferencia cada 3 frames.
- Fusión de detecciones normal/lejana antes del tracker.
- Máscara lejana proyectada como capa adicional sobre el frame completo.
- Severidad lejana recalculada en coordenadas del frame completo.
- Reportes conservan máscaras normal y lejana en la evidencia.
- Eliminación individual.
- Selección múltiple y selección total.
- Borrado masivo con confirmación.
- Reescritura atómica del CSV mediante archivo temporal + fsync.
- Eliminación best-effort de imágenes en MediaStore/FileProvider.
- Actualización del contador REPORTES al volver a la pantalla principal.

## Límite de validación

No se pudo ejecutar `assembleDebug` en este entorno porque Gradle necesita descargar la distribución 8.11.1 y no hay acceso externo. XML, estructura, checksum y reglas estáticas se validan con `tools/verify_project.py`.
