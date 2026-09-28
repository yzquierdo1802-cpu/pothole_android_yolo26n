from pathlib import Path
import hashlib
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
java = root/'app/src/main/java/com/pothole/v3'
res = root/'app/src/main/res'

required = [
    root/'app/src/main/AndroidManifest.xml',
    res/'layout/activity_main.xml',
    res/'layout/activity_reports.xml',
    res/'layout/activity_settings.xml',
    res/'layout/activity_calibration.xml',
    java/'MainActivity.kt', java/'YoloSegmentationEngine.kt', java/'DualRange.kt',
    java/'TemporalTracker.kt', java/'AppDatabase.kt', java/'ReportDao.kt',
    java/'ReportRepository.kt', java/'SettingsActivity.kt', java/'CalibrationActivity.kt',
    java/'AdaptivePerformanceController.kt', java/'LocationRecorder.kt', java/'PlaceResolver.kt',
    root/'app/src/main/assets/pothole_yolo26n_seg_416_int8.tflite',
    root/'app/src/main/assets/pothole_yolo26n_seg_512_int8.tflite',
]
for f in required:
    assert f.exists() and f.stat().st_size > 0, f"Falta: {f}"

for f in list(res.rglob('*.xml')) + [root/'app/src/main/AndroidManifest.xml']:
    ET.parse(f)

sha416 = hashlib.sha256((root/'app/src/main/assets/pothole_yolo26n_seg_416_int8.tflite').read_bytes()).hexdigest()
sha512 = hashlib.sha256((root/'app/src/main/assets/pothole_yolo26n_seg_512_int8.tflite').read_bytes()).hexdigest()
assert sha416 == '72ac2067cf6495b168f6a8d550c9d36b8abf4bb387f6e5fedd72ee7b65b09f93', sha416
assert sha512 == '9490fa2bdb5cb3cd17f09339df1ef4d5daac1f23ad2b0fe3d1f944119c434ee0', sha512

main = (java/'MainActivity.kt').read_text()
overlay = (java/'OverlayView.kt').read_text()
repo = (java/'ReportRepository.kt').read_text()
report_activity = (java/'ReportsActivity.kt').read_text()
adaptive = (java/'AdaptivePerformanceController.kt').read_text()
dual = (java/'DualRange.kt').read_text()
manifest = (root/'app/src/main/AndroidManifest.xml').read_text()
build = (root/'app/build.gradle.kts').read_text()
theme = (res/'values/themes.xml').read_text()

checks = {
    'version_v17': 'versionCode = 29' in build and '17.0-gps-georeport' in build,
    'room_dependency': 'androidx.room:room-runtime' in build and 'room-compiler' in build and 'org.jetbrains.kotlin.kapt' in build,
    'professional_ui': 'Baches PE' in (res/'layout/activity_main.xml').read_text() and 'Galería / Reportes' in (res/'layout/activity_reports.xml').read_text(),
    'report_thumbnails': 'itemThumbnail' in (res/'layout/item_report.xml').read_text() and 'bindThumbnail' in (java/'ReportAdapter.kt').read_text(),
    'room_source_of_truth': 'AppDatabase.get' in repo and 'writeCsvSnapshot' in repo,
    'primary_416': 'MODEL_416' in main and 'DetectionSource.PRIMARY_416' in main,
    'secondary_512': 'MODEL_512' in main and 'DetectionSource.FAR_512' in main,
    'separate_executors': 'primaryExecutor' in main and 'longRangeExecutor' in main and 'reportExecutor' in main,
    'no_frame_queue': 'STRATEGY_KEEP_ONLY_LATEST' in main and 'primaryBusy.compareAndSet(false, true)' in main,
    'instant_candidate': 'POSIBLE BACHE' in main and 'POSIBLE •' in overlay,
    'adaptive_profile': 'AdaptivePerformanceController' in main and 'averagePrimaryMs' in adaptive,
    'thermal_guard': 'thermal.allowSecondaryInference()' in main,
    'calibration': 'RoadCalibration' in dual and 'CalibrationActivity' in manifest,
    'optional_location': 'locationEnabled()' in main and 'ACCESS_FINE_LOCATION' in manifest,
    'no_internet_permission': 'android.permission.INTERNET' not in manifest,
    'full_hd_fallback': 'Size(1920, 1080)' in main and 'FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER' in main,
    'rotation': 'configChanges="orientation|screenSize|smallestScreenSize|keyboardHidden"' in manifest,
    'reports_search_filters': 'reportSearch' in (res/'layout/activity_reports.xml').read_text() and 'filterWeekButton' in report_activity,
    'mass_delete': 'selectAll' in report_activity and 'deleteByEventIds' in report_activity,
    'material3': 'Theme.Material3' in theme,
    'xnnpack_cpu': 'setUseXNNPACK(true)' in (java/'YoloSegmentationEngine.kt').read_text(),
    'no_gpu_delegate': 'GpuDelegate' not in '\n'.join(p.read_text(errors='ignore') for p in java.glob('*.kt')),
    'mask_cleanup': all(k in (java/'YoloSegmentationEngine.kt').read_text() for k in ['keepLargestComponent','majoritySmooth','openBinaryMask','fillInteriorHoles']),
    'scientific_vector_mask': 'extractScientificPolygon' in (java/'YoloSegmentationEngine.kt').read_text() and 'maskPolygon' in (java/'Detection.kt').read_text() and 'drawVectorMask' in overlay,
    'far_to_primary_handoff': 'isCrossSourceHandoff' in (java/'TemporalTracker.kt').read_text() and 'handoffFromFar' in overlay,
    'first_seen_telemetry': 'firstSeenTimestampMillis' in (java/'PotholeReport.kt').read_text() and 'confirmationDelayMs' in repo,
    'room_migration_v4': all(k in (java/'AppDatabase.kt').read_text() for k in ['MIGRATION_1_2','MIGRATION_2_3','MIGRATION_3_4','version = 4']),
    'scientific_evidence': 'saveScientificEvidence' in (java/'ReportMedia.kt').read_text() and 'originalImageUri' in (java/'PotholeReport.kt').read_text() and 'maskImageUri' in (java/'PotholeReport.kt').read_text(),
    'scientific_csv': 'mask_area_px' in (java/'ReportRepository.kt').read_text() and 'overlay_image_uri' in (java/'ReportRepository.kt').read_text(),
    'gps_place_storage': all(k in (java/'PotholeReport.kt').read_text() for k in ['placeName','addressLine','locationTimestampMillis','locationProvider']) and 'PlaceResolver' in main,
    'gps_ui_chip': 'gpsText' in main and 'gpsText' in (res/'layout/activity_main.xml').read_text(),
    'map_action': 'itemMapButton' in (res/'layout/item_report.xml').read_text() and 'openMap' in report_activity,
    'place_csv': 'place_name' in repo and 'address_line' in repo and 'location_provider' in repo,
    'model_integrity': True,
}
failed = [k for k, v in checks.items() if not v]
assert not failed, f"Fallaron: {failed}"

print('OK: V17 GPS GEOREPORT verificada estáticamente')
print('416 SHA256:', sha416)
print('512 SHA256:', sha512)
for k in checks:
    print(' -', k, 'OK')
