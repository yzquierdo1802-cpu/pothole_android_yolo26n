# Auditoría V8 PRODUCTION

## Modelo
- YOLO26n-seg 416 INT8 intacto.
- SHA-256: `72ac2067cf6495b168f6a8d550c9d36b8abf4bb387f6e5fedd72ee7b65b09f93`.

## Compatibilidad
- minSdk 23.
- targetSdk 36.
- CameraX 1.6.2.
- Interfaz responsive por orientación y ancho físico disponible.
- Selector de resolución CameraX con fallback.
- Cámara trasera preferida; fallback a cámara frontal si no existe trasera.

## Datos / privacidad
- No existe permiso INTERNET.
- Imágenes y CSV se procesan y almacenan localmente.
- Imágenes públicas en Android 10+ se guardan mediante MediaStore.
- CSV se comparte mediante FileProvider y se exporta bajo acción explícita del usuario.

## Reportes
- Persistencia CSV por append sincronizado.
- `fsync()` tras cada fila para reducir pérdida de datos ante cierre inesperado.
- Una fila por track confirmado.
- Evidencia JPEG anotada con máscara, caja, ID, severidad y score.
- Escritura y compresión en hilo de baja prioridad para reducir impacto en inferencia.

## Orientación
- MainActivity no está bloqueada en portrait.
- Maneja `orientation|screenSize|smallestScreenSize` sin recrear/cerrar el intérprete.
- Al rotar, CameraX se vuelve a enlazar y el tracker limpia coordenadas pero conserva la secuencia de IDs.

## Pendiente externo a este entorno
- Compilación Gradle final en Android Studio.
- Firma release con keystore del propietario.
- Pruebas de campo en varios OEM/Android.
