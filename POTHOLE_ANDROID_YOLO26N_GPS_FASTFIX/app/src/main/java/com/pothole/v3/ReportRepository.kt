package com.pothole.v3

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * Repositorio transaccional. Room es la fuente de verdad.
 * El CSV se genera bajo demanda para exportar/compartir y nunca se usa como base de datos.
 */
class ReportRepository(private val context: Context) {
    companion object {
        private const val PREFS = "report_repository_v12"
        private const val LEGACY_IMPORTED = "legacy_csv_imported"
        private const val HEADER = "event_id,timestamp_iso,session_id,track_id,severity,score_ai,raw_score_ai,mask_area_ratio,compensated_area_ratio,perspective_factor,frame_width,frame_height,orientation,inference_ms,total_ms,image_name,image_uri,detection_source,distance_band,model_input,app_version,first_seen_timestamp_iso,confirmed_timestamp_iso,first_seen_source,first_seen_distance_band,confirmed_distance_band,confirmation_delay_ms,calibration_horizon,phone_height_cm,latitude,longitude,location_accuracy_m,location_timestamp_ms,location_age_ms,location_provider,place_name,address_line,locality,sub_admin_area,admin_area,country_name,mask_area_px,centroid_x,centroid_y,original_image_name,original_image_uri,mask_image_name,mask_image_uri,overlay_image_name,overlay_image_uri"
    }

    private val dao = AppDatabase.get(context).reportDao()
    private val reportsDir = File(context.filesDir, "reports").apply { mkdirs() }
    val csvFile: File = File(reportsDir, "pothole_reports_export.csv")
    private val legacyCsv = File(reportsDir, "pothole_reports.csv")

    fun append(report: PotholeReport) { ensureLegacyImported(); dao.insert(report) }
    fun count(): Int { ensureLegacyImported(); return dao.count() }
    fun readAll(): List<PotholeReport> { ensureLegacyImported(); return dao.readAll() }
    fun readLatest(limit: Int = 500): List<PotholeReport> {
        ensureLegacyImported()
        return dao.readLatest(limit.coerceAtLeast(1))
    }

    fun deleteByEventIds(eventIds: Set<String>): List<PotholeReport> {
        ensureLegacyImported()
        if (eventIds.isEmpty()) return emptyList()
        val ids = eventIds.toList()
        val deleted = dao.findByIds(ids)
        if (deleted.isNotEmpty()) dao.deleteByIds(ids)
        return deleted
    }

    fun deleteAll(): List<PotholeReport> {
        ensureLegacyImported()
        val all = dao.readAll()
        if (all.isNotEmpty()) dao.deleteAll()
        return all
    }


