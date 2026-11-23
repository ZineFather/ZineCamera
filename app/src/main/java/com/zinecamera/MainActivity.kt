package com.zinecamera

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaRecorder
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.FileDescriptor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.pow

class MainActivity : Activity() {
    private lateinit var preview: TextureView
    private lateinit var status: TextView
    private lateinit var zoomValue: TextView
    private lateinit var zoomSlider: SeekBar
    private lateinit var zoomMode: TextView
    private lateinit var flashButton: ImageView
    private lateinit var timerButton: TextView
    private lateinit var qualityButton: TextView
    private lateinit var shutter: View
    private lateinit var recordIndicator: TextView

    private var selectedVideoQuality = "1080p30"
    private data class VideoQuality(val label: String, val width: Int, val height: Int, val fps: Int)
    private val videoQualities = listOf(
        VideoQuality("720p · 30 FPS",1280,720,30),
        VideoQuality("1080p · 30 FPS",1920,1080,30),
        VideoQuality("1080p · 60 FPS",1920,1080,60),
        VideoQuality("4K · 30 FPS",3840,2160,30),
        VideoQuality("4K · 60 FPS",3840,2160,60)
    )
    private val cameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private var cameraId: String = ""
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var recorder: MediaRecorder? = null
    private var recorderDescriptor: android.os.ParcelFileDescriptor? = null
    private var recordingUri: android.net.Uri? = null
    private var recording = false
    private var frontCamera = false
    private var flash = false
    private var experimental1000x = false
    private var timerSeconds = 0
    private var zoom = 1f
    private var maxZoom = 1f
    private var activeArray: Rect? = null
    private var previewSurface: Surface? = null
    private var previewSize = android.util.Size(1920, 1080)
    private var photoPendingUri: android.net.Uri? = null
    private var selectedMode = "PHOTO"
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private val cameraCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createPreviewSession()
        }
        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            if (cameraDevice === camera) cameraDevice = null
            setStatus("Camera disconnected")
        }
        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            if (cameraDevice === camera) cameraDevice = null
            setStatus("Camera error ($error)")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        setContentView(R.layout.activity_main)
        bindViews()
        startCameraThread()
        wireControls()
        if (hasPermissions()) {
            if (preview.isAvailable) openCamera() else preview.surfaceTextureListener = textureListener
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 41)
        }
    }

    private fun bindViews() {
        preview = findViewById(R.id.cameraPreview)
        status = findViewById(R.id.statusText)
        zoomValue = findViewById(R.id.zoomValue)
        zoomSlider = findViewById(R.id.zoomSlider)
        zoomMode = findViewById(R.id.zoomMode)
        flashButton = findViewById(R.id.flashButton)
        timerButton = findViewById(R.id.timerButton)
        qualityButton = findViewById(R.id.qualityButton)
        shutter = findViewById(R.id.shutterButton)
        recordIndicator = findViewById(R.id.recordIndicator)
    }

    private fun wireControls() {
        flashButton.setOnClickListener {
            flash = !flash
            flashButton.alpha = if (flash) 1f else 0.55f
            updateRepeatingRequest()
            setStatus(if (flash) "Flash enabled" else "Flash disabled")
        }
        timerButton.setOnClickListener {
            timerSeconds = when (timerSeconds) { 0 -> 3; 3 -> 10; else -> 0 }
            timerButton.text = if (timerSeconds == 0) "OFF" else "${timerSeconds}s"
        }
        qualityButton.setOnClickListener { showVideoQualityMenu() }
        zoomMode.setOnClickListener {
            experimental1000x = !experimental1000x
            zoomSlider.max = if (experimental1000x) 1000 else 700
            zoomMode.text = if (experimental1000x) "1000x" else "100x"
            setStatus(if (experimental1000x) "Experimental digital zoom enabled" else "Digital zoom limited to 100x")
        }
        zoomSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val limit = if (experimental1000x) 1000f else 100f
                val fraction = progress.toFloat() / zoomSlider.max.coerceAtLeast(1)
                zoom = 1f + (limit - 1f) * fraction.pow(2.35f)
                zoomValue.text = String.format(Locale.US, "%.1fx", zoom)
                applyZoom()
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        shutter.setOnClickListener {
            if (selectedMode == "VIDEO") {
                if (recording) stopRecording() else startRecording()
            } else {
                if (timerSeconds > 0) {
                    setStatus("Photo in ${timerSeconds}s")
                    shutter.postDelayed({ takePhoto() }, timerSeconds * 1000L)
                } else takePhoto()
            }
        }
        findViewById<ImageView>(R.id.galleryButton).setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply { type = "image/*" }
            runCatching { startActivity(intent) }.onFailure { setStatus("Gallery app unavailable") }
        }
        findViewById<TextView>(R.id.switchButton).setOnClickListener {
            if (recording) return@setOnClickListener
            frontCamera = !frontCamera
            closeCamera()
            openCamera()
        }
        val modes = listOf(
            R.id.modePhoto to "PHOTO", R.id.modeVideo to "VIDEO",
            R.id.modePortrait to "PORTRAIT", R.id.modeNight to "NIGHT", R.id.modePro to "PRO"
        )
        modes.forEach { (id, mode) ->
            findViewById<TextView>(id).setOnClickListener {
                selectedMode = mode
                modes.forEach { (otherId, otherMode) ->
                    val view = findViewById<TextView>(otherId)
                    view.setBackgroundResource(if (otherMode == mode) R.drawable.bg_active_pill else R.drawable.bg_glass_pill)
                    view.setTextColor(if (otherMode == mode) 0xFF101318.toInt() else 0xFFA7ABB5.toInt())
                }
                recordIndicator.text = if (mode == "VIDEO") "TAP TO RECORD" else "CAPTURE"
                setStatus(when (mode) {
                    "PORTRAIT" -> "Portrait mode • depth effect depends on camera support"
                    "NIGHT" -> "Night mode selected"
                    "PRO" -> "Pro mode selected"
                    "VIDEO" -> "Video mode ready"
                    else -> "Photo mode ready"
                })
            }
        }
    }

    private fun showVideoQualityMenu() {
        val menu = android.widget.PopupMenu(this, qualityButton)
        videoQualities.forEachIndexed { index, q -> menu.menu.add(0,index,index,q.label) }
        menu.setOnMenuItemClickListener { item ->
            val q=videoQualities[item.itemId]
            selectedVideoQuality=when { q.width==1280 -> "720p30"; q.width==1920 && q.fps==30 -> "1080p30"; q.width==1920 -> "1080p60"; q.fps==30 -> "4K30"; else -> "4K60" }
            qualityButton.text=q.label.replace(" FPS","")
            setStatus("Video quality: ${q.label}")
            true
        }
        menu.show()
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) = openCamera()
        override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) = Unit
        override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) = Unit
    }

    private fun startCameraThread() {
        cameraThread = HandlerThread("ZineCamera2").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    private fun hasPermissions() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (preview.isAvailable) openCamera() else preview.surfaceTextureListener = textureListener
        } else setStatus("Camera permission is required")
    }

    private fun openCamera() {
        if (!hasPermissions() || !preview.isAvailable || isFinishing) return
        try {
            val ids = cameraManager.cameraIdList
            cameraId = ids.firstOrNull { id ->
                val facing = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                facing == if (frontCamera) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            } ?: ids.first()
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            maxZoom = (chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f).coerceAtLeast(1f)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java).orEmpty()
            previewSize = sizes.firstOrNull { it.width <= 1920 && it.height <= 1080 } ?: sizes.firstOrNull() ?: android.util.Size(1280, 720)
            preview.surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)
            cameraManager.openCamera(cameraId, cameraCallback, cameraHandler)
            setStatus("Camera ready")
        } catch (e: Exception) {
            setStatus("Open failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun createPreviewSession() {
        val device = cameraDevice ?: return
        try {
            val texture = preview.surfaceTexture ?: return
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            previewSurface = Surface(texture)
            val jpegSizes = cameraManager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(android.graphics.ImageFormat.JPEG)
            val photoSize = jpegSizes?.maxByOrNull { it.width.toLong() * it.height } ?: android.util.Size(1920, 1080)
            imageReader?.close()
            imageReader = ImageReader.newInstance(photoSize.width, photoSize.height, android.graphics.ImageFormat.JPEG, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                val bytes = image.planes[0].buffer.let { buffer -> ByteArray(buffer.remaining()).also { buffer.get(it) } }
                image.close()
                val uri = photoPendingUri ?: return@setOnImageAvailableListener
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    photoPendingUri = null
                    runOnUiThread { setStatus("Photo saved to Pictures/ZineCamera") }
                } catch (e: Exception) {
                    runOnUiThread { setStatus("Photo save failed") }
                }
            }, cameraHandler)
            val surfaces = listOfNotNull(previewSurface, imageReader?.surface)
            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    session = configured
                    updateRepeatingRequest()
                }
                override fun onConfigureFailed(failed: CameraCaptureSession) = setStatus("Preview configuration failed")
            }, cameraHandler)
        } catch (e: Exception) {
            setStatus("Preview failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun buildPreviewRequest(): CaptureRequest? {
        val device = cameraDevice ?: return null
        val surface = previewSurface ?: return null
        return try {
            device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.FLASH_MODE, if (flash && !frontCamera) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                cropRegion()?.let { set(CaptureRequest.SCALER_CROP_REGION, it) }
            }.build()
        } catch (e: Exception) {
            setStatus("Preview request failed: ${e.message}")
            null
        }
    }

    private fun updateRepeatingRequest() {
        val activeSession = session ?: return
        val request = buildPreviewRequest() ?: return
        try {
            activeSession.setRepeatingRequest(request, null, cameraHandler)
        } catch (e: Exception) {
            setStatus("Camera control failed: ${e.message}")
        }
    }

    private fun cropRegion(): Rect? {
        val array = activeArray ?: return null
        val hardwareZoom = zoom.coerceIn(1f, maxZoom)
        val centerX = array.centerX()
        val centerY = array.centerY()
        val width = (array.width() / hardwareZoom).roundToInt().coerceAtLeast(2)
        val height = (array.height() / hardwareZoom).roundToInt().coerceAtLeast(2)
        return Rect(
            (centerX - width / 2).coerceAtLeast(array.left),
            (centerY - height / 2).coerceAtLeast(array.top),
            (centerX + width / 2).coerceAtMost(array.right),
            (centerY + height / 2).coerceAtMost(array.bottom)
        )
    }

    private fun applyZoom() {
        val nativeZoom = zoom.coerceAtMost(maxZoom)
        val extra = (zoom / nativeZoom).coerceAtLeast(1f)
        preview.scaleX = extra.coerceAtMost(100f)
        preview.scaleY = extra.coerceAtMost(100f)
        updateRepeatingRequest()
        if (zoom > maxZoom) setStatus("Digital zoom • native limit ${String.format(Locale.US, "%.1f", maxZoom)}x")
    }

    private fun takePhoto() {
        val device = cameraDevice ?: return
        val activeSession = session ?: return
        val readerSurface = imageReader?.surface ?: return
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Zine_${stamp()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ZineCamera")
            }
            photoPendingUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(readerSurface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, if (flash && !frontCamera) CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH else CaptureRequest.CONTROL_AE_MODE_ON)
                if (flash && !frontCamera) set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
                cropRegion()?.let { set(CaptureRequest.SCALER_CROP_REGION, it) }
                set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation())
            }.build()
            activeSession.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: android.hardware.camera2.TotalCaptureResult) {
                    runOnUiThread { setStatus("Photo captured") }
                }
            }, cameraHandler)
        } catch (e: Exception) {
            photoPendingUri?.let { contentResolver.delete(it, null, null) }
            photoPendingUri = null
            setStatus("Capture failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun sensorOrientation(): Int =
        cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

    private fun startRecording() {
        val device = cameraDevice ?: return
        val texture = preview.surfaceTexture ?: return
        if (recording || recorder != null) return
        val quality = videoQualities.firstOrNull { q ->
            selectedVideoQuality == when {
                q.width == 1280 -> "720p30"
                q.width == 1920 && q.fps == 30 -> "1080p30"
                q.width == 1920 -> "1080p60"
                q.fps == 30 -> "4K30"
                else -> "4K60"
            }
        } ?: videoQualities[1]
        setStatus("Preparing ${quality.label}…")
        shutter.isEnabled = false
        Thread({
            try {
                val supportedSizes = cameraManager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(MediaRecorder::class.java).orEmpty()
                val requestedSupported = supportedSizes.any { it.width == quality.width && it.height == quality.height } &&
                    supportedFpsRange(quality.fps) != null
                val actualQuality = if (requestedSupported) quality else videoQualities[1]
                if (!requestedSupported) runOnUiThread {
                    qualityButton.text = "1080p · 30"
                    setStatus("${quality.label} unavailable; safely switching to 1080p30")
                }
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "Zine_${stamp()}.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ZineCamera")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                }
                recordingUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Cannot create video file")
                recorderDescriptor = contentResolver.openFileDescriptor(recordingUri!!, "w")
                    ?: throw IllegalStateException("Cannot open video file")
                val media = newMediaRecorder()
                recorder = media
                configureRecorder(media, recorderDescriptor!!.fileDescriptor, actualQuality)
                createRecordingSession(device, texture, media, actualQuality)
            } catch (e: Exception) {
                runOnUiThread {
                    cleanupFailedRecording()
                    shutter.isEnabled = true
                    setStatus("Cannot start ${quality.label}: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }, "ZineRecorderSetup").start()
    }

    private fun supportedFpsRange(fps: Int): android.util.Range<Int>? {
        val ranges = cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        return ranges.filter { it.lower <= fps && it.upper >= fps }.minByOrNull { it.upper - it.lower }
    }

    private fun videoOrientationHint(): Int {
        val sensor = sensorOrientation()
        val rotation = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (frontCamera) (sensor + rotation) % 360 else (sensor - rotation + 360) % 360
    }

    @Suppress("DEPRECATION")
    private fun newMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

    private fun configureRecorder(media: MediaRecorder, fd: FileDescriptor, quality: VideoQuality) {
        val width=quality.width; val height=quality.height; val fps=quality.fps
        media.reset()
        media.setAudioSource(MediaRecorder.AudioSource.MIC)
        media.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        media.setOutputFile(fd)
        media.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        media.setVideoSize(width, height)
        media.setVideoFrameRate(fps)
        media.setOrientationHint(videoOrientationHint())
        media.setVideoEncodingBitRate(when { width >= 3840 && fps == 60 -> 65_000_000; width >= 3840 -> 42_000_000; width >= 1920 && fps == 60 -> 24_000_000; width >= 1920 -> 14_000_000; else -> 8_000_000 })
        media.setAudioEncodingBitRate(128_000)
        media.setAudioSamplingRate(48_000)
        media.prepare()
    }

    private fun createRecordingSession(device: CameraDevice, texture: android.graphics.SurfaceTexture, media: MediaRecorder, quality: VideoQuality, allowFallback: Boolean = true) {
        closeCameraSession()
        texture.setDefaultBufferSize(1920, 1080)
        val previewOut = Surface(texture)
        val recorderOut = media.surface
        device.createCaptureSession(listOf(previewOut, recorderOut), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                session = configured
                try {
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(previewOut)
                        addTarget(recorderOut)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        supportedFpsRange(quality.fps)?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                        cropRegion()?.let { set(CaptureRequest.SCALER_CROP_REGION, it) }
                    }.build()
                    configured.setRepeatingRequest(request, null, cameraHandler)
                    media.start()
                    recording = true
                    runOnUiThread {
                        shutter.isEnabled = true
                        recordIndicator.text = "● RECORDING"
                        setStatus("Recording ${quality.label}")
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        cleanupFailedRecording()
                        shutter.isEnabled = true
                        setStatus("Recorder start failed: ${e.message}")
                        createPreviewSession()
                    }
                    runCatching { media.reset() }
                    closeCameraSession()
                }
            }
            override fun onConfigureFailed(failed: CameraCaptureSession) {
                if (allowFallback && recorderDescriptor != null) {
                    try {
                        media.reset()
                        configureRecorder(media, recorderDescriptor!!.fileDescriptor, videoQualities[1])
                        runOnUiThread { setStatus("Selected mode rejected; falling back to 1080p30") }
                        createRecordingSession(device, texture, media, videoQualities[1], false)
                    } catch (e: Exception) {
                        runOnUiThread {
                            cleanupFailedRecording()
                            shutter.isEnabled = true
                            setStatus("Video fallback failed: ${e.message}")
                            createPreviewSession()
                        }
                    }
                } else {
                    runOnUiThread {
                        setStatus("Video session rejected by camera")
                        cleanupFailedRecording()
                        shutter.isEnabled = true
                        createPreviewSession()
                    }
                }
            }
        }, cameraHandler)
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        try {
            recorder?.stop()
            recorder?.reset()
            recordingUri?.let { uri ->
                val values = ContentValues().apply { if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.IS_PENDING, 0) }
                contentResolver.update(uri, values, null, null)
            }
            setStatus("Video saved")
        } catch (e: Exception) {
            recordingUri?.let { contentResolver.delete(it, null, null) }
            setStatus("Recording stopped with an error")
        } finally {
            recorderDescriptor?.close()
            recorderDescriptor = null
            recorder?.release()
            recorder = null
            recordingUri = null
            recordIndicator.text = "TAP TO RECORD"
            shutter.isEnabled = true
            closeCameraSession()
            createPreviewSession()
        }
    }

    private fun cleanupFailedRecording() {
        recording = false
        runCatching { recorder?.reset(); recorder?.release() }
        recorder = null
        runCatching { recorderDescriptor?.close() }
        recorderDescriptor = null
        recordingUri?.let { contentResolver.delete(it, null, null) }
        recordingUri = null
        recordIndicator.text = "TAP TO RECORD"
        shutter.isEnabled = true
    }

    private fun closeCameraSession() {
        runCatching { session?.stopRepeating() }
        runCatching { session?.abortCaptures() }
        runCatching { session?.close() }
        session = null
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private fun setStatus(message: String) { if (::status.isInitialized) runOnUiThread { status.text = message } }

    private fun closeCamera() {
        closeCameraSession()
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { previewSurface?.release() }
        previewSurface = null
    }

    override fun onResume() {
        super.onResume()
        if (::preview.isInitialized && preview.isAvailable && cameraDevice == null && hasPermissions()) openCamera()
    }

    override fun onPause() {
        if (recording) stopRecording()
        closeCamera()
        super.onPause()
    }

    override fun onDestroy() {
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        super.onDestroy()
    }
}
