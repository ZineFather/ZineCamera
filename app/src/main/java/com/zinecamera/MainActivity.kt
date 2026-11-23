package com.zinecamera

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodecList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import android.hardware.camera2.CameraCharacteristics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var zoomBar: SeekBar
    private lateinit var modeLabel: TextView
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: androidx.camera.video.Recording? = null
    private var maxNativeZoom = 1f
    private var requestedZoom = 1f
    private var isVideo = false
    private var facing = CameraSelector.LENS_FACING_BACK
    private var flashEnabled = false
    private var timerSeconds = 0
    private var selectedMode = "PHOTO"
    private var selectedQuality = Quality.FHD
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.CAMERA] == true) startCamera()
        else status.text = "Camera permission is required"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildInterface()
        if (hasPermissions()) startCamera() else permissionLauncher.launch(requiredPermissions())
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF08090D.toInt())
            setPadding(14, 12, 14, 12)
        }
        val header = TextView(this).apply {
            text = "ZINE CAMERA"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            letterSpacing = 0.18f
        }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        val previewParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = 10; bottomMargin = 8 }
        status = label("Starting camera…", 11f, 0xFFB9C0CE.toInt())
        modeLabel = label("PHOTO", 13f, 0xFF72A7FF.toInt())
        zoomLabel = label("1.0x", 19f, 0xFFFFFFFF.toInt())
        zoomBar = SeekBar(this).apply {
            max = 1000
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val zoom = 1000f.pow(progress / 1000f)
                    applyZoom(zoom)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        val modeRow = row()
        listOf("PHOTO", "VIDEO", "PORTRAIT", "NIGHT", "PRO").forEach { mode ->
            modeRow.addView(makeButton(mode) {
                selectedMode = mode
                isVideo = mode == "VIDEO"
                modeLabel.text = "$mode MODE"
                status.text = when (mode) {
                    "PORTRAIT" -> "Portrait mode selected • depth effect is not available yet"
                    "NIGHT" -> "Night mode selected • standard exposure pipeline"
                    "PRO" -> "Pro mode selected • manual controls are experimental"
                    "VIDEO" -> "Video mode • software zoom is preview-only while recording"
                    else -> "Photo mode ready"
                }
                if (isVideo) applyZoom(requestedZoom)
            }, 1f)
        }
        val quickRow = row()
        quickRow.addView(makeButton("FLASH") {
            flashEnabled = !flashEnabled
            imageCapture?.flashMode = if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
            Toast.makeText(this, if (flashEnabled) "Flash on" else "Flash off", Toast.LENGTH_SHORT).show()
        }, 1f)
        quickRow.addView(makeButton("TIMER") {
            timerSeconds = when (timerSeconds) { 0 -> 3; 3 -> 10; else -> 0 }
            Toast.makeText(this, "Timer: ${if (timerSeconds == 0) "Off" else "${timerSeconds}s"}", Toast.LENGTH_SHORT).show()
        }, 1f)
        quickRow.addView(makeButton("4K60 TEST") { probe4k60() }, 1f)
        quickRow.addView(makeButton("QUALITY") {
            selectedQuality = when (selectedQuality) {
                Quality.FHD -> Quality.UHD
                Quality.UHD -> Quality.HD
                else -> Quality.FHD
            }
            Toast.makeText(this, "Requested video quality: ${selectedQualityLabel()}", Toast.LENGTH_SHORT).show()
            status.text = "Quality applies after camera restart; selected ${selectedQualityLabel()}"
        }, 1f)
        quickRow.addView(makeButton("GALLERY") { openGallery() }, 1f)

        val actionRow = row()
        actionRow.addView(makeButton("SWITCH") {
            facing = if (facing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            startCamera()
        }, 1f)
        actionRow.addView(makeButton("CAPTURE") { takePhoto() }, 1.4f)
        actionRow.addView(makeButton("REC / STOP") { toggleRecording() }, 1f)

        root.addView(header)
        root.addView(previewView, previewParams)
        root.addView(modeLabel)
        root.addView(zoomLabel)
        root.addView(zoomBar)
        root.addView(modeRow)
        root.addView(quickRow)
        root.addView(actionRow)
        root.addView(status)
        setContentView(root)
    }

    private fun Float.pow(power: Float): Float = Math.pow(this.toDouble(), power.toDouble()).toFloat()
    private fun label(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER
    }
    private fun row() = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
    private fun makeButton(label: String, weight: Float = 1f, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 10f; isAllCaps = false
        setPadding(2, 0, 2, 0)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(0, 48, weight).apply { setMargins(2, 3, 2, 3) }
    }
    private fun selectedQualityLabel() = when (selectedQuality) {
        Quality.UHD -> "4K"
        Quality.HD -> "720p"
        else -> "1080p"
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= 28) permissions += Manifest.permission.WRITE_EXTERNAL_STORAGE
        return permissions.toTypedArray()
    }
    private fun hasPermissions() = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val selector = CameraSelector.Builder().requireLensFacing(facing).build()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setFlashMode(if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                    .build()
                val recorder = Recorder.Builder().setQualitySelector(
                    QualitySelector.fromOrderedList(
                        listOf(selectedQuality, Quality.FHD, Quality.HD, Quality.SD).distinct(),
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                ).build()
                videoCapture = VideoCapture.withOutput(recorder)
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, selector, preview, imageCapture, videoCapture)
                maxNativeZoom = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
                applyZoom(requestedZoom)
                reportCapabilities()
            } catch (e: Exception) {
                status.text = "Camera error: ${e.localizedMessage ?: e.javaClass.simpleName}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun applyZoom(value: Float) {
        requestedZoom = value.coerceIn(1f, 1000f)
        val native = min(requestedZoom, maxNativeZoom.coerceAtLeast(1f))
        camera?.cameraControl?.setZoomRatio(native)
        val software = if (isVideo) 1f else (requestedZoom / native).coerceAtLeast(1f)
        previewView.scaleX = software
        previewView.scaleY = software
        zoomLabel.text = String.format(Locale.US, "%.1fx", requestedZoom)
        if (requestedZoom > maxNativeZoom && !isVideo) {
            status.text = "Experimental digital zoom • native %.1fx + software crop".format(Locale.US, maxNativeZoom)
        } else if (requestedZoom > maxNativeZoom && isVideo) {
            status.text = "Video zoom limited to native %.1fx".format(Locale.US, maxNativeZoom)
        }
    }

    private fun probe4k60() {
        try {
            val cam = camera ?: run { status.text = "Camera is not ready"; return }
            val info = Camera2CameraInfo.from(cam.cameraInfo)
            val map = info.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val camera4k = map?.getOutputSizes(android.media.MediaRecorder::class.java)
                ?.any { it.width >= 3840 && it.height >= 2160 } == true
            val fps60 = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.any { it.upper >= 60 } == true
            val encoder4k60 = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder }
                .flatMap { codec -> codec.supportedTypes.filter { it.startsWith("video/") }.map { type -> codec to type } }
                .any { (codec, type) ->
                    try {
                        codec.getCapabilitiesForType(type).videoCapabilities
                            .areSizeAndRateSupported(3840, 2160, 60.0)
                    } catch (_: Exception) { false }
                }
            status.text = "4K60 probe • Camera 4K: $camera4k • AE 60fps: $fps60 • Encoder 4K60: $encoder4k60 • diagnostic only"
            Toast.makeText(this, "Probe complete. This does not force unsupported recording modes.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            status.text = "4K60 probe unavailable: ${e.localizedMessage ?: "unknown error"}"
        }
    }

    private fun reportCapabilities() {
        val cam = camera ?: return
        try {
            val info = Camera2CameraInfo.from(cam.cameraInfo)
            val map = info.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(android.media.MediaRecorder::class.java).orEmpty()
            val supports4k = sizes.any { it.width >= 3840 && it.height >= 2160 }
            val fps = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            val ranges = fps?.joinToString { "${it.lower}–${it.upper}" } ?: "unknown"
            status.text = "Native zoom ${"%.1f".format(Locale.US, maxNativeZoom)}x • 4K stream: ${if (supports4k) "listed" else "not listed"} • AE FPS: $ranges"
        } catch (_: Exception) {
            status.text = "Camera ready • capability query incomplete"
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val action = { savePhoto(capture) }
        if (timerSeconds == 0) action() else {
            status.text = "Photo in ${timerSeconds}s…"
            mainHandler.postDelayed({ action() }, timerSeconds * 1000L)
        }
    }

    private fun savePhoto(capture: ImageCapture) {
        val name = "Zine_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ZineCamera")
        }
        val output = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
        capture.takePicture(output, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                val uri = result.savedUri
                if (uri != null && requestedZoom > maxNativeZoom && !isVideo) {
                    cropSavedPhoto(uri, requestedZoom / maxNativeZoom.coerceAtLeast(1f))
                } else Toast.makeText(this@MainActivity, "Photo saved", Toast.LENGTH_SHORT).show()
            }
            override fun onError(exception: ImageCaptureException) {
                Toast.makeText(this@MainActivity, exception.message ?: "Capture failed", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun cropSavedPhoto(uri: android.net.Uri, factor: Float) {
        cameraExecutor.execute {
            try {
                val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@execute
                val cropWidth = max(1, (bitmap.width / factor).roundToInt()).coerceAtMost(bitmap.width)
                val cropHeight = max(1, (bitmap.height / factor).roundToInt()).coerceAtMost(bitmap.height)
                val left = (bitmap.width - cropWidth) / 2
                val top = (bitmap.height - cropHeight) / 2
                val cropped = Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
                contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                    cropped.compress(Bitmap.CompressFormat.JPEG, 94, stream)
                }
                if (cropped !== bitmap) cropped.recycle()
                bitmap.recycle()
                runOnUiThread { Toast.makeText(this, "Experimental digital crop saved", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Photo saved without software crop", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun toggleRecording() {
        val capture = videoCapture ?: return
        if (recording != null) { recording?.stop(); recording = null; return }
        val name = "Zine_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ZineCamera")
        }
        val output = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build()
        val pending = capture.output.prepareRecording(this, output)
        val configured = if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) pending.withAudioEnabled() else pending
        recording = configured.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> status.text = "Recording • software zoom disabled for video"
                is VideoRecordEvent.Finalize -> {
                    status.text = if (event.hasError()) "Recording failed: ${event.error}" else "Video saved"
                    recording = null
                }
            }
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply { type = "image/*" }
        try { startActivity(intent) } catch (_: Exception) {
            Toast.makeText(this, "No gallery app found", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        recording?.stop()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
