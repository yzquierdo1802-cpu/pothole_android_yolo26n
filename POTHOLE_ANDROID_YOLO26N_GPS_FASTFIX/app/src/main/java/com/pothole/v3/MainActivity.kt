package com.pothole.v3

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.os.Process
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * V17 GPS GEOREPORT
 *
 * Principio de diseño: la inferencia principal 416 nunca espera al análisis lejano,
 * al guardado de imágenes, a Room ni a la exportación. El primer candidato se dibuja
 * en el mismo frame como POSIBLE; sólo persistencia/CSV requieren confirmación temporal.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var controlPanel: View
    private lateinit var operationStatusText: TextView
    private lateinit var statsText: TextView
    private lateinit var fpsText: TextView
    private lateinit var gpsText: TextView
    private lateinit var severityText: TextView
    private lateinit var confText: TextView
    private lateinit var startAiButton: Button
    private lateinit var reportsButton: Button

    @Volatile private var primaryEngine: YoloSegmentationEngine? = null
    @Volatile private var longRangeEngine: YoloSegmentationEngine? = null
    @Volatile private var longRangeIs512 = false
    @Volatile private var aiReady = false
    @Volatile private var aiActive = false
    @Volatile private var conf = 0.28f
    @Volatile private var longRangeDescription = "ROI cargando..."

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val primaryExecutor = Executors.newSingleThreadExecutor()
    private val longRangeExecutor = Executors.newSingleThreadExecutor { task ->
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }.apply { name = "pothole-long-range" }
    }
    private val reportExecutor = Executors.newSingleThreadExecutor { task ->
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }.apply { name = "pothole-report-writer" }
    }

    private val primaryBusy = AtomicBoolean(false)
    private val secondaryBusy = AtomicBoolean(false)
    private val pendingLongRange = AtomicReference<LongRangeObservation?>(null)
    private val frameConverter = RgbaFrameConverter()
    private val roadRoiExtractor = RoadRangeRoiExtractor()
    private val tracker = TemporalTracker()
    private val performance = PerformanceMonitor(60)
    private lateinit var thermal: ThermalController
    private lateinit var reportRepository: ReportRepository
    private lateinit var config: ProfessionalConfig
    private lateinit var adaptive: AdaptivePerformanceController
    private lateinit var locationRecorder: LocationRecorder
    private lateinit var placeResolver: PlaceResolver

    private data class FirstSeenInfo(
        val timestampMillis: Long,
        val timestampIso: String,
        val source: DetectionSource,
        val distanceBand: String
    )

    private val capturedTrackIds = ConcurrentHashMap.newKeySet<Int>()
    private val firstSeenByTrack = ConcurrentHashMap<Int, FirstSeenInfo>()
    private val reportCount = AtomicInteger(0)
    private val sessionId: String by lazy {
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    }

    private var lastAcceptedFrameNs = 0L
    private var processedFrames = 0

    companion object {
        private const val MODEL_416 = "pothole_yolo26n_seg_416_int8.tflite"
        private const val MODEL_512 = "pothole_yolo26n_seg_512_int8.tflite"
        private const val FAR_CONFIDENCE = 0.26f
        private const val LONG_CONFIDENCE = 0.22f
        private const val ULTRA_CONFIDENCE = 0.20f
        private const val LONG_RESULT_MAX_AGE_NS = 450_000_000L
    }

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            previewView.post { startCamera() }
            if (!aiReady) initializeAi()
        } else operationStatusText.text = "Permiso de cámara denegado"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        config = ProfessionalConfig(applicationContext)
        thermal = ThermalController(applicationContext)
        reportRepository = ReportRepository(applicationContext)
        adaptive = AdaptivePerformanceController(applicationContext, config)
        locationRecorder = LocationRecorder(applicationContext)
        placeResolver = PlaceResolver(applicationContext)

        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        controlPanel = findViewById(R.id.controlPanel)
        operationStatusText = findViewById(R.id.operationStatusText)
        statsText = findViewById(R.id.statsText)
        fpsText = findViewById(R.id.fpsText)
        gpsText = findViewById(R.id.gpsText)
        severityText = findViewById(R.id.severityText)
        confText = findViewById(R.id.confText)
        startAiButton = findViewById(R.id.startAiButton)
        reportsButton = findViewById(R.id.reportsButton)

        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        roadRoiExtractor.setCalibration(config.calibration())
        SeverityEstimator.configure(config.calibration())
        applyResponsiveLayout()
        applyProfessionalPreferences()
        initializeReportCount()

        findViewById<SeekBar>(R.id.confSeek).apply {
            max = 60
            progress = 28
            confText.text = "Sensibilidad IA · 0.28"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                    conf = progress.coerceAtLeast(15) / 100f
                    confText.text = "Sensibilidad IA · %.2f".format(Locale.US, conf)
                }
                override fun onStartTrackingTouch(s: SeekBar?) = Unit
                override fun onStopTrackingTouch(s: SeekBar?) = Unit
            })
        }

        startAiButton.setOnClickListener {
            when {
                !aiReady -> initializeAi()
                aiActive -> pauseAi()
                else -> resumeAi()
            }
        }
        reportsButton.setOnClickListener { startActivity(Intent(this, ReportsActivity::class.java)) }
        findViewById<Button>(R.id.calibrateButton).setOnClickListener { startActivity(Intent(this, CalibrationActivity::class.java)) }
        findViewById<Button>(R.id.settingsButton).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        gpsText.setOnClickListener { handleGpsChipClick() }

        operationStatusText.text = "Cámara lista • IA preparada"
        severityText.text = "Baja 0 • Media 0 • Alta 0"

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            previewView.post { startCamera() }
            initializeAi()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        roadRoiExtractor.setCalibration(config.calibration())
        SeverityEstimator.configure(config.calibration())
        applyProfessionalPreferences()
        locationRecorder.startIfAllowed(config.locationEnabled())
        if (::reportRepository.isInitialized) initializeReportCount()
    }

    override fun onPause() {
        locationRecorder.stop()
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyResponsiveLayout()
        tracker.reset(resetIds = false)
        processedFrames = 0
        pendingLongRange.getAndSet(null)?.recycle()
        overlayView.clear()
        lastAcceptedFrameNs = 0L
        previewView.post { startCamera() }
    }

    private fun handleGpsChipClick() {
        when (locationRecorder.status().state) {
            LocationRecorder.State.DISABLED -> {
                Toast.makeText(this, "Activa GPS en Ajustes de Baches PE", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            LocationRecorder.State.NO_PERMISSION -> {
                Toast.makeText(this, "Concede ubicación precisa en Ajustes", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            LocationRecorder.State.LOCATION_OFF -> {
                Toast.makeText(this, "Activa la ubicación del teléfono", Toast.LENGTH_SHORT).show()
                runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            }
            LocationRecorder.State.SEARCHING, LocationRecorder.State.ERROR -> {
                locationRecorder.refresh()
                Toast.makeText(this, "Buscando una ubicación nueva…", Toast.LENGTH_SHORT).show()
            }
            LocationRecorder.State.READY -> {
                val s = locationRecorder.displaySnapshot()
                if (s != null) {
                    Toast.makeText(
                        this,
                        "GPS listo • ±${s.accuracyM.roundToInt()} m • ${s.provider}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun applyProfessionalPreferences() {
        statsText.visibility = if (config.diagnosticsEnabled()) View.VISIBLE else View.GONE
    }

    private fun applyResponsiveLayout() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val lp = controlPanel.layoutParams as FrameLayout.LayoutParams
        if (landscape) {
            val metrics = resources.displayMetrics
            val widthDp = metrics.widthPixels / metrics.density
            val fraction = when {
                widthDp >= 900f -> 0.32f
                widthDp >= 650f -> 0.38f
                else -> 0.50f
            }
            lp.width = (metrics.widthPixels * fraction).roundToInt()
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.START or Gravity.TOP
        } else {
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.height = FrameLayout.LayoutParams.WRAP_CONTENT
            lp.gravity = Gravity.TOP
        }
        controlPanel.layoutParams = lp
    }

    private fun initializeReportCount() {
        reportExecutor.execute {
            val count = runCatching { reportRepository.count() }.getOrDefault(0)
            reportCount.set(count)
            runOnUiThread { updateReportsButton(count) }
        }
    }

    private fun updateReportsButton(count: Int = reportCount.get()) {
        reportsButton.text = if (count > 0) "Galería ($count)" else "Galería"
    }

    /** Carga primero 416 y habilita detección; 512 se carga después en paralelo. */
    private fun initializeAi() {
        if (primaryEngine != null) return
        startAiButton.isEnabled = false
        startAiButton.text = "Cargando…"
        operationStatusText.text = "Cargando detector rápido 416..."

        primaryExecutor.execute {
            try {
                val loaded = YoloSegmentationEngine(
                    applicationContext,
                    MODEL_416,
                    DetectionSource.PRIMARY_416,
                    threadLimit = null
                )
                primaryEngine = loaded
                aiReady = true
                aiActive = true
                tracker.reset()
                capturedTrackIds.clear()
                performance.reset()
                processedFrames = 0
                runOnUiThread {
                    operationStatusText.text = "ACTIVO • detección inmediata 416"
                    startAiButton.isEnabled = true
                    startAiButton.text = "Pausar"
                }
                initializeLongRangeEngine()
            } catch (t: Throwable) {
                t.printStackTrace()
                primaryEngine = null
                aiReady = false
                aiActive = false
                runOnUiThread {
                    operationStatusText.text = "Error IA: ${t.javaClass.simpleName}: ${t.message}"
                    startAiButton.isEnabled = true
                    startAiButton.text = "Reintentar"
                }
            }
        }
    }

    private fun initializeLongRangeEngine() {
        if (longRangeEngine != null) return
        longRangeExecutor.execute {
            // 512 se usa sólo cuando el hardware tiene margen. En equipos modestos se
            // carga un segundo 416 para no degradar la ruta primaria.
            val engine512 = if (adaptive.canUse512ByHardware()) runCatching {
                YoloSegmentationEngine(applicationContext, MODEL_512, DetectionSource.FAR_512, threadLimit = 2)
            }.getOrNull() else null
            if (engine512 != null) {
                longRangeEngine = engine512
                longRangeIs512 = true
                longRangeDescription = "ROI 512 listo"
            } else {
                longRangeEngine = runCatching {
                    YoloSegmentationEngine(applicationContext, MODEL_416, DetectionSource.FAR_416, threadLimit = 2)
                }.getOrNull()
                longRangeIs512 = false
                longRangeDescription = if (longRangeEngine != null) "ROI 416 adaptativo" else "ROI secundario no disponible"
            }
        }
    }

    private fun pauseAi() {
        aiActive = false
        tracker.reset()
        capturedTrackIds.clear()
        performance.reset()
        processedFrames = 0
        pendingLongRange.getAndSet(null)?.recycle()
        overlayView.clear()
        startAiButton.text = "Reanudar"
        operationStatusText.text = "Modelo pausado"
        fpsText.text = "IA 0.0 FPS"
    }

    private fun resumeAi() {
        if (!aiReady) return
        aiActive = true
        lastAcceptedFrameNs = 0L
        tracker.reset()
        capturedTrackIds.clear()
        performance.reset()
        processedFrames = 0
        pendingLongRange.getAndSet(null)?.recycle()
        startAiButton.text = "Pausar"
        operationStatusText.text = "ACTIVO • detección inmediata 416"
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
                val resolutionSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_16_9, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                    .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build()

                val preview = Preview.Builder()
                    .setTargetRotation(rotation)
                    .setResolutionSelector(resolutionSelector)
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }

                val analysis = ImageAnalysis.Builder()
                    .setTargetRotation(rotation)
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setOutputImageRotationEnabled(true)
                    .build()

                analysis.setAnalyzer(cameraExecutor) { image ->
                    if (!aiReady || !aiActive) {
                        image.close(); return@setAnalyzer
                    }
                    val now = System.nanoTime()
                    val thermalInterval = thermal.minFrameIntervalNs(now)
                    if (thermalInterval > 0L && now - lastAcceptedFrameNs < thermalInterval) {
                        image.close(); return@setAnalyzer
                    }
                    // Nunca formamos cola de frames: si 416 está ocupado, se descarta el antiguo.
                    if (!primaryBusy.compareAndSet(false, true)) {
                        image.close(); return@setAnalyzer
                    }
                    lastAcceptedFrameNs = now

                    val conversionStart = System.nanoTime()
                    val frame = try {
                        frameConverter.convert(image)
                    } catch (t: Throwable) {
                        primaryBusy.set(false)
                        runOnUiThread { operationStatusText.text = "Error cámara: ${t.message}" }
                        null
                    } finally {
                        image.close()
                    }
                    if (frame == null) return@setAnalyzer
                    val cameraMs = (System.nanoTime() - conversionStart) / 1_000_000L

                    try {
                        primaryExecutor.execute {
                            try {
                                processPrimaryFrame(frame, cameraMs)
                            } catch (t: Throwable) {
                                t.printStackTrace()
                                runOnUiThread { operationStatusText.text = "Error inferencia: ${t.javaClass.simpleName}: ${t.message}" }
                            } finally {
                                primaryBusy.set(false)
                            }
                        }
                    } catch (t: Throwable) {
                        primaryBusy.set(false)
                    }
                }

                provider.unbindAll()
                val selector = when {
                    provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> error("No se encontró una cámara compatible")
                }
                val viewPort = previewView.viewPort
                if (viewPort != null) {
                    provider.bindToLifecycle(
                        this,
                        selector,
                        UseCaseGroup.Builder().addUseCase(preview).addUseCase(analysis).setViewPort(viewPort).build()
                    )
                } else {
                    provider.bindToLifecycle(this, selector, preview, analysis)
                }
            } catch (t: Throwable) {
                t.printStackTrace()
                operationStatusText.text = "Error cámara: ${t.javaClass.simpleName}: ${t.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processPrimaryFrame(frame: RgbaFrame, cameraMs: Long) {
        val engine = primaryEngine ?: return
        processedFrames++
        val base = engine.infer(frame, conf, PreprocessQuality.FAST)
        adaptive.observePrimary(base.metrics.modelMs)

        var result = base
        var consumedRange = "PRIMARIO"
        val observation = pendingLongRange.getAndSet(null)
        if (observation != null) {
            val age = System.nanoTime() - observation.capturedAtNs
            if (age in 0..LONG_RESULT_MAX_AGE_NS && observation.fullWidth == frame.width && observation.fullHeight == frame.height) {
                result = DualRangeFusion.combine(
                    base = base,
                    secondary = observation.result,
                    roi = observation.roi,
                    fullWidth = frame.width,
                    fullHeight = frame.height,
                    source = observation.source,
                    distanceBand = observation.band.distanceBand
                )
                consumedRange = "${observation.band.label} ${observation.band.distanceBand}${observation.sweepLabel} x${"%.1f".format(Locale.US, observation.zoomGain)}"
                // La máscara copiada pasa a result.extraMaskLayers y será reciclada al reemplazarse
                // por un nuevo resultado sólo si no se consume. Aquí el overlay la usa de inmediato.
            } else {
                observation.recycle()
            }
        }

        val trackingStart = System.nanoTime()
        val tracked = tracker.update(result.detections, result.sourceWidth, result.sourceHeight)
        val trackingMs = (System.nanoTime() - trackingStart) / 1_000_000L

        // Marca el instante REAL de primera observación. El mismo ID se conserva durante
        // el handoff 512 -> 416, por lo que luego podemos medir anticipación y tiempo
        // hasta confirmación sin retrasar el camino de inferencia.
        if (tracked.isNotEmpty()) {
            val firstSeenNow = System.currentTimeMillis()
            tracked.asSequence()
                .filter { !it.predicted && it.trackId > 0 }
                .forEach { d ->
                    firstSeenByTrack.putIfAbsent(
                        d.trackId,
                        FirstSeenInfo(
                            timestampMillis = firstSeenNow,
                            timestampIso = ReportMedia.timestampIso(firstSeenNow),
                            source = d.firstSeenSource,
                            distanceBand = d.firstSeenDistanceBand
                        )
                    )
                }
        }

        val trackedResult = result.copy(detections = tracked)
        val pipelineTotal = cameraMs + base.metrics.totalMs + trackingMs
        val perf = performance.record(pipelineTotal)

        queueNewReports(frame, trackedResult, pipelineTotal)
        scheduleLongRange(frame, processedFrames)

        runOnUiThread {
            if (!aiActive || isFinishing || isDestroyed) return@runOnUiThread
            overlayView.setResults(trackedResult)
            val visible = tracked.filterNot { it.predicted }
            val confirmed = visible.filter { it.trackConfirmed }
            val low = confirmed.count { it.severity == Severity.LOW }
            val medium = confirmed.count { it.severity == Severity.MEDIUM }
            val high = confirmed.count { it.severity == Severity.HIGH }
            val best = visible.maxOfOrNull { it.confidence } ?: 0f
            val policy = adaptive.policy(thermal.allowSecondaryInference())

            operationStatusText.text = when {
                confirmed.any { it.handoffFromFar } -> "BACHE CONFIRMADO • APROXIMÁNDOSE • ${confirmed.size} • $consumedRange"
                confirmed.any { it.source != DetectionSource.PRIMARY_416 } -> "ALERTA TEMPRANA • BACHE LEJANO CONFIRMADO • $consumedRange"
                confirmed.isNotEmpty() -> "BACHE DETECTADO • ${confirmed.size} confirmado(s) • $consumedRange"
                visible.any { it.source != DetectionSource.PRIMARY_416 } -> "ALERTA TEMPRANA • POSIBLE BACHE LEJANO • $consumedRange"
                visible.isNotEmpty() -> "POSIBLE BACHE • verificando • $consumedRange"
                else -> "ACTIVO • buscando • ${policy.label} • $longRangeDescription"
            }
            severityText.text = "Baja $low • Media $medium • Alta $high"
            fpsText.text = "IA %.1f FPS".format(Locale.US, perf.fps)
            val gpsStatus = locationRecorder.status()
            val gps = gpsStatus.snapshot
            gpsText.text = when (gpsStatus.state) {
                LocationRecorder.State.DISABLED -> "GPS OFF"
                LocationRecorder.State.NO_PERMISSION -> "GPS SIN PERMISO"
                LocationRecorder.State.LOCATION_OFF -> "GPS APAGADO"
                LocationRecorder.State.SEARCHING -> "GPS buscando…"
                LocationRecorder.State.ERROR -> "GPS ERROR"
                LocationRecorder.State.READY -> "GPS ±${gps?.accuracyM?.roundToInt() ?: 0} m"
            }
            gpsText.setTextColor(
                if (gpsStatus.state == LocationRecorder.State.READY) ContextCompat.getColor(this, R.color.accent_cyan)
                else ContextCompat.getColor(this, R.color.text_secondary)
            )
            statsText.text = buildString {
                append("Visibles ").append(visible.size).append(" • Confirmados ").append(confirmed.size)
                append(" • Mejor ").append("%.1f".format(Locale.US, best * 100f)).append("%\n")
                append("Cam ").append(cameraMs).append(" ms • Pre ").append(base.metrics.preprocessMs)
                append(" • IA416 ").append(base.metrics.modelMs).append(" • Post ").append(base.metrics.postprocessMs)
                append(" • Total ").append(pipelineTotal).append(" ms\n")
                append("Prom ").append(perf.averageTotalMs).append(" • p95 ").append(perf.p95TotalMs)
                append(" • ").append(thermal.label()).append(" • ").append(policy.label).append("\n")
                append("Cam ").append(frame.width).append('x').append(frame.height)
                append(" • ").append(adaptive.deviceSummary()).append(" • ").append(longRangeDescription)
            }
        }
    }

    /**
     * Programa ROI 5-20 m sin bloquear el executor principal. La pasada 512 usa sólo
     * dos threads y prioridad de background para conservar la respuesta 416.
     */
    private fun scheduleLongRange(frame: RgbaFrame, frameIndex: Int) {
        val engine = longRangeEngine ?: return
        val policy = adaptive.policy(thermal.allowSecondaryInference())
        if (policy.secondaryIntervalFrames == Int.MAX_VALUE) return
        if (frameIndex % policy.secondaryIntervalFrames != 0) return
        if (!secondaryBusy.compareAndSet(false, true)) return

        val secondaryIndex = frameIndex / policy.secondaryIntervalFrames
        val band = when (secondaryIndex % 3) {
            1 -> RangeBand.LONG
            else -> RangeBand.ULTRA
        }
        // V13 mantiene continuidad temporal sobre barridos laterales: el centro de
        // carretera proviene de la calibración y el ROI ULTRA es algo más ancho.
        val ultraShift = 0f
        val roi = roadRoiExtractor.extract(frame, band, ultraShift)
        if (roi == null) {
            secondaryBusy.set(false); return
        }
        val capturedNs = System.nanoTime()
        val sweep = if (band == RangeBand.ULTRA) " CENTRO" else ""
        val threshold = when (band) {
            RangeBand.FAR -> minOf(conf, FAR_CONFIDENCE)
            RangeBand.LONG -> minOf(conf, LONG_CONFIDENCE)
            RangeBand.ULTRA -> minOf(conf, ULTRA_CONFIDENCE)
        }
        val quality = when {
            band == RangeBand.FAR -> PreprocessQuality.DETAIL
            policy.prefer512 -> PreprocessQuality.DETAIL_ENHANCED
            else -> PreprocessQuality.DETAIL
        }
        val source = if (longRangeIs512) DetectionSource.FAR_512 else DetectionSource.FAR_416

        longRangeExecutor.execute {
            try {
                val secondary = engine.infer(roi.frame, threshold, quality)
                val detached = DualRangeFusion.detachedCopy(secondary)
                val observation = LongRangeObservation(
                    result = detached,
                    roi = roi.rect,
                    band = band,
                    zoomGain = roi.zoomGain,
                    sweepLabel = sweep,
                    capturedAtNs = capturedNs,
                    fullWidth = frame.width,
                    fullHeight = frame.height,
                    source = source
                )
                pendingLongRange.getAndSet(observation)?.recycle()
            } catch (t: Throwable) {
                t.printStackTrace()
            } finally {
                secondaryBusy.set(false)
            }
        }
    }

    private fun queueNewReports(frame: RgbaFrame, result: InferenceResult, pipelineTotalMs: Long) {
        val newlyConfirmed = result.detections.filter {
            !it.predicted && it.trackConfirmed && it.trackId > 0 && capturedTrackIds.add(it.trackId)
        }
        if (newlyConfirmed.isEmpty()) return

        // Sólo se copia el frame para persistencia. La evidencia científica V15 usa
        // el contorno vectorial de cada detección y ya no necesita duplicar bitmaps de máscara.
        val framePixels = frame.pixels.copyOf()
        val timestamp = System.currentTimeMillis()
        val orientation = if (frame.width >= frame.height) "HORIZONTAL" else "VERTICAL"
        val cal = config.calibration()
        val location = locationRecorder.snapshot(maxAgeMs = 6_000L)

        reportExecutor.execute {
            try {
                for (d in newlyConfirmed) {
                    if (location != null && reportRepository.hasRecentNearby(
                            location.latitude, location.longitude, location.accuracyM, sessionId
                        )
                    ) {
                        // Mismo punto reciente con GPS suficientemente preciso: evita
                        // duplicar inventario al pasar de nuevo durante otra sesión.
                        firstSeenByTrack.remove(d.trackId)
                        continue
                    }
                    val place = if (location != null) {
                        placeResolver.resolve(location.latitude, location.longitude)
                    } else {
                        PlaceResolver.Place()
                    }
                    val evidence = ReportMedia.saveScientificEvidence(
                        applicationContext,
                        framePixels,
                        frame.width,
                        frame.height,
                        d,
                        timestamp
                    )
                    val firstSeen = firstSeenByTrack[d.trackId] ?: FirstSeenInfo(
                        timestampMillis = timestamp,
                        timestampIso = ReportMedia.timestampIso(timestamp),
                        source = d.firstSeenSource,
                        distanceBand = d.firstSeenDistanceBand
                    )
                    val confirmedIso = ReportMedia.timestampIso(timestamp)
                    val report = PotholeReport(
                        eventId = "${sessionId}_${d.trackId}_$timestamp",
                        timestampMillis = timestamp,
                        timestampIso = ReportMedia.timestampIso(timestamp),
                        sessionId = sessionId,
                        trackId = d.trackId,
                        severity = d.severity.label,
                        scoreAi = d.confidence,
                        rawScoreAi = d.rawConfidence,
                        maskAreaRatio = d.maskAreaRatio,
                        compensatedAreaRatio = d.severityScore,
                        perspectiveFactor = d.perspectiveFactor,
                        frameWidth = frame.width,
                        frameHeight = frame.height,
                        orientation = orientation,
                        inferenceMs = result.metrics.modelMs,
                        totalMs = pipelineTotalMs,
                        imageName = evidence.overlay.name,
                        imageUri = evidence.overlay.uri.toString(),
                        detectionSource = d.source.label,
                        distanceBand = d.distanceBand,
                        modelInput = d.source.modelInput,
                        firstSeenTimestampMillis = firstSeen.timestampMillis,
                        firstSeenTimestampIso = firstSeen.timestampIso,
                        confirmedTimestampMillis = timestamp,
                        confirmedTimestampIso = confirmedIso,
                        firstSeenSource = firstSeen.source.label,
                        firstSeenDistanceBand = firstSeen.distanceBand,
                        confirmedDistanceBand = d.distanceBand,
                        confirmationDelayMs = (timestamp - firstSeen.timestampMillis).coerceAtLeast(0L),
                        calibrationHorizon = cal.horizonFraction,
                        phoneHeightCm = cal.phoneHeightCm,
                        latitude = location?.latitude,
                        longitude = location?.longitude,
                        locationAccuracyM = location?.accuracyM,
                        locationTimestampMillis = location?.timestampMillis ?: 0L,
                        locationAgeMs = location?.ageMs ?: 0L,
                        locationProvider = location?.provider.orEmpty(),
                        placeName = place.placeName,
                        addressLine = place.addressLine,
                        locality = place.locality,
                        subAdminArea = place.subAdminArea,
                        adminArea = place.adminArea,
                        countryName = place.countryName,
                        maskAreaPx = (d.maskAreaRatio * frame.width.toFloat() * frame.height.toFloat()).toInt().coerceAtLeast(0),
                        centroidX = if (d.maskPolygon.isNotEmpty()) d.maskPolygon.map { it.x }.average().toFloat() else d.box.centerX(),
                        centroidY = if (d.maskPolygon.isNotEmpty()) d.maskPolygon.map { it.y }.average().toFloat() else d.box.centerY(),
                        originalImageName = evidence.original.name,
                        originalImageUri = evidence.original.uri.toString(),
                        maskImageName = evidence.mask.name,
                        maskImageUri = evidence.mask.uri.toString(),
                        overlayImageName = evidence.overlay.name,
                        overlayImageUri = evidence.overlay.uri.toString()
                    )
                    reportRepository.append(report)
                    firstSeenByTrack.remove(d.trackId)
                    val count = reportCount.incrementAndGet()
                    runOnUiThread { updateReportsButton(count) }
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            } finally {
                // El frame local queda liberado por GC tras finalizar esta tarea.
            }
        }
    }

    override fun onDestroy() {
        aiActive = false
        aiReady = false
        locationRecorder.stop()
        pendingLongRange.getAndSet(null)?.recycle()
        firstSeenByTrack.clear()
        cameraExecutor.shutdownNow()
        primaryExecutor.shutdown()
        longRangeExecutor.shutdown()
        reportExecutor.shutdown()
        val primaryStopped = try { primaryExecutor.awaitTermination(2500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
        val secondaryStopped = try { longRangeExecutor.awaitTermination(2500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
        try { reportExecutor.awaitTermination(1800, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
        // No se cierra un Interpreter mientras todavía puede estar ejecutando código nativo.
        if (primaryStopped) runCatching { primaryEngine?.close() }
        if (secondaryStopped) runCatching { longRangeEngine?.close() }
        primaryEngine = null
        longRangeEngine = null
        runCatching { frameConverter.close() }
        runCatching { roadRoiExtractor.close() }
        super.onDestroy()
    }
}
