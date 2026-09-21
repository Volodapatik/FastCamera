package com.volodapatik.fastcamera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
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
    private var timerSeconds = 0 // 0, 3, 5, 10
    private var countDownTimer: CountDownTimer? = null

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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Pinch to zoom
        scaleGestureDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val camera = camera ?: return false
                    val currentZoom = camera.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                    val delta = detector.scaleFactor
                    camera.cameraControl.setZoomRatio(currentZoom * delta)
                    return true
                }
            })

        binding.previewView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) {
                // Tap to focus
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
        checkPermissionsAndStart()
    }

    private fun setupButtons() {
        binding.btnCapture.setOnClickListener {
            if (isVideoMode) {
                if (recording != null) {
                    stopRecording()
                } else {
                    startRecordingWithTimer()
                }
            } else {
                takePhotoWithTimer()
            }
        }

        binding.btnSwitchCamera.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            startCamera()
        }

        binding.btnFlash.setOnClickListener {
            flashMode = when (flashMode) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                else -> ImageCapture.FLASH_MODE_OFF
            }
            imageCapture?.flashMode = flashMode
            updateFlashIcon()
            val text = when (flashMode) {
                ImageCapture.FLASH_MODE_ON -> "Спалах: Увімк"
                ImageCapture.FLASH_MODE_AUTO -> "Спалах: Авто"
                else -> "Спалах: Вимк"
            }
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }

        binding.btnTimer.setOnClickListener {
            timerSeconds = when (timerSeconds) {
                0 -> 3
                3 -> 5
                5 -> 10
                else -> 0
            }
            val text = if (timerSeconds == 0) "Таймер: вимк" else "Таймер: ${timerSeconds}с"
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }

        binding.btnGrid.setOnClickListener {
            isGridVisible = !isGridVisible
            binding.gridOverlay.visibility = if (isGridVisible) View.VISIBLE else View.GONE
        }

        binding.btnMode.setOnClickListener {
            isVideoMode = !isVideoMode
            updateModeUI()
            startCamera() // rebind use cases
        }

        binding.btnGallery.setOnClickListener {
            // Open system gallery
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                type = "image/*"
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Галерея недоступна", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateFlashIcon() {
        // Simple feedback via toast already, can improve icons later
    }

    private fun updateModeUI() {
        if (isVideoMode) {
            binding.btnCapture.setBackgroundResource(android.R.drawable.ic_menu_camera) // temporary
            Toast.makeText(this, "Режим: Відео", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Режим: Фото", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
        }
        permissions.add(Manifest.permission.RECORD_AUDIO)

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return

        val preview = Preview.Builder()
            .build()
            .also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

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

            val useCases = if (isVideoMode) {
                arrayOf(preview, videoCapture)
            } else {
                arrayOf(preview, imageCapture)
            }

            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                *useCases
            )

            // Enable zoom range if available
            camera?.cameraInfo?.zoomState?.observe(this) { state ->
                // Can update UI slider later
            }

        } catch (e: Exception) {
            Log.e(TAG, "Use case binding failed", e)
            Toast.makeText(this, "Помилка камери: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun takePhotoWithTimer() {
        if (timerSeconds > 0) {
            startCountdown(timerSeconds) { takePhoto() }
        } else {
            takePhoto()
        }
    }

    private fun startRecordingWithTimer() {
        if (timerSeconds > 0) {
            startCountdown(timerSeconds) { startRecording() }
        } else {
            startRecording()
        }
    }

    private fun startCountdown(seconds: Int, onFinish: () -> Unit) {
        binding.tvTimerCountdown.visibility = View.VISIBLE
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                binding.tvTimerCountdown.text = ((millisUntilFinished / 1000) + 1).toString()
            }
            override fun onFinish() {
                binding.tvTimerCountdown.visibility = View.GONE
                onFinish()
            }
        }.start()
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "FC_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FastCamera")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Toast.makeText(baseContext, "Фото збережено", Toast.LENGTH_SHORT).show()
                }
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    Toast.makeText(baseContext, "Помилка фото", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun startRecording() {
        val videoCapture = this.videoCapture ?: return

        binding.btnCapture.isEnabled = false

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "FC_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/FastCamera")
            }
        }

        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutput)
            .apply {
                if (PermissionChecker.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.RECORD_AUDIO
                    ) == PermissionChecker.PERMISSION_GRANTED
                ) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        binding.btnCapture.isEnabled = true
                        Toast.makeText(this, "Запис...", Toast.LENGTH_SHORT).show()
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!event.hasError()) {
                            Toast.makeText(this, "Відео збережено", Toast.LENGTH_SHORT).show()
                        } else {
                            recording?.close()
                            recording = null
                            Log.e(TAG, "Video capture error: ${event.error}")
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
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        countDownTimer?.cancel()
    }

    companion object {
        private const val TAG = "FastCamera"
    }
}
