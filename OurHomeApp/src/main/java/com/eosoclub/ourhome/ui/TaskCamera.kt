package com.eosoclub.ourhome.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.Size
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.scale
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.eosoclub.ourhome.data.CHECK_PHOTO_MAX_BYTES
import com.eosoclub.ourhome.data.CHECK_PHOTO_QUALITIES
import com.eosoclub.ourhome.data.CheckText
import com.eosoclub.ourhome.data.checkPhotoSize
import com.eosoclub.ourhome.data.scaledBeforeTurnSize
import com.eosoclub.ourhome.data.normalizeRotation
import com.eosoclub.ourhome.data.rotatePixels
import com.eosoclub.ourhome.data.rotatedSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

private const val TAG = "TaskCamera"

/**
 * A check photo as sent: [source] scaled so the long edge of the result is at
 * most 1600 px, then turned by [rotationDegrees] (the server drops EXIF, so
 * the turn has to be in the pixels), then JPEG at quality 80 (lower only if a
 * busy photo would still be over the server's limit). Scaling before turning
 * gives the same picture as the spec's turn-then-scale ([scaledBeforeTurnSize],
 * TaskChecksTest), while only the small image is turned: memory on low-end
 * phones. Takes [source] over: it is recycled as soon as it isn't needed
 * (recycling it again afterwards is harmless). Sizes: [checkPhotoSize].
 */
internal fun encodeCheckPhoto(source: Bitmap, rotationDegrees: Int): ByteArray {
    val degrees = normalizeRotation(rotationDegrees)
    val small = scaledBeforeTurnSize(source.width, source.height, degrees)
    val scaled = if (source.width == small.width && source.height == small.height) source else source.scale(small.width, small.height)
    // The full-size frame is done with.
    if (scaled !== source) source.recycle()
    val turned = if (degrees == 0) {
        scaled
    } else {
        // The turn itself is the pure, unit-tested rotatePixels (TaskChecksTest).
        val width = scaled.width
        val height = scaled.height
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        scaled.recycle()
        val size = rotatedSize(width, height, degrees)
        Bitmap.createBitmap(rotatePixels(pixels, width, height, degrees), size.width, size.height, Bitmap.Config.ARGB_8888)
    }
    try {
        var bytes = ByteArray(0)
        for (quality in CHECK_PHOTO_QUALITIES) {
            val out = ByteArrayOutputStream()
            turned.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bytes = out.toByteArray()
            if (bytes.size <= CHECK_PHOTO_MAX_BYTES) break
        }
        return bytes
    } finally {
        turned.recycle()
    }
}

/** A JPEG for the screen, decoded at most [maxEdge] px on its long side (whole steps of 2). */
internal fun decodePhoto(bytes: ByteArray, maxEdge: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

/**
 * Takes a check photo inside Our Home with CameraX: the picture goes to
 * memory only (never the gallery or another camera app), is turned and scaled
 * by [encodeCheckPhoto], and [onTaken] gets the JPEG. The CAMERA permission is
 * asked for before this opens.
 */
@Composable
internal fun TaskCameraDialog(onTaken: (ByteArray) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val taken by rememberUpdatedState(onTaken)
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val preview = remember { Preview.Builder().build().apply { setSurfaceProvider { surfaceRequest = it } } }
    // Around 2048 x 1536 is plenty for 1600 px and keeps the bitmap small in memory.
    val capture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(2048, 1536), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                    )
                    .build(),
            )
            .build()
    }
    // Turning and encoding run off the main thread.
    val worker = remember { Executors.newSingleThreadExecutor() }

    LaunchedEffect(Unit) {
        try {
            val cameras = ProcessCameraProvider.awaitInstance(context)
            val selector = if (runCatching { cameras.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }
            cameras.unbind(preview, capture)
            cameras.bindToLifecycle(lifecycleOwner, selector, preview, capture)
            provider = cameras
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "camera didn't start", e)
            problem = "Couldn't start the camera."
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            provider?.unbind(preview, capture)
            worker.shutdown()
        }
    }

    fun shoot() {
        if (busy || provider == null) return
        busy = true
        problem = null
        capture.takePicture(
            worker,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val result = runCatching {
                        val rotation = image.imageInfo.rotationDegrees
                        val bitmap = try { image.toBitmap() } finally { image.close() }
                        try { encodeCheckPhoto(bitmap, rotation) } finally { bitmap.recycle() }
                    }
                    scope.launch {
                        busy = false
                        result.onSuccess { taken(it) }.onFailure {
                            Log.w(TAG, "photo not encoded", it)
                            problem = "Couldn't take the photo. Try again."
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.w(TAG, "photo not taken", exception)
                    scope.launch {
                        busy = false
                        problem = "Couldn't take the photo. Try again."
                    }
                }
            },
        )
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            surfaceRequest?.let { CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize()) }
            IconButton(
                onClick = onDismiss,
                enabled = !busy,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp),
            ) { Icon(Icons.Filled.Close, contentDescription = CheckText.CANCEL, tint = Color.White) }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Always there (empty when fine), so the button below never moves.
                Text(
                    problem ?: "",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(72.dp)
                            .border(4.dp, Color.White, CircleShape)
                            .padding(8.dp)
                            .background(if (provider != null && !busy) Color.White else Color.Gray, CircleShape)
                            .semantics { contentDescription = CheckText.TAKE_PHOTO }
                            .clickable(enabled = provider != null && !busy, role = Role.Button) { shoot() },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = Color.Black)
                    }
                }
            }
        }
    }
}
