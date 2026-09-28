package com.pothole.v3

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * Geolocalización robusta para evidencias de baches.
 *
 * - No bloquea la inferencia.
 * - Combina GPS + NETWORK + PASSIVE cuando están disponibles.
 * - Solicita un fix inmediato en Android 11+ además de las actualizaciones continuas.
 * - Distingue permiso, servicio apagado y búsqueda real para evitar "GPS buscando" eterno.
 */
class LocationRecorder(private val context: Context) : LocationListener {
    enum class State { DISABLED, NO_PERMISSION, LOCATION_OFF, SEARCHING, READY, ERROR }

    data class Snapshot(
        val latitude: Double,
        val longitude: Double,
        val accuracyM: Float,
        val timestampMillis: Long,
        val ageMs: Long,
        val provider: String
    )

    data class Status(
        val state: State,
        val snapshot: Snapshot? = null,
        val detail: String = ""
    )

    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    @Volatile private var last: Location? = null
    @Volatile private var running = false
    @Volatile private var state: State = State.DISABLED
    @Volatile private var lastError: String = ""
    @Volatile private var enabledByUser: Boolean = false

    fun startIfAllowed(enabled: Boolean) {
        enabledByUser = enabled
        stopInternal(clearState = false)
        if (!enabled) {
            state = State.DISABLED
            return
        }

        val fine = hasFinePermission()
        val coarse = hasCoarsePermission()
        if (!fine && !coarse) {
            state = State.NO_PERMISSION
            return
        }
        if (!isLocationServiceEnabled()) {
            state = State.LOCATION_OFF
            return
        }

        state = State.SEARCHING
        lastError = ""

        // Aprovecha cualquier fix reciente existente mientras llega uno nuevo.
        listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).forEach { provider ->
            runCatching {
                if (manager.allProviders.contains(provider)) {
                    manager.getLastKnownLocation(provider)?.let(::consider)
                }
            }
        }

        var requested = false
        runCatching {
            if (fine && providerEnabled(LocationManager.GPS_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, this)
                requested = true
                requestImmediateFix(LocationManager.GPS_PROVIDER)
            }
            if (providerEnabled(LocationManager.NETWORK_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 750L, 0f, this)
                requested = true
                requestImmediateFix(LocationManager.NETWORK_PROVIDER)
            }
            if (providerEnabled(LocationManager.PASSIVE_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 1000L, 0f, this)
                requested = true
            }
        }.onFailure {
            lastError = it.javaClass.simpleName + ": " + (it.message ?: "error de ubicación")
        }

        running = requested
        if (!requested && last == null) {
            state = if (isLocationServiceEnabled()) State.ERROR else State.LOCATION_OFF
            if (lastError.isBlank()) lastError = "No hay proveedor de ubicación disponible"
        } else if (last != null) {
            state = State.READY
        }
    }

    /** Fuerza una nueva búsqueda sin bloquear la cámara/IA. */
    fun refresh() = startIfAllowed(enabledByUser)

    fun stop() = stopInternal(clearState = false)

    private fun stopInternal(clearState: Boolean) {
        if (running) runCatching { manager.removeUpdates(this) }
        running = false
        if (clearState) state = State.DISABLED
    }

    /**
     * Fix para persistencia. Por defecto exige una posición bastante reciente porque
     * el vehículo puede estar en movimiento. La edad exacta queda almacenada en Room/CSV.
     */
    fun snapshot(maxAgeMs: Long = 6_000L): Snapshot? = snapshotFrom(last, maxAgeMs)

    /** Fix relajado sólo para mostrar estado en UI. No se usa para registrar un bache. */
    fun displaySnapshot(maxAgeMs: Long = 30_000L): Snapshot? = snapshotFrom(last, maxAgeMs)

    fun status(): Status {
        if (!enabledByUser) return Status(State.DISABLED)
        if (!hasFinePermission() && !hasCoarsePermission()) return Status(State.NO_PERMISSION)
        if (!isLocationServiceEnabled()) return Status(State.LOCATION_OFF)
        val snap = displaySnapshot()
        if (snap != null) return Status(State.READY, snap)
        return Status(if (state == State.ERROR) State.ERROR else State.SEARCHING, detail = lastError)
    }

    fun isLocationServiceEnabled(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            providerEnabled(LocationManager.GPS_PROVIDER) || providerEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }.getOrDefault(false)

    private fun hasFinePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasCoarsePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun providerEnabled(provider: String): Boolean = runCatching {
        manager.allProviders.contains(provider) && manager.isProviderEnabled(provider)
    }.getOrDefault(false)

    private fun requestImmediateFix(provider: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            manager.getCurrentLocation(
                provider,
                null,
                ContextCompat.getMainExecutor(context)
            ) { location ->
                if (location != null) consider(location)
            }
        }
    }

    private fun snapshotFrom(location: Location?, maxAgeMs: Long): Snapshot? {
        val l = location ?: return null
        val age = locationAgeMs(l)
        if (age > maxAgeMs) return null
        return Snapshot(
            latitude = l.latitude,
            longitude = l.longitude,
            accuracyM = if (l.hasAccuracy()) l.accuracy else 9999f,
            timestampMillis = l.time,
            ageMs = age,
            provider = l.provider.orEmpty()
        )
    }

    private fun locationAgeMs(l: Location): Long {
        // elapsedRealtime evita errores si cambia la hora del teléfono.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && l.elapsedRealtimeNanos > 0L) {
            return ((SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000L).coerceAtLeast(0L)
        }
        return (System.currentTimeMillis() - l.time).coerceAtLeast(0L)
    }

    private fun consider(candidate: Location) {
        if (!candidate.latitude.isFinite() || !candidate.longitude.isFinite()) return
        val current = last
        if (current == null) {
            last = Location(candidate)
            state = State.READY
            return
        }

        val candidateAge = locationAgeMs(candidate)
        val currentAge = locationAgeMs(current)
        val candidateAcc = if (candidate.hasAccuracy()) candidate.accuracy else 9999f
        val currentAcc = if (current.hasAccuracy()) current.accuracy else 9999f

        // Para carretera: prima frescura; a frescura similar, prima precisión.
        val muchNewer = candidateAge + 1500L < currentAge
        val similarAge = kotlin.math.abs(candidateAge - currentAge) <= 2000L
        val materiallyMoreAccurate = candidateAcc + 4f < currentAcc
        val acceptableNewer = candidateAge <= currentAge && candidateAcc <= currentAcc + 12f
        if (muchNewer || (similarAge && materiallyMoreAccurate) || acceptableNewer) {
            last = Location(candidate)
            state = State.READY
        }
    }

    override fun onLocationChanged(location: Location) = consider(location)
    override fun onProviderEnabled(provider: String) {
        if (enabledByUser) state = State.SEARCHING
    }
    override fun onProviderDisabled(provider: String) {
        if (enabledByUser && !isLocationServiceEnabled()) state = State.LOCATION_OFF
    }
    @Deprecated("Deprecated in Android framework")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
}
