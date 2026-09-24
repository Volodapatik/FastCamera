package com.volodapatik.fastcamera

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.volodapatik.fastcamera.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var isGridVisible = false
    private var isVideoMode = false
    private var timerSeconds = 0
    private var countDownTimer: CountDownTimer? = null

    // Last captured media (photo or video)
    private var lastMediaUri: Uri? = null
    private var lastMediaIsVideo = false

    private var recordingStartTime = 0L
    private val recordingHandler = Handler(Looper.getMainLooper())
    private val recordingRunnable = object : Runnable {
        override fun run() {
            val elapsed = (System.currentTimeMillis() - recordingStartTime) / 1000
            val min = elapsed / 60
            val sec = elapsed % 60
            binding.tvRecordingTime.text = String.format("%02d:%02d", min, sec)
            recordingHandler.postDelayed(this, 1000)
        }
    }

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startCamera()
        } else {
            Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val topPadding = maxOf(systemBars.top, cutout.top) + 28

            binding.topBar.updatePadding(top = topPadding)
            binding.bottomBar.updatePadding(bottom = systemBars.bottom + 12)
            binding.previewTopBar.updatePadding(top = topPadding)
            binding.previewBottomBar.updatePadding(bottom = systemBars.bottom + 12)

            insets
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        scaleGestureDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val cam = camera ?: return false
                    val currentZoom = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                    cam.cameraControl.setZoomRatio(currentZoom * detector.scaleFactor)
                    return true
                }
            })

        binding.previewView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) {
                val factory = binding.previewView.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                    .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                camera?.cameraControl?.startFocusAndMetering(action)
            }
            true
        }

        setupButtons()
        updateFlashUI()
        checkPermissionsAndStart()
    }

    private fun setupButtons() {
        binding.btnCapture.setOnClickListener {
            if (isVideoMode) {
                if (recording != null) stopRecording() else startRecordingWithTimer()
            } else {
                takePhotoWithTimer()
            }
        }

        binding.btnSwitchCamera.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            startCamera()
        }

        binding.btnFlash.setOnClickListener {
            flashMode = when (flashMode) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                else -> ImageCapture.FLASH_MODE_OFF
            }
            imageCapture?.flashMode = flashMode
            updateFlashUI()
        }

        binding.btnTimer.setOnClickListener {
            timerSeconds = when (timerSeconds) {
                0 -> 3
                3 -> 5
                5 -> 10
                else -> 0
            }
            binding.tvTimerState.text = if (timerSeconds == 0) "" else "${timerSeconds}s"
            Toast.makeText(this, if (timerSeconds == 0) "Таймер: вимк" else "Таймер: ${timerSeconds}с", Toast.LENGTH_SHORT).show()
        }

        binding.btnGrid.setOnClickListener {
            isGridVisible = !isGridVisible
            binding.gridOverlay.visibility = if (isGridVisible) View.VISIBLE else View.GONE
            binding.btnGrid.alpha = if (isGridVisible) 1f else 0.5f
            Toast.makeText(this, if (isGridVisible) "Сітка: увімк" else "Сітка: вимк", Toast.LENGTH_SHORT).show()
        }

        binding.btnMode.setOnClickListener {
            isVideoMode = !isVideoMode
            val modeText = if (isVideoMode) "Режим: Відео" else "Режим: Фото"
            Toast.makeText(this, modeText, Toast.LENGTH_SHORT).show()
            startCamera()
        }

        binding.btnGallery.setOnClickListener { openLastMedia() }
        binding.imgLastPhoto.setOnClickListener { openLastMedia() }

        binding.btnClosePreview.setOnClickListener { closePhotoPreview() }
        binding.btnBackToCamera.setOnClickListener { closePhotoPreview() }

        binding.btnSharePreview.setOnClickListener {
            lastMediaUri?.let { uri ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = if (lastMediaIsVideo) "video/mp4" else "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Поділитися"))
            }
        }

        binding.btnDeletePhoto.setOnClickListener { deleteLastMedia() }

        binding.btnOpenInGallery.setOnClickListener {
            lastMediaUri?.let { uri ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, if (lastMediaIsVideo) "video/*" else "image/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Не вдалося відкрити", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateFlashUI() {
        val text = when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> "ON"
            ImageCapture.FLASH_MODE_AUTO -> "AUTO"
            else -> "OFF"
        }
        binding.tvFlashState.text = text
    }

    private fun openLastMedia() {
        val uri = lastMediaUri
        if (uri == null) {
            Toast.makeText(this, "Немає медіа", Toast.LENGTH_SHORT).show()
            return
        }

        if (lastMediaIsVideo) {
            // Для відео одразу відкриваємо в системному плеєрі
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "video/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Не вдалося відкрити відео", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Для фото — вбудований перегляд
        try {
            val bitmap: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri))
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
            binding.imgFullPreview.setImageBitmap(bitmap)
            binding.previewOverlay.visibility = View.VISIBLE
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open preview", e)
            Toast.makeText(this, "Помилка відкриття", Toast.LENGTH_SHORT).show()
        }
    }

    private fun closePhotoPreview() {
        binding.previewOverlay.visibility = View.GONE
        binding.imgFullPreview.setImageDrawable(null)
    }

    private fun deleteLastMedia() {
        val uri = lastMediaUri ?: return
        try {
            if (contentResolver.delete(uri, null, null) > 0) {
                lastMediaUri = null
                lastMediaIsVideo = false
                binding.imgLastPhoto.setImageDrawable(null)
                binding.imgLastPhoto.visibility = View.GONE
                binding.btnGallery.visibility = View.VISIBLE
                closePhotoPreview()
                Toast.makeText(this, "Видалено", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Помилка видалення", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
        }
        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = binding.previewView.surfaceProvider
        }

        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(flashMode)
            .build()

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        try {
            cameraProvider.unbindAll()
            val useCases = if (isVideoMode) arrayOf(preview, videoCapture) else arrayOf(preview, imageCapture)
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, *useCases)
        } catch (e: Exception) {
            Log.e(TAG, "Binding failed", e)
            Toast.makeText(this, "Помилка камери", Toast.LENGTH_LONG).show()
        }
    }

    private fun takePhotoWithTimer() {
        if (timerSeconds > 0) startCountdown(timerSeconds) { takePhoto() } else takePhoto()
    }

    private fun startRecordingWithTimer() {
        if (timerSeconds > 0) startCountdown(timerSeconds) { startRecording() } else startRecording()
    }

    private fun startCountdown(seconds: Int, onFinish: () -> Unit) {
        binding.tvTimerCountdown.visibility = View.VISIBLE
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millis: Long) {
                binding.tvTimerCountdown.text = ((millis / 1000) + 1).toString()
            }
            override fun onFinish() {
                binding.tvTimerCountdown.visibility = View.GONE
                onFinish()
            }
        }.start()
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "FC_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FastCamera")
            }
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
        ).build()

        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    output.savedUri?.let {
                        lastMediaUri = it
                        lastMediaIsVideo = false
                        showMediaThumbnail(it, isVideo = false)
                    }
                    Toast.makeText(baseContext, "Фото збережено", Toast.LENGTH_SHORT).show()
                }
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(baseContext, "Помилка фото", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun showMediaThumbnail(uri: Uri, isVideo: Boolean) {
        try {
            val bitmap: Bitmap? = if (isVideo) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentResolver.loadThumbnail(uri, Size(128, 128), null)
                } else {
                    @Suppress("DEPRECATION")
                    ThumbnailUtils.createVideoThumbnail(
                        uri.path ?: return,
                        MediaStore.Images.Thumbnails.MINI_KIND
                    )
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, _, _ ->
                        decoder.setTargetSampleSize(8)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(contentResolver, uri)
                }
            }

            if (bitmap != null) {
                binding.imgLastPhoto.setImageBitmap(bitmap)
                binding.imgLastPhoto.visibility = View.VISIBLE
                binding.btnGallery.visibility = View.INVISIBLE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Thumbnail error", e)
            // Навіть якщо мініатюра не завантажилась — показуємо іконку галереї як fallback
            binding.imgLastPhoto.visibility = View.GONE
            binding.btnGallery.visibility = View.VISIBLE
        }
    }

    private fun startRecording() {
        val videoCapture = this.videoCapture ?: return
        binding.btnCapture.isEnabled = false

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "FC_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/FastCamera")
            }
        }

        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutput)
            .apply {
                if (PermissionChecker.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PermissionChecker.PERMISSION_GRANTED) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        binding.btnCapture.isEnabled = true
                        binding.tvRecordingTime.visibility = View.VISIBLE
                        recordingStartTime = System.currentTimeMillis()
                        recordingHandler.post(recordingRunnable)
                        Toast.makeText(this@MainActivity, "Запис...", Toast.LENGTH_SHORT).show()
                    }
                    is VideoRecordEvent.Finalize -> {
                        recordingHandler.removeCallbacks(recordingRunnable)
                        binding.tvRecordingTime.visibility = View.GONE
                        if (!event.hasError()) {
                            val uri = event.outputResults.outputUri
                            if (uri != Uri.EMPTY) {
                                lastMediaUri = uri
                                lastMediaIsVideo = true
                                showMediaThumbnail(uri, isVideo = true)
                            }
                            Toast.makeText(this@MainActivity, "Відео збережено", Toast.LENGTH_SHORT).show()
                        } else {
                            recording?.close()
                            recording = null
                            Log.e(TAG, "Video error: ${event.error}")
                        }
                        binding.btnCapture.isEnabled = true
                    }
                    else -> {}
                }
            }
    }

    private fun stopRecording() {
        recording?.stop()
        recording = null
        recordingHandler.removeCallbacks(recordingRunnable)
        binding.tvRecordingTime.visibility = View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        countDownTimer?.cancel()
        recordingHandler.removeCallbacks(recordingRunnable)
    }

    companion object {
        private const val TAG = "FastCamera"
    }
}
