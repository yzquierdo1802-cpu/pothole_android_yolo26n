package com.pothole.v3

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

class ReportsActivity : AppCompatActivity() {
    private lateinit var repository: ReportRepository
    private lateinit var adapter: ReportAdapter
    private lateinit var summary: TextView
    private lateinit var deleteSelectedButton: Button
    private val ioExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var allReports: List<PotholeReport> = emptyList()
    private var visibleReports: List<PotholeReport> = emptyList()
    private var severityFilter: String? = null
    private var timeFilterMs: Long? = null
    private var query: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reports)

        repository = ReportRepository(applicationContext)
        summary = findViewById(R.id.reportsSummary)
        deleteSelectedButton = findViewById(R.id.deleteSelectedButton)
        adapter = ReportAdapter(this, emptyList(), ::openImage, ::openMap, ::confirmDeleteOne, ::updateSelectionUi)
        findViewById<ListView>(R.id.reportsList).adapter = adapter

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.selectAllButton).setOnClickListener { adapter.selectAll() }
        findViewById<Button>(R.id.clearSelectionButton).setOnClickListener { adapter.clearSelection() }
        deleteSelectedButton.setOnClickListener { confirmDeleteSelected() }
        findViewById<Button>(R.id.exportCsvButton).setOnClickListener { exportCsv() }
        findViewById<Button>(R.id.shareCsvButton).setOnClickListener { shareCsv() }

        findViewById<Button>(R.id.filterAllButton).setOnClickListener { severityFilter = null; timeFilterMs = null; applyFilters() }
        findViewById<Button>(R.id.filterLowButton).setOnClickListener { severityFilter = Severity.LOW.label; applyFilters() }
        findViewById<Button>(R.id.filterMediumButton).setOnClickListener { severityFilter = Severity.MEDIUM.label; applyFilters() }
        findViewById<Button>(R.id.filterHighButton).setOnClickListener { severityFilter = Severity.HIGH.label; applyFilters() }
        findViewById<Button>(R.id.filterTodayButton).setOnClickListener {
            timeFilterMs = startOfToday(); applyFilters()
        }
        findViewById<Button>(R.id.filterWeekButton).setOnClickListener {
            timeFilterMs = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L; applyFilters()
        }

        findViewById<EditText>(R.id.reportSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString().orEmpty().trim().lowercase(Locale.getDefault())
                applyFilters()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        updateSelectionUi(0)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        ioExecutor.execute {
            val all = runCatching { repository.readAll() }.getOrDefault(emptyList())
            runOnUiThread {
                allReports = all
                applyFilters()
            }
        }
    }

    private fun applyFilters() {
        val sev = severityFilter
        val since = timeFilterMs
        val q = query
        visibleReports = allReports.filter { r ->
            val severityOk = sev == null || r.severity == sev
            val timeOk = since == null || r.timestampMillis >= since
            val queryOk = q.isBlank() || buildString {
                append(r.eventId).append(' ')
                append(r.sessionId).append(' ')
                append(r.trackId).append(' ')
                append(r.timestampIso).append(' ')
                append(r.detectionSource).append(' ')
                append(r.distanceBand).append(' ')
                append(r.severity).append(' ')
                append(r.placeName).append(' ')
                append(r.addressLine).append(' ')
                append(r.locality).append(' ')
                append(r.subAdminArea).append(' ')
                append(r.adminArea).append(' ')
                append(r.countryName).append(' ')
                append(r.latitude ?: "").append(' ')
                append(r.longitude ?: "")
            }.lowercase(Locale.getDefault()).contains(q)
            severityOk && timeOk && queryOk
        }
        adapter.submit(visibleReports)
        updateSummary()
    }

    private fun updateSummary() {
        val low = allReports.count { it.severity == Severity.LOW.label }
        val medium = allReports.count { it.severity == Severity.MEDIUM.label }
        val high = allReports.count { it.severity == Severity.HIGH.label }
        summary.text = "Total ${allReports.size} • Baja $low • Media $medium • Alta $high • Mostrados ${visibleReports.size} • Seleccionados ${adapter.selectedCount()}"
    }

    private fun updateSelectionUi(selected: Int) {
        deleteSelectedButton.text = "Eliminar ($selected)"
        deleteSelectedButton.isEnabled = selected > 0
        if (::summary.isInitialized && ::adapter.isInitialized) updateSummary()
    }

    private fun confirmDeleteOne(report: PotholeReport) {
        AlertDialog.Builder(this)
            .setTitle("Eliminar registro")
            .setMessage("Se eliminarán el registro y su imagen asociada. ¿Continuar?")
            .setNegativeButton("CANCELAR", null)
            .setPositiveButton("ELIMINAR") { _, _ -> deleteEvents(setOf(report.eventId)) }
            .show()
    }

    private fun confirmDeleteSelected() {
        val ids = adapter.selectedEventIds()
        if (ids.isEmpty()) return
        val allVisibleSelected = ids.size == visibleReports.size && visibleReports.isNotEmpty()
        val message = if (allVisibleSelected) {
            "Se eliminarán los ${ids.size} registros actualmente seleccionados y sus imágenes. Esta acción no se puede deshacer."
        } else {
            "Se eliminarán ${ids.size} registros y sus imágenes asociadas. ¿Continuar?"
        }
        AlertDialog.Builder(this)
            .setTitle("Eliminar seleccionados")
            .setMessage(message)
            .setNegativeButton("CANCELAR", null)
            .setPositiveButton("ELIMINAR") { _, _ -> deleteEvents(ids) }
            .show()
    }

    private fun deleteEvents(ids: Set<String>) {
        if (ids.isEmpty()) return
        deleteSelectedButton.isEnabled = false
        ioExecutor.execute {
            val deleted = runCatching { repository.deleteByEventIds(ids) }.getOrElse { t ->
                runOnUiThread {
                    Toast.makeText(this, t.message ?: "No se pudieron eliminar los registros", Toast.LENGTH_LONG).show()
                    deleteSelectedButton.isEnabled = true
                }
                return@execute
            }
            var deletedImages = 0
            deleted.forEach { if (ReportMedia.deleteImage(applicationContext, it)) deletedImages++ }
            val all = runCatching { repository.readAll() }.getOrDefault(emptyList())
            runOnUiThread {
                allReports = all
                adapter.clearSelection()
                applyFilters()
                Toast.makeText(this, "Eliminados ${deleted.size} registros • $deletedImages imágenes", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exportCsv() {
        ioExecutor.execute {
            val result = runCatching {
                val snapshot = repository.writeCsvSnapshot()
                ReportMedia.exportCsvToDownloads(this, snapshot)
            }
            runOnUiThread {
                result.onSuccess { Toast.makeText(this, "CSV exportado", Toast.LENGTH_LONG).show() }
                    .onFailure { Toast.makeText(this, it.message ?: "No se pudo exportar CSV", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun shareCsv() {
        ioExecutor.execute {
            val result = runCatching {
                require(allReports.isNotEmpty()) { "Aún no hay reportes" }
                repository.writeCsvSnapshot()
            }
            runOnUiThread {
                result.onSuccess { file ->
                    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Compartir reporte CSV"))
                }.onFailure { Toast.makeText(this, it.message ?: "No se pudo compartir", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun openMap(report: PotholeReport) {
        val lat = report.latitude
        val lon = report.longitude
        if (lat == null || lon == null) {
            Toast.makeText(
                this,
                "Este reporte no tiene coordenadas GPS. Fue registrado antes de obtener una ubicación válida.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val label = report.placeName.ifBlank { "Bache #${report.trackId}" }

        // 1) App de mapas instalada (Google Maps, Waze u otra que acepte geo:).
        val geoUri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(label)})")
        val geoIntent = Intent(Intent.ACTION_VIEW, geoUri)
        val geoOpened = runCatching { startActivity(geoIntent) }.isSuccess
        if (geoOpened) return

        // 2) Fallback universal al navegador. No requiere permiso INTERNET de esta app,
        // porque la URL la abre el navegador externo.
        val webUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lon")
        val webIntent = Intent(Intent.ACTION_VIEW, webUri)
        runCatching { startActivity(webIntent) }.onFailure {
            Toast.makeText(
                this,
                "No hay una aplicación compatible para abrir el mapa. Coordenadas: %.6f, %.6f".format(Locale.US, lat, lon),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun openImage(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, "No se pudo abrir la imagen", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startOfToday(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    override fun onDestroy() {
        if (::adapter.isInitialized) adapter.shutdown()
        ioExecutor.shutdownNow()
        super.onDestroy()
    }
}
