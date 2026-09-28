package com.pothole.v3

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Fuente de verdad persistente de una detección confirmada.
 * Room es el almacenamiento primario; CSV es únicamente un formato de exportación.
 */
@Entity(tableName = "pothole_reports")
data class PotholeReport(
    @PrimaryKey val eventId: String,
    val timestampMillis: Long,
    val timestampIso: String,
    val sessionId: String,
    val trackId: Int,
    val severity: String,
    val scoreAi: Float,
    val rawScoreAi: Float,
    val maskAreaRatio: Float,
    val compensatedAreaRatio: Float,
    val perspectiveFactor: Float,
    val frameWidth: Int,
    val frameHeight: Int,
    val orientation: String,
    val inferenceMs: Long,
    val totalMs: Long,
    val imageName: String,
    val imageUri: String,
    val detectionSource: String = "PRIMARY_416",
    val distanceBand: String = "GENERAL",
    val modelInput: Int = 416,
    val appVersion: String = "17.0-gps-georeport",
    @ColumnInfo(defaultValue = "0") val firstSeenTimestampMillis: Long = 0L,
    @ColumnInfo(defaultValue = "''") val firstSeenTimestampIso: String = "",
    @ColumnInfo(defaultValue = "0") val confirmedTimestampMillis: Long = 0L,
    @ColumnInfo(defaultValue = "''") val confirmedTimestampIso: String = "",
    @ColumnInfo(defaultValue = "'UNKNOWN'") val firstSeenSource: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "'UNKNOWN'") val firstSeenDistanceBand: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "'UNKNOWN'") val confirmedDistanceBand: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "0") val confirmationDelayMs: Long = 0L,
    val calibrationHorizon: Float = 0.32f,
    val phoneHeightCm: Int = 120,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAccuracyM: Float? = null,
    @ColumnInfo(defaultValue = "0") val locationTimestampMillis: Long = 0L,
    @ColumnInfo(defaultValue = "0") val locationAgeMs: Long = 0L,
    @ColumnInfo(defaultValue = "''") val locationProvider: String = "",
    @ColumnInfo(defaultValue = "''") val placeName: String = "",
    @ColumnInfo(defaultValue = "''") val addressLine: String = "",
    @ColumnInfo(defaultValue = "''") val locality: String = "",
    @ColumnInfo(defaultValue = "''") val subAdminArea: String = "",
    @ColumnInfo(defaultValue = "''") val adminArea: String = "",
    @ColumnInfo(defaultValue = "''") val countryName: String = "",
    @ColumnInfo(defaultValue = "0") val maskAreaPx: Int = 0,
    @ColumnInfo(defaultValue = "0") val centroidX: Float = 0f,
    @ColumnInfo(defaultValue = "0") val centroidY: Float = 0f,
    @ColumnInfo(defaultValue = "''") val originalImageName: String = "",
    @ColumnInfo(defaultValue = "''") val originalImageUri: String = "",
    @ColumnInfo(defaultValue = "''") val maskImageName: String = "",
    @ColumnInfo(defaultValue = "''") val maskImageUri: String = "",
    @ColumnInfo(defaultValue = "''") val overlayImageName: String = "",
    @ColumnInfo(defaultValue = "''") val overlayImageUri: String = ""
)
