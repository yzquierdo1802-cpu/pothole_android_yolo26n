package com.pothole.v3

import android.content.Context
import android.location.Geocoder
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Reverse geocoder opcional para convertir coordenadas en un lugar legible.
 *
 * - Nunca participa en el hilo de inferencia.
 * - Usa el servicio Geocoder del sistema; si no está disponible, las coordenadas
 *   siguen guardándose normalmente.
 * - Cachea por celda (~10 m) para evitar consultas repetidas cuando varios baches
 *   están en el mismo tramo de vía.
 */
class PlaceResolver(context: Context) {
    data class Place(
        val placeName: String = "",
        val addressLine: String = "",
        val locality: String = "",
        val subAdminArea: String = "",
        val adminArea: String = "",
        val countryName: String = ""
    )

    private val appContext = context.applicationContext
    private val geocoder = Geocoder(appContext, Locale.getDefault())
    private val cache = object : LinkedHashMap<String, Place>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Place>?): Boolean = size > 64
    }

    @Synchronized
    fun resolve(latitude: Double, longitude: Double): Place {
        val key = "%.4f,%.4f".format(Locale.US, latitude, longitude)
        cache[key]?.let { return it }

        if (!Geocoder.isPresent()) {
            return Place().also { cache[key] = it }
        }

        val result = runCatching {
            @Suppress("DEPRECATION")
            geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull()
        }.getOrNull()

        val place = if (result == null) {
            Place()
        } else {
            val locality = result.locality.orEmpty()
            val subAdmin = result.subAdminArea.orEmpty()
            val admin = result.adminArea.orEmpty()
            val country = result.countryName.orEmpty()
            val preferredName = sequenceOf(
                result.thoroughfare,
                result.subLocality,
                result.featureName,
                locality.takeIf { it.isNotBlank() },
                subAdmin.takeIf { it.isNotBlank() },
                admin.takeIf { it.isNotBlank() }
            ).filterNotNull().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()

            Place(
                placeName = preferredName,
                addressLine = runCatching { result.getAddressLine(0) }.getOrNull().orEmpty(),
                locality = locality,
                subAdminArea = subAdmin,
                adminArea = admin,
                countryName = country
            )
        }
        cache[key] = place
        return place
    }
}
