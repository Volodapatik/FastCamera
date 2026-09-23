package com.volodapatik.fastcamera

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
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
    private var lastPhotoUri: Uri? = null

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

            binding.topBar.updatePadding(top = systemBars.top + 6)
            binding.bottomBar.updatePadding(bottom = systemBars.bottom + 10)
            binding.previewTopBar.updatePadding(top = systemBars.top + 6)
            binding.previewBottomBar.updatePadding(bottom = systemBars.bottom + 10)

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
            val modeText = if (isVideoMode) "Режим: Відео" else "Режим: Фото"
            Toast.makeText(this, modeText, Toast.LENGTH_SHORT).show()
            startCamera()
        }

        // Тільки при натисканні на мініатюру відкриваємо перегляд
        binding.btnGallery.setOnClickListener { openLastPhotoPreview() }
        binding.imgLastPhoto.setOnClickListener { openLastPhotoPreview() }

        binding.btnClosePreview.setOnClickListener { closePhotoPreview() }
        binding.btnBackToCamera.setOnClickListener { closePhotoPreview() }

        binding.btnSharePreview.setOnClickListener {
            lastPhotoUri?.let { uri ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Поділитися фото"))
            }
        }

        binding.btnDeletePhoto.setOnClickListener {
            deleteLastPhoto()
        }
    }

    private fun openLastPhotoPreview() {
        val uri = lastPhotoUri
        if (uri == null) {
            Toast.makeText(this, "Немає фото для перегляду", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val bitmap: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
            binding.imgFullPreview.setImageBitmap(bitmap)
            binding.previewOverlay.visibility = View.VISIBLE
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open preview", e)
            Toast.makeText(this, "Не вдалося відкрити фото", Toast.LENGTH_SHORT).show()
        }
    }

    private fun closePhotoPreview() {
        binding.previewOverlay.visibility = View.GONE
        binding.imgFullPreview.setImageDrawable(null)
    }

    private fun deleteLastPhoto() {
        val uri = lastPhotoUri ?: return
        try {
            val deleted = contentResolver.delete(uri, null, null)
            if (deleted > 0) {
                lastPhotoUri = null
                binding.imgLastPhoto.setImageDrawable(null)
                binding.imgLastPhoto.visibility = View.GONE
                binding.btnGallery.visibility = View.VISIBLE
                closePhotoPreview()
                Toast.makeText(this, "Фото видалено", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Не вдалося видалити", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Delete failed", e)
            Toast.makeText(this, "Помилка видалення", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) startCamera()
        else requestPermissionLauncher.launch(permissions.toTypedArray())
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

        val preview = Preview.Builder().build().also {
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

            camera = cameraProvider.bindToLifecycle(this, cameraSelector, *useCases)

        } catch (e: Exception) {
            Log.e(TAG, "Use case binding failed", e)
            Toast.makeText(this, "Помилка камери: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun takePhotoWithTimer() {
        if (timerSeconds > 0) startCountdown(timerSeconds) { takePhoto() }
        else takePhoto()
    }

    private fun startRecordingWithTimer() {
        if (timerSeconds > 0) startCountdown(timerSeconds) { startRecording() }
        else startRecording()
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
                    val uri = output.savedUri
                    if (uri != null) {
                        lastPhotoUri = uri
                        showLastPhotoThumbnail(uri)
                        // НЕ відкриваємо автоматично — тільки мініатюра
                    }
                    Toast.makeText(baseContext, "Фото збережено", Toast.LENGTH_SHORT).show()
                }
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    Toast.makeText(baseContext, "Помилка фото", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun showLastPhotoThumbnail(uri: Uri) {
        try {
            val bitmap: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.setTargetSampleSize(8)
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }

            binding.imgLastPhoto.setImageBitmap(bitmap)
            binding.imgLastPhoto.visibility = View.VISIBLE
            binding.btnGallery.visibility = View.INVISIBLE
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load thumbnail", e)
        }
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
                        this@MainActivity, Manifest.permission.RECORD_AUDIO
                    ) == PermissionChecker.PERMISSION_GRANTED
                ) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        binding.btnCapture.isEnabled = true
                        Toast.makeText(this@MainActivity, "Запис...", Toast.LENGTH_SHORT).show()
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!event.hasError()) {
                            Toast.makeText(this@MainActivity, "Відео збережено", Toast.LENGTH_SHORT).show()
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
