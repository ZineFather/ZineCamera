package com.zinecamera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.FallbackStrategy
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

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var zoomBar: SeekBar
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: androidx.camera.video.Recording? = null
    private var maxZoom = 1f
    private var isVideo = false
    private var facing = CameraSelector.LENS_FACING_BACK

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
            setPadding(18, 18, 18, 18)
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
        ).apply { topMargin = 14; bottomMargin = 12 }
        status = TextView(this).apply {
            text = "Preparing camera…"
            textSize = 12f
            setTextColor(0xFFB9C0CE.toInt())
            gravity = Gravity.CENTER
        }
        zoomLabel = TextView(this).apply {
            text = "1.0x"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        zoomBar = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val min = camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f
                    val ratio = min + (maxZoom - min) * (progress / 1000f)
                    camera?.cameraControl?.setZoomRatio(ratio)
                    zoomLabel.text = String.format(Locale.US, "%.1fx", ratio)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        val modeRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        modeRow.addView(makeButton("PHOTO") { isVideo = false; status.text = "PHOTO mode" }, weightedParams())
        modeRow.addView(makeButton("VIDEO") { isVideo = true; status.text = "VIDEO mode" }, weightedParams())
        modeRow.addView(makeButton("SWITCH") {
            facing = if (facing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            startCamera()
        }, weightedParams())
        val actionRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        actionRow.addView(makeButton("CAPTURE") { takePhoto() }, weightedParams())
        actionRow.addView(makeButton("REC / STOP") { toggleRecording() }, weightedParams())
        root.addView(header)
        root.addView(previewView, previewParams)
        root.addView(status)
        root.addView(zoomLabel)
        root.addView(zoomBar)
        root.addView(modeRow)
        root.addView(actionRow)
        setContentView(root)
    }

    private fun weightedParams() = LinearLayout.LayoutParams(0, 52, 1f).apply { setMargins(4, 8, 4, 4) }
    private fun makeButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
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
                imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
                val recorder = Recorder.Builder().setQualitySelector(
                    QualitySelector.fromOrderedList(
                        listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD),
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                ).build()
                videoCapture = VideoCapture.withOutput(recorder)
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, selector, preview, imageCapture, videoCapture)
                val state = camera!!.cameraInfo.zoomState.value
                maxZoom = state?.maxZoomRatio ?: 1f
                zoomBar.progress = 0
                zoomLabel.text = String.format(Locale.US, "%.1fx", state?.minZoomRatio ?: 1f)
                reportCapabilities()
            } catch (e: Exception) {
                status.text = "Camera error: ${e.localizedMessage}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun reportCapabilities() {
        val cam = camera ?: return
        try {
            val info = Camera2CameraInfo.from(cam.cameraInfo)
            val map = info.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val fps = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            val sizes = map?.getOutputSizes(android.media.MediaRecorder::class.java).orEmpty()
            val supports4k = sizes.any { it.width >= 3840 && it.height >= 2160 }
            val highSpeedSizes = map?.highSpeedVideoSizes.orEmpty()
            val highSpeed1080 = highSpeedSizes.firstOrNull { it.width >= 1920 && it.height >= 1080 }
            val highSpeedRanges = highSpeed1080?.let { map?.getHighSpeedVideoFpsRangesFor(it) }.orEmpty()
            val supports1080p120 = highSpeedRanges.any { it.upper >= 120 }
            val highSpeedText = if (highSpeed1080 == null) "not listed" else highSpeedRanges.joinToString { "${it.lower}–${it.upper}fps" }
            val fpsText = fps?.joinToString { "${it.lower}–${it.upper}" } ?: "unknown"
            status.text = "Zoom 1–${String.format(Locale.US, "%.1f", maxZoom)}x • 4K stream: ${if (supports4k) "listed" else "not listed"} • 1080p120: ${if (supports1080p120) "high-speed stream listed" else "not listed"}"
            status.contentDescription = "High-speed 1080p ranges: $highSpeedText. AE FPS ranges: $fpsText"
        } catch (_: Exception) {
            status.text = "Camera ready • capability query incomplete"
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val name = "Zine_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ZineCamera")
        }
        val output = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
        capture.takePicture(output, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                Toast.makeText(this@MainActivity, "Photo saved", Toast.LENGTH_SHORT).show()
            }
            override fun onError(exception: ImageCaptureException) {
                Toast.makeText(this@MainActivity, exception.message ?: "Capture failed", Toast.LENGTH_LONG).show()
            }
        })
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
        val configured = if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending.withAudioEnabled()
        } else pending
        recording = configured.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> status.text = "Recording • CameraX quality selection"
                is VideoRecordEvent.Finalize -> {
                    status.text = if (event.hasError()) "Recording failed: ${event.error}" else "Video saved"
                    recording = null
                }
            }
        }
    }

    override fun onDestroy() {
        recording?.stop()
        super.onDestroy()
    }
}