    /**
     * Deduplicación geoespacial conservadora entre sesiones. Sólo se aplica cuando
     * la ubicación está habilitada, es reciente y tiene precisión razonable.
     */
    fun hasRecentNearby(
        latitude: Double,
        longitude: Double,
        accuracyM: Float,
        currentSessionId: String,
        withinMs: Long = 24L * 60L * 60L * 1000L
    ): Boolean {
        ensureLegacyImported()
        // GPS de consumo no permite deduplicación fiable cuando la precisión es pobre.
        if (accuracyM <= 0f || accuracyM > 10f) return false
        val radiusM = maxOf(3.0, accuracyM.toDouble() * 0.50).coerceAtMost(5.0)
        val recent = dao.recentLocated(System.currentTimeMillis() - withinMs, 500)
        return recent.any { r ->
            val lat = r.latitude ?: return@any false
            val lon = r.longitude ?: return@any false
            val otherAcc = r.locationAccuracyM ?: 999f
            r.sessionId != currentSessionId && otherAcc <= 10f &&
                haversineMeters(latitude, longitude, lat, lon) <= radiusM
        }
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earth = 6_371_000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dp / 2) * kotlin.math.sin(dp / 2) +
            kotlin.math.cos(p1) * kotlin.math.cos(p2) *
            kotlin.math.sin(dl / 2) * kotlin.math.sin(dl / 2)
        return 2.0 * earth * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1.0 - a))
    }

    /** Genera una instantánea CSV coherente con la base Room actual. */
    fun writeCsvSnapshot(): File {
        ensureLegacyImported()
        val rows = dao.readAll().asReversed() // CSV cronológico: antiguo -> reciente
        val tmp = File(reportsDir, "pothole_reports_export.tmp")
        FileOutputStream(tmp, false).use { fos ->
            OutputStreamWriter(fos, StandardCharsets.UTF_8).use { writer ->
                writer.append(HEADER).append('\n')
                rows.forEach { writer.append(toCsvLine(it)).append('\n') }
                writer.flush()
                fos.fd.sync()
            }
        }
        if (csvFile.exists()) csvFile.delete()
        if (!tmp.renameTo(csvFile)) {
            tmp.copyTo(csvFile, overwrite = true)
            tmp.delete()
        }
        return csvFile
    }

    @Synchronized
    private fun ensureLegacyImported() {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(LEGACY_IMPORTED, false)) return
        runCatching {
            if (legacyCsv.exists() && legacyCsv.length() > 0L) {
                val legacy = ArrayList<PotholeReport>()
                BufferedReader(FileReader(legacyCsv)).useLines { lines ->
                    lines.drop(1).forEach { line -> parseLegacy(line)?.let(legacy::add) }
                }
                if (legacy.isNotEmpty()) dao.insertAll(legacy)
            }
        }
        prefs.edit().putBoolean(LEGACY_IMPORTED, true).apply()
    }

    private fun parseLegacy(line: String): PotholeReport? {
        val parsed = parseCsvLine(line)
        if (parsed.size < 17) return null
        return runCatching {
            PotholeReport(
                eventId = parsed[0],
                timestampMillis = parseTimestampMillis(parsed[1]),
                timestampIso = parsed[1],
                sessionId = parsed[2],
                trackId = parsed[3].toInt(),
                severity = parsed[4],
                scoreAi = parsed[5].toFloat(),
                rawScoreAi = parsed[6].toFloat(),
                maskAreaRatio = parsed[7].toFloat(),
                compensatedAreaRatio = parsed[8].toFloat(),
                perspectiveFactor = parsed[9].toFloat(),
                frameWidth = parsed[10].toInt(),
                frameHeight = parsed[11].toInt(),
                orientation = parsed[12],
                inferenceMs = parsed[13].toLong(),
                totalMs = parsed[14].toLong(),
                imageName = parsed[15],
                imageUri = parsed[16]
            )
        }.getOrNull()
    }

    private fun parseTimestampMillis(iso: String): Long = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", java.util.Locale.US)
            .parse(iso)?.time ?: 0L
    }.getOrDefault(0L)

    private fun toCsvLine(r: PotholeReport): String = listOf(
        r.eventId, r.timestampIso, r.sessionId, r.trackId.toString(), r.severity,
        "%.4f".format(java.util.Locale.US, r.scoreAi),
        "%.4f".format(java.util.Locale.US, r.rawScoreAi),
        "%.6f".format(java.util.Locale.US, r.maskAreaRatio),
        "%.6f".format(java.util.Locale.US, r.compensatedAreaRatio),
        "%.4f".format(java.util.Locale.US, r.perspectiveFactor),
        r.frameWidth.toString(), r.frameHeight.toString(), r.orientation,
        r.inferenceMs.toString(), r.totalMs.toString(), r.imageName, r.imageUri,
        r.detectionSource, r.distanceBand, r.modelInput.toString(), r.appVersion,
        r.firstSeenTimestampIso, r.confirmedTimestampIso, r.firstSeenSource,
        r.firstSeenDistanceBand, r.confirmedDistanceBand, r.confirmationDelayMs.toString(),
        "%.4f".format(java.util.Locale.US, r.calibrationHorizon),
        r.phoneHeightCm.toString(),
        r.latitude?.let { "%.7f".format(java.util.Locale.US, it) } ?: "",
        r.longitude?.let { "%.7f".format(java.util.Locale.US, it) } ?: "",
        r.locationAccuracyM?.let { "%.1f".format(java.util.Locale.US, it) } ?: "",
        r.locationTimestampMillis.toString(),
        r.locationAgeMs.toString(),
        r.locationProvider,
        r.placeName,
        r.addressLine,
        r.locality,
        r.subAdminArea,
        r.adminArea,
        r.countryName,
        r.maskAreaPx.toString(),
        "%.2f".format(java.util.Locale.US, r.centroidX),
        "%.2f".format(java.util.Locale.US, r.centroidY),
        r.originalImageName, r.originalImageUri,
        r.maskImageName, r.maskImageUri,
        r.overlayImageName, r.overlayImageUri
    ).joinToString(",") { escapeCsv(it) }

    private fun escapeCsv(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return value
        return "\"${value.replace("\"", "\"\"")}\""
    }

    private fun parseCsvLine(line: String): List<String> {
        val out = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    cell.append('"'); i++
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out.add(cell.toString()); cell.setLength(0) }
                else -> cell.append(c)
            }
            i++
        }
        out.add(cell.toString())
        return out
    }
}
