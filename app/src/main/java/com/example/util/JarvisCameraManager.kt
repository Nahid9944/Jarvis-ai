package com.example.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.ai.ScreenVisualAnalyzer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

object JarvisCameraManager {

    var activeCameraControl: CameraControl? = null
    var activeImageCapture: ImageCapture? = null
    var activeLensFacing: Int = CameraSelector.LENS_FACING_BACK
    var lastCapturedBitmap: Bitmap? = null

    fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun captureFrame(context: Context): Bitmap? = withContext(Dispatchers.IO) {
        if (!hasCameraPermission(context)) return@withContext null

        val capture = activeImageCapture
        if (capture != null) {
            return@withContext suspendCancellableCoroutine { cont ->
                val tempFile = File(context.cacheDir, "jarvis_cam_frame_${System.currentTimeMillis()}.jpg")
                val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()
                capture.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            val bmp = BitmapFactory.decodeFile(tempFile.absolutePath)
                            lastCapturedBitmap = bmp
                            tempFile.delete()
                            if (cont.isActive) cont.resume(bmp)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            }
        }

        // Return last cached or null
        lastCapturedBitmap
    }

    suspend fun analyzeCameraView(
        context: Context,
        apiKey: String,
        query: String = "ক্যামেরার সামনে কি দেখা যাচ্ছে বিস্তারিত বাংলায় বর্ণনা করো।"
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!hasCameraPermission(context)) {
            return@withContext Pair(false, "ক্যামেরা পারমিশন (CAMERA) অনুমোদিত নয়। অনুগ্রহ করে পারমিশন সেন্টারে গিয়ে ক্যামেরা চালু করুন।")
        }

        val bitmap = captureFrame(context) ?: lastCapturedBitmap
        if (bitmap == null) {
            return@withContext Pair(
                false,
                "ক্যামেরা প্রিভিউ এই মুহূর্তে সক্রিয় নেই। অনুগ্রহ করে প্রথমে 'ক্যামেরা চালু করো' বলে ক্যামেরা স্ক্রিন ওপেন করুন।"
            )
        }

        val analysis = ScreenVisualAnalyzer.analyzeBitmap(bitmap, apiKey, query)
        Pair(true, analysis.summaryBengali)
    }

    suspend fun readTextFromCamera(
        context: Context,
        apiKey: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!hasCameraPermission(context)) {
            return@withContext Pair(false, "ক্যামেরা পারমিশন প্রয়োজন।")
        }

        val bitmap = captureFrame(context) ?: lastCapturedBitmap
        if (bitmap == null) {
            return@withContext Pair(false, "ক্যামেরা ফ্রেম পাওয়া যায়নি। ক্যামেরা ভিশন চালু করুন।")
        }

        // Try local ML Kit OCR first
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bitmap, 0)

        val mlKitText: String = suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (cont.isActive) cont.resume(visionText.text)
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume("")
                }
        }

        if (mlKitText.isNotBlank()) {
            Pair(true, "ক্যামেরা টেক্সট রিডার (OCR):\n$mlKitText")
        } else {
            // Fallback to Gemini Multimodal Vision for Bengali / complex scripts
            val analysis = ScreenVisualAnalyzer.analyzeBitmap(
                bitmap,
                apiKey,
                "Extract and transcribe all printed and handwritten text visible in this image in Bengali or English."
            )
            Pair(true, "ক্যামেরা টেক্সট এক্সট্রাকশন (Gemini Vision):\n${analysis.summaryBengali}")
        }
    }

    suspend fun detectObjects(
        context: Context,
        apiKey: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        analyzeCameraView(
            context = context,
            apiKey = apiKey,
            query = "এই ছবিতে দৃশ্যমান প্রধান বস্তুসমূহ (Objects), তাদের অবস্থান ও বিবরণ বুলেট পয়েন্টে তালিকা আকারে দাও।"
        )
    }

    fun setZoom(zoomRatio: Float): Pair<Boolean, String> {
        val control = activeCameraControl
        return if (control != null) {
            control.setZoomRatio(zoomRatio.coerceIn(1.0f, 5.0f))
            Pair(true, "ক্যামেরা জুম ${zoomRatio}x নির্ধারণ করা হয়েছে।")
        } else {
            Pair(false, "ক্যামেরা লাইভ কন্ট্রোল সক্রিয় নেই। ক্যামেরা স্ক্রিন ওপেন করুন।")
        }
    }

    fun switchLens(): Pair<Boolean, String> {
        activeLensFacing = if (activeLensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        val label = if (activeLensFacing == CameraSelector.LENS_FACING_FRONT) "সামনের (Front)" else "পেছনের (Back)"
        return Pair(true, "ক্যামেরা লেন্স পরিবর্তন করে $label ক্যামেরায় সুইচ করা হয়েছে।")
    }
}
