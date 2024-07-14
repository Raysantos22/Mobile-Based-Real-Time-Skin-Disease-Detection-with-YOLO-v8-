package com.surendramaran.yolov5.feature.home

// Import necessary libraries
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.surendramaran.yolov5.feature.home.Constants.LABELS_PATH
import com.surendramaran.yolov5.feature.home.Constants.MODEL_PATH
import com.surendramaran.yolov5.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.CameraSelector
import android.content.Intent
import android.provider.MediaStore
import android.widget.ImageButton
import android.widget.ToggleButton
import com.surendramaran.yolov5.R

// MainActivity class implementing DetectorListener interface
class MainActivity : AppCompatActivity(), Detector.DetectorListener {
    // Declaring variables
    private lateinit var binding: ActivityMainBinding
    private var isFrontCamera = false
    private var isFlashOn = false
    private var flashMode: FlashMode = FlashMode.OFF
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var detector: Detector
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private val REQUEST_CODE_GALLERY = 1001

    // onCreate method called when the activity is first created
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Inflating layout using View Binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize detector
        detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this)
        detector.setup()

        // Initialize ImageCapture
        imageCapture = ImageCapture.Builder()
            .setFlashMode(if (isFlashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
            .build()

        // Check if all required permissions are granted, if not request them
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }

        // Setting onClickListener for openGalleryButton
        val openGalleryButton: ImageButton = findViewById(R.id.openGalleryButton)
        openGalleryButton.setOnClickListener {
            openGallery()
        }

        // Creating a single thread executor for camera operations
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Setting onClickListeners for cameraButton, flipCameraButton, and flashToggleButton
        val cameraButton: ImageButton = findViewById(R.id.cameraButton)
        cameraButton.setOnClickListener {
            takePhoto()
        }
        val flipCameraButton: ImageButton = findViewById(R.id.flipCameraButton)
        flipCameraButton.setOnClickListener {
            flipCamera()
        }
        val flashToggleButton: ToggleButton = findViewById(R.id.flashToggleButton)
        flashToggleButton.setOnClickListener {
            toggleFlash()
        }
    }

    // Method to open the gallery to select images
    private fun openGallery() {
        val galleryIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        startActivityForResult(galleryIntent, REQUEST_CODE_GALLERY)
    }

    // Method to create preview for camera
    private fun createPreview(): Preview {
        return Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .build()
    }

    // Method to create image analyzer
    private fun createImageAnalyzer(): ImageAnalysis {
        return ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
    }

    // Method to create image capture
    private fun createImageCapture(): ImageCapture {
        return ImageCapture.Builder()
            .setFlashMode(if (isFlashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
            .build()
    }

    // Method to set flash mode
    private fun setFlashMode() {
        // Enable/disable torch based on flash mode
        val cameraControl = camera?.cameraControl
        cameraControl?.enableTorch(isFlashOn)

        // Rebind camera use cases with new flash mode
        val cameraProvider = cameraProvider ?: return
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(if (isFrontCamera) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK)
            .build()

        try {
            cameraProvider.unbindAll()

            // Create instances of the necessary use cases
            preview = createPreview()
            imageAnalyzer = createImageAnalyzer()
            imageCapture = createImageCapture()

            // Build the camera with the new use cases
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalyzer, imageCapture
            )

            // Set the flash mode based on the current state
            cameraControl?.enableTorch(isFlashOn)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    // Enum to represent flash mode
    enum class FlashMode {
        ON, OFF;

        // Method to toggle flash mode
        fun toggle(): FlashMode {
            return if (this == ON) OFF else ON
        }
    }

    // Method to check if flash is available
    private fun isFlashAvailable(): Boolean {
        val cameraProvider = cameraProvider ?: return false
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(if (isFrontCamera) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK)
            .build()

        return try {
            val camera = cameraProvider.bindToLifecycle(
                this, cameraSelector
            )
            val flashInfo = camera.cameraInfo
            flashInfo.hasFlashUnit()
        } catch (exc: Exception) {
            false
        }
    }

    // Method to toggle flash mode
    private fun toggleFlash() {
        isFlashOn = !isFlashOn
        cameraProvider?.unbindAll() // Unbind existing use cases
        setFlashMode()
        startCamera() // Rebind use cases with new flash mode
    }

    // Method to flip between front and back camera
    private fun flipCamera() {
        isFrontCamera = !isFrontCamera
        startCamera()
    }

    // Method to start the camera
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    // Method to bind camera use cases
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        val rotation = binding.viewFinder.display.rotation

        val cameraSelector = if (isFrontCamera) {
            CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()
        } else {
            CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()
        }

        preview = createPreview()

        imageAnalyzer = createImageAnalyzer()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmapBuffer =
                Bitmap.createBitmap(
                    imageProxy.width,
                    imageProxy.height,
                    Bitmap.Config.ARGB_8888
                )
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

                if (isFrontCamera) {
                    postScale(
                        -1f,
                        1f,
                        imageProxy.width.toFloat(),
                        imageProxy.height.toFloat()
                    )
                }
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                matrix, true
            )

            detector.detect(rotatedBitmap)
        }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer,
                imageCapture // Include imageCapture here
            )

            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    // Method to capture photo
    private fun takePhoto() {
        // Use imageCapture instead of imageAnalyzer
        val imageCapture = imageCapture

        // Define image folder to save the captured image
        val imageFolder = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "Images"
        )
        if (!imageFolder.exists()) {
            imageFolder.mkdir()
        }

        // Generate file name for the captured image
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .format(System.currentTimeMillis())
        val fileName = "IMG_$timeStamp.jpg"
        val imageFile = File(imageFolder, fileName)

        // Define output options for image capture
        val outputOptions = ImageCapture.OutputFileOptions.Builder(imageFile).build()

        // Capture image
        imageCapture?.takePicture(
            outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val message = "Photo Capture Succeeded: ${outputFileResults.savedUri}"
                    Toast.makeText(
                        this@MainActivity,
                        message,
                        Toast.LENGTH_LONG
                    ).show()
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(
                        this@MainActivity,
                        exception.message.toString(),
                        Toast.LENGTH_LONG
                    ).show()
                }

            }
        )
    }

    // Method to check if all required permissions are granted
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext,
            it
        ) == PackageManager.PERMISSION_GRANTED
    }

    // Request permission launcher to request camera permissions
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (it[Manifest.permission.CAMERA] == true) {
            startCamera()
        }
    }

    // onDestroy method called when the activity is destroyed
    override fun onDestroy() {
        super.onDestroy()
        detector.clear()
        cameraExecutor.shutdown()
    }

    // onResume method called when the activity is resumed
    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    companion object {
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA
        ).toTypedArray()
    }

    // Method of DetectorListener interface to handle empty detection
    override fun onEmptyDetect() {
        binding.overlay.invalidate()
    }

    // Method of DetectorListener interface to handle detection results
    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        runOnUiThread {
            binding.inferenceTime.text = "${inferenceTime}ms"
            binding.overlay.apply {
                setResults(boundingBoxes)
                invalidate()
            }
        }
    }
}
