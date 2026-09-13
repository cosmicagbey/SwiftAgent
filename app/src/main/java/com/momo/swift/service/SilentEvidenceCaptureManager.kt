package com.momo.swift.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.FileProvider
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

            // Allow camera sensor brief moment to stabilize exposure
            delay(350L)

            val capturedFiles = mutableListOf<File>()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val cleanPhone = phoneNumber.replace(Regex("[^0-9]"), "").takeLast(10)

            // Capture burst shots with perceptible tactile tick on each snap
            for (i in 1..burstCount) {
                val photoFile = File(evidenceDir, "evidence_${cleanPhone}_${timestamp}_$i.jpg")
                val success = captureSinglePhoto(context, imageCapture, photoFile)
                if (success && photoFile.exists() && photoFile.length() > 0) {
                    capturedFiles.add(photoFile)
                    Log.d(TAG, "Captured evidence frame $i: ${photoFile.absolutePath} (${photoFile.length()} bytes)")
                }
                // Solid, distinct tick felt by the agent for each picture snapped
                triggerPhotoCapturedTick(context, i)
                if (i < burstCount) {
                    delay(delayBetweenMs)
                }
            }

            // Unbind camera immediately after burst is complete
            cameraProvider.unbindAll()

            // Distinct triple-pulse buzz telling agent: "Capture complete! You can pull the phone back."
            triggerCaptureFinishedHaptic(context)

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
                vibrator.vibrate(VibrationEffect.createOneShot(50L, 120))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50L)
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
     * Cue 3: Crisp, distinct tick felt on EACH photo snap so the agent tracks progress.
     */
    fun triggerPhotoCapturedTick(context: Context, photoIndex: Int) {
        try {
            val vibrator = getVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(55L, 160))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(55L)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Photo tick suppressed: ${e.message}")
        }
    }

    /**
     * Cue 4: Distinct triple-pulse buzz telling agent:
     * "All pictures are taken! Safely pull the phone back."
     */
    fun triggerCaptureFinishedHaptic(context: Context) {
        try {
            val vibrator = getVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(
                    longArrayOf(0, 75, 70, 75, 70, 140),
                    intArrayOf(0, 180, 0, 180, 0, 240),
                    -1
                )
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 75, 70, 75, 70, 140), -1)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Completion haptic suppressed: ${e.message}")
        }
    }

    /**
     * Backward-compatibility wrapper for completion tick.
     */
    fun triggerCompletionTick(context: Context) {
        triggerCaptureFinishedHaptic(context)
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

    /**
     * Shares one or more evidence photos to external apps (WhatsApp, Email, Telegram, etc.)
     * using Android FileProvider.
     */
    fun sharePhotos(context: Context, photos: List<File>, caption: String = "Suspected MoMo Fraudster Evidence") {
        if (photos.isEmpty()) return
        try {
            val uris = photos.mapNotNull { file ->
                try {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create URI for ${file.name}", e)
                    null
                }
            }
            if (uris.isEmpty()) return

            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                    putExtra(Intent.EXTRA_TEXT, caption)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "image/jpeg"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    putExtra(Intent.EXTRA_TEXT, caption)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            context.startActivity(Intent.createChooser(intent, "Share Evidence Photos via"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch share sheet", e)
        }
    }

    /**
     * Safely deletes a photo file from the evidence directory.
     */
    fun deletePhoto(file: File): Boolean {
        return try {
            if (file.exists()) file.delete() else false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete file ${file.name}", e)
            false
        }
    }
}
