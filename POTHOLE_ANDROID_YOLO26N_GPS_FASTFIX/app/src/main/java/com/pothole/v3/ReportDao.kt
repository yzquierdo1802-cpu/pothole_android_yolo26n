package com.pothole.v3

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ReportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(report: PotholeReport)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(reports: List<PotholeReport>)

    @Query("SELECT COUNT(*) FROM pothole_reports")
    fun count(): Int

    @Query("SELECT * FROM pothole_reports ORDER BY timestampMillis DESC")
    fun readAll(): List<PotholeReport>

    @Query("SELECT * FROM pothole_reports ORDER BY timestampMillis DESC LIMIT :limit")
    fun readLatest(limit: Int): List<PotholeReport>

    @Query("SELECT * FROM pothole_reports WHERE eventId IN (:ids)")
    fun findByIds(ids: List<String>): List<PotholeReport>

    @Query("DELETE FROM pothole_reports WHERE eventId IN (:ids)")
    fun deleteByIds(ids: List<String>): Int

    @Query("SELECT * FROM pothole_reports WHERE latitude IS NOT NULL AND longitude IS NOT NULL AND timestampMillis >= :sinceMillis ORDER BY timestampMillis DESC LIMIT :limit")
    fun recentLocated(sinceMillis: Long, limit: Int): List<PotholeReport>

    @Query("DELETE FROM pothole_reports")
    fun deleteAll(): Int
}
