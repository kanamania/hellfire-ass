package com.hellfire.ass.guard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.util.Base64
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Bounded, sequential captures. Image bytes stay in memory until locally encrypted. */
class GuardSensors(private val context: Context, private val owner: LifecycleOwner) {
    suspend fun location(): JSONObject = withTimeout(12_000) {
        check(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { "LOCATION_PERMISSION_MISSING" }
        @Suppress("MissingPermission")
        val result = suspendCancellableCoroutine<android.location.Location?> { continuation ->
            val cancel = CancellationTokenSource()
            continuation.invokeOnCancellation { cancel.cancel() }
            LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancel.token)
                .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
        } ?: error("LOCATION_UNAVAILABLE")
        check(System.currentTimeMillis() - result.time in 0..120_000) { "LOCATION_STALE" }
        JSONObject().put("latitude", result.latitude).put("longitude", result.longitude)
            .put("accuracy", result.accuracy.toDouble()).put("timestamp", result.time / 1000)
    }

    suspend fun images(errors: JSONArray): JSONArray = withContext(Dispatchers.Main) {
        val results = JSONArray()
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            errors.put("CAMERA_PERMISSION_MISSING"); return@withContext results
        }
        val future = ProcessCameraProvider.getInstance(context)
        val provider = try { withTimeout(10_000) {
            suspendCancellableCoroutine<ProcessCameraProvider> { continuation ->
                future.addListener({
                    try { if (continuation.isActive) continuation.resume(future.get()) }
                    catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                }, ContextCompat.getMainExecutor(context))
            }
        } } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            errors.put("CAMERA_PROVIDER_TIMEOUT"); return@withContext results
        } catch (_: Exception) { errors.put("CAMERA_PROVIDER_UNAVAILABLE"); return@withContext results }
        for ((name, selector) in listOf("front" to CameraSelector.DEFAULT_FRONT_CAMERA, "back" to CameraSelector.DEFAULT_BACK_CAMERA)) {
            try {
                check(provider.hasCamera(selector)) { "CAMERA_MISSING" }
                @Suppress("DEPRECATION")
                val capture = ImageCapture.Builder().setTargetResolution(Size(640, 480))
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                provider.bindToLifecycle(owner, selector, capture)
                val bytes = withTimeout(8_000) { suspendCancellableCoroutine<ByteArray> { continuation ->
                    capture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            try {
                                check(image.format == ImageFormat.JPEG) { "CAMERA_FORMAT_UNSUPPORTED" }
                                val buffer = image.planes[0].buffer
                                check(buffer.remaining() <= 1024 * 1024) { "CAMERA_IMAGE_TOO_LARGE" }
                                val jpeg = ByteArray(buffer.remaining()).also(buffer::get)
                                if (continuation.isActive) continuation.resume(jpeg) else jpeg.fill(0)
                            } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                            finally { image.close() }
                        }
                        override fun onError(exception: ImageCaptureException) {
                            if (continuation.isActive) continuation.resumeWithException(exception)
                        }
                    })
                } }
                try { results.put(JSONObject().put("facing", name).put("jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP))) }
                finally { bytes.fill(0) }
            } catch (e: CancellationException) {
                if (e !is TimeoutCancellationException) throw e
                errors.put("${name.uppercase()}_CAMERA_TIMEOUT")
            } catch (_: Exception) { errors.put("${name.uppercase()}_CAMERA_UNAVAILABLE") }
            finally { provider.unbindAll() }
        }
        results
    }
}
