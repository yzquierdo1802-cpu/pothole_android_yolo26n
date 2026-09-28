package com.pothole.v3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.util.LruCache
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class ReportAdapter(
    private val context: Context,
    private var items: List<PotholeReport>,
    private val onOpenImage: (Uri) -> Unit,
    private val onOpenMap: (PotholeReport) -> Unit,
    private val onDeleteOne: (PotholeReport) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : BaseAdapter() {

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
    private val selectedIds = LinkedHashSet<String>()
    private val thumbnailExecutor = Executors.newFixedThreadPool(2)
    private val thumbnailCache = object : LruCache<String, Bitmap>(12) {}

    fun submit(newItems: List<PotholeReport>) {
        items = newItems
        selectedIds.retainAll(newItems.map { it.eventId }.toSet())
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size)
    }

    fun selectAll() {
        selectedIds.clear()
        items.forEach { selectedIds.add(it.eventId) }
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size)
    }

    fun clearSelection() {
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun selectedEventIds(): Set<String> = selectedIds.toSet()
    fun selectedCount(): Int = selectedIds.size

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): PotholeReport = items[position]
    override fun getItemId(position: Int): Long = getItem(position).eventId.hashCode().toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_report, parent, false)
        val item = getItem(position)
        val check = view.findViewById<CheckBox>(R.id.itemCheck)
        val severity = view.findViewById<TextView>(R.id.itemSeverity)
        val title = view.findViewById<TextView>(R.id.itemTitle)
        val details = view.findViewById<TextView>(R.id.itemDetails)
        val imageButton = view.findViewById<Button>(R.id.itemImageButton)
        val maskButton = view.findViewById<Button>(R.id.itemMaskButton)
        val originalButton = view.findViewById<Button>(R.id.itemOriginalButton)
        val mapButton = view.findViewById<Button>(R.id.itemMapButton)
        val deleteButton = view.findViewById<Button>(R.id.itemDeleteButton)
        val thumbnail = view.findViewById<ImageView>(R.id.itemThumbnail)

        check.setOnCheckedChangeListener(null)
        check.isChecked = item.eventId in selectedIds
        check.setOnCheckedChangeListener { _, checked ->
            if (checked) selectedIds.add(item.eventId) else selectedIds.remove(item.eventId)
            onSelectionChanged(selectedIds.size)
        }

        severity.text = item.severity
        severity.setTextColor(
            when (item.severity) {
                Severity.HIGH.label -> Color.rgb(255, 82, 82)
                Severity.MEDIUM.label -> Color.rgb(210, 155, 0)
                else -> Color.rgb(0, 170, 90)
            }
        )
        val time = if (item.timestampMillis > 0L) dateFormat.format(Date(item.timestampMillis)) else item.timestampIso
        title.text = "$time • ID #${item.trackId}"
        details.text = buildString {
            append("Score IA: ").append("%.1f".format(Locale.US, item.scoreAi * 100f)).append("%")
            append(" • Área: ").append("%.3f".format(Locale.US, item.maskAreaRatio * 100f)).append("%")
            if (item.maskAreaPx > 0) append(" • ~").append(item.maskAreaPx).append(" px")
            append("\nIA: ").append(item.inferenceMs).append(" ms")
            append(" • Total: ").append(item.totalMs).append(" ms")
            append(" • ").append(item.orientation)
            append("\n").append(item.detectionSource).append(" • ").append(item.distanceBand)
            if (item.firstSeenTimestampMillis > 0L) {
                append("\nPrimera detección: ").append(item.firstSeenDistanceBand)
                append(" • Confirmación: ").append(item.confirmedDistanceBand)
                append(" • +").append(item.confirmationDelayMs).append(" ms")
            }
            item.latitude?.let { lat ->
                item.longitude?.let { lon ->
                    append("\nGPS: ").append("%.6f".format(Locale.US, lat)).append(", ").append("%.6f".format(Locale.US, lon))
                    item.locationAccuracyM?.let { append(" • ±").append("%.1f".format(Locale.US, it)).append(" m") }
                }
            }
            if (item.placeName.isNotBlank() || item.addressLine.isNotBlank()) {
                append("\nLugar: ").append(item.placeName.ifBlank { item.locality.ifBlank { item.adminArea } })
                if (item.addressLine.isNotBlank() && item.addressLine != item.placeName) {
                    append(" • ").append(item.addressLine)
                }
            }
        }
        val overlayUri = item.overlayImageUri.ifBlank { item.imageUri }
        bindThumbnail(thumbnail, overlayUri)
        imageButton.isEnabled = overlayUri.isNotBlank()
        imageButton.setOnClickListener { if (overlayUri.isNotBlank()) runCatching { onOpenImage(Uri.parse(overlayUri)) } }

        maskButton.isEnabled = item.maskImageUri.isNotBlank()
        maskButton.setOnClickListener {
            if (item.maskImageUri.isNotBlank()) runCatching { onOpenImage(Uri.parse(item.maskImageUri)) }
        }

        originalButton.isEnabled = item.originalImageUri.isNotBlank()
        originalButton.setOnClickListener {
            if (item.originalImageUri.isNotBlank()) runCatching { onOpenImage(Uri.parse(item.originalImageUri)) }
        }

        val hasCoordinates = item.latitude != null && item.longitude != null
        mapButton.isEnabled = true
        mapButton.alpha = if (hasCoordinates) 1f else 0.55f
        mapButton.text = if (hasCoordinates) "MAPA" else "SIN GPS"
        mapButton.setOnClickListener { onOpenMap(item) }

        deleteButton.setOnClickListener { onDeleteOne(item) }
        view.setOnLongClickListener {
            check.isChecked = !check.isChecked
            true
        }
        return view
    }
    private fun bindThumbnail(imageView: ImageView, uriText: String) {
        imageView.tag = uriText
        if (uriText.isBlank()) {
            imageView.setImageDrawable(null)
            return
        }
        thumbnailCache.get(uriText)?.let {
            imageView.setImageBitmap(it)
            return
        }
        imageView.setImageDrawable(null)
        thumbnailExecutor.execute {
            val bitmap = runCatching {
                context.contentResolver.openInputStream(Uri.parse(uriText))?.use { input ->
                    BitmapFactory.Options().run {
                        inSampleSize = 4
                        inPreferredConfig = Bitmap.Config.RGB_565
                        BitmapFactory.decodeStream(input, null, this)
                    }
                }
            }.getOrNull()
            if (bitmap != null) {
                thumbnailCache.put(uriText, bitmap)
                imageView.post {
                    if (imageView.tag == uriText) imageView.setImageBitmap(bitmap)
                }
            }
        }
    }

    fun shutdown() {
        thumbnailExecutor.shutdownNow()
        thumbnailCache.evictAll()
    }

}
