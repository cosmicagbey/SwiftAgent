package com.momo.swift.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Handles silent, discreet facial evidence capture using the front-facing camera.
 * Snaps a rapid burst of photos without shutter sound or preview flash while the customer
 * inspects their transaction details on screen.
 */
object SilentEvidenceCaptureManager {

    private const val TAG = "SilentEvidenceCapture"

    /**
     * Checks if the app currently has camera permission.
     */
    fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Captures a silent burst of facial photos from the front camera.
     *
     * @param context Application/Activity context.
     * @param lifecycleOwner The active Compose/Activity LifecycleOwner.
     * @param phoneNumber The flagged scammer phone number to tag in the file names.
     * @param burstCount Number of photos to take (default 3).
     * @param delayBetweenMs Delay between burst shots in milliseconds.
     * @return List of saved image files.
     */
    suspend fun captureBurstPhotos(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        phoneNumber: String = "unknown",
        burstCount: Int = 3,
        delayBetweenMs: Long = 500L
    ): List<File> = withContext(Dispatchers.Main) {
        if (!hasCameraPermission(context)) {
            Log.w(TAG, "Camera permission not granted, skipping silent evidence capture")
            return@withContext emptyList()
        }

        val cameraProvider = getCameraProvider(context) ?: return@withContext emptyList()

        try {
            // Select front-facing camera
            val cameraSelector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                Log.w(TAG, "No suitable camera available on this device")
                return@withContext emptyList()
            }

            // Build ImageCapture use-case optimized for fast capture
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                .build()

            // Bind to lifecycle
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                imageCapture
            )

            val evidenceDir = File(context.filesDir, "fraud_evidence").apply {
                if (!exists()) mkdirs()
            }

            val capturedFiles = mutableListOf<File>()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val cleanPhone = phoneNumber.replace(Regex("[^0-9]"), "").takeLast(10)

            // Capture burst shots
            for (i in 1..burstCount) {
                val photoFile = File(evidenceDir, "evidence_${cleanPhone}_${timestamp}_$i.jpg")
                val success = captureSinglePhoto(context, imageCapture, photoFile)
                if (success && photoFile.exists() && photoFile.length() > 0) {
                    capturedFiles.add(photoFile)
                    Log.d(TAG, "Captured evidence frame $i: ${photoFile.absolutePath} (${photoFile.length()} bytes)")
                    triggerSilentHapticTick(context, isCompletion = (i == burstCount))
                }
                if (i < burstCount) {
                    delay(delayBetweenMs)
                }
            }

            // Unbind camera immediately after burst is complete
            cameraProvider.unbindAll()

            return@withContext capturedFiles
        } catch (e: Exception) {
            Log.e(TAG, "Error during silent evidence capture", e)
            try {
                cameraProvider.unbindAll()
            } catch (_: Exception) {}
            return@withContext emptyList()
        }
    }

    /**
     * Cue 1: Heads-up preparation tick telling the agent the trap is primed.
     */
    fun triggerPreparationTick(context: Context) {
        try {
            val vibrator = getVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(45L, 100))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(45L)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Haptic tick suppressed: ${e.message}")
        }
    }

    /**
     * Cue 2: Distinct double-buzz telling the agent to turn screen to customer now.
     */
    fun triggerFlipScreenBuzz(context: Context) {
        try {
            val vibrator = getVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(
                    longArrayOf(0, 100, 80, 160),
                    intArrayOf(0, 180, 0, 240),
                    -1
                )
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 100, 80, 160), -1)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Haptic buzz suppressed: ${e.message}")
        }
    }

    /**
     * Cue 3: Subtle completion tick confirming evidence was captured.
     */
    fun triggerCompletionTick(context: Context) {
        triggerSilentHapticTick(context, isCompletion = true)
    }

    private fun getVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /**
     * Emits a whisper-quiet, transient micro-tick directly to the agent's fingers
     * without generating an audible motor buzz on hard surfaces.
     */
    private fun triggerSilentHapticTick(context: Context, isCompletion: Boolean = false) {
        try {
            val vibrator = getVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (isCompletion) {
                    val effect = VibrationEffect.createWaveform(
                        longArrayOf(0, 15, 50, 15),
                        intArrayOf(0, 60, 0, 80),
                        -1
                    )
                    vibrator.vibrate(effect)
                } else {
                    val effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                    vibrator.vibrate(effect)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val duration = if (isCompletion) 25L else 12L
                val effect = VibrationEffect.createOneShot(duration, 50)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(if (isCompletion) 25L else 12L)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Haptic tick suppressed: ${e.message}")
        }
    }

    private suspend fun getCameraProvider(context: Context): ProcessCameraProvider? {
        return suspendCancellableCoroutine { continuation ->
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                try {
                    continuation.resume(cameraProviderFuture.get())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get ProcessCameraProvider", e)
                    continuation.resume(null)
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    private suspend fun captureSinglePhoto(
        context: Context,
        imageCapture: ImageCapture,
        outputFile: File
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    if (continuation.isActive) {
                        continuation.resume(true)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }
            }
        )
    }

    /**
     * Retrieves saved facial evidence photos from the secure evidence directory.
     * Optionally filters by phone number, or returns recent evidence photos.
     */
    fun getEvidencePhotos(context: Context, phoneNumber: String? = null): List<File> {
        val evidenceDir = File(context.filesDir, "fraud_evidence")
        if (!evidenceDir.exists()) return emptyList()
        val files = evidenceDir.listFiles()?.filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) } ?: return emptyList()
        return if (!phoneNumber.isNullOrBlank()) {
            val clean = phoneNumber.replace(Regex("[^0-9]"), "").takeLast(9)
            val matched = files.filter { it.name.contains(clean) }.sortedByDescending { it.lastModified() }
            if (matched.isNotEmpty()) matched else files.sortedByDescending { it.lastModified() }
        } else {
            files.sortedByDescending { it.lastModified() }
        }
    }

    /**
     * Compresses and encodes an evidence photo file into a Base64 string for broadcast attachment.
     */
    fun encodeFileToBase64(file: File): String? {
        return try {
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return null
            val outputStream = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, outputStream)
            val bytes = outputStream.toByteArray()
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode photo to base64", e)
            null
        }
    }
}
