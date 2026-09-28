# Recuperación / diagnóstico

Si la app no abre o se cierra:

1. Abrir Logcat y filtrar `AndroidRuntime` / `FATAL EXCEPTION`.
2. Confirmar Gradle JVM 17 o 21.
3. Limpiar y recompilar el proyecto.
4. Si el problema aparece sólo con largo alcance, seleccionar perfil `RÁPIDO`; el 416 principal seguirá funcionando.
5. Si se revoca cámara/ubicación, volver a conceder únicamente los permisos deseados.

El diseño mantiene la inferencia 416 independiente del detector 512 y del almacenamiento para que un fallo secundario no detenga la ruta principal.
