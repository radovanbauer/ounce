package com.radovanbauer.ounce.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.radovanbauer.ounce.data.Repo

/**
 * One-tap meal capture: shutter -> photo is handed to the background logger -> straight back to Today.
 * [date] is the day being viewed; camera shots are logged "now" when that's today.
 */
@Composable
fun CameraScreen(date: String, onDone: () -> Unit) {
    val repo = LocalRepo.current
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(false) }
    val flashAlpha by animateFloatAsState(if (flash) 0.8f else 0f, tween(if (flash) 40 else 250), label = "flash")
    val logDate = date
    val logTime = if (date == Repo.today()) Repo.now() else "12:00"

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) { repo.logPhoto(uri, logDate, logTime, fromGallery = true); onDone() }
    }
    // fallback when the camera permission is denied: the system camera app (needs no permission)
    var systemCaptureFile by remember { mutableStateOf<java.io.File?>(null) }
    val systemCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = systemCaptureFile
        if (ok && f != null) repo.logPhoto(Uri.fromFile(f), logDate, logTime, fromGallery = false, cleanup = f) else f?.delete()
        onDone()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok; denied = !ok
    }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(denied) {
        if (denied) {
            val f = repo.newCaptureFile()
            systemCaptureFile = f
            systemCamera.launch(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f))
        }
    }

    val controller = remember {
        LifecycleCameraController(ctx).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        }
    }
    DisposableEffect(granted) {
        if (granted) controller.bindToLifecycle(lifecycle)
        onDispose { controller.unbind() }
    }

    fun shoot() {
        if (capturing) return
        capturing = true
        flash = true
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        val file = repo.newCaptureFile()
        controller.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(ctx),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    repo.logPhoto(Uri.fromFile(file), logDate, logTime, fromGallery = false, cleanup = file)
                    onDone()
                }
                override fun onError(e: ImageCaptureException) {
                    file.delete()
                    capturing = false
                    flash = false
                }
            })
    }
    LaunchedEffect(flash) { if (flash) { kotlinx.coroutines.delay(60); flash = false } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) AndroidView({ PreviewView(it).apply { this.controller = controller; scaleType = PreviewView.ScaleType.FILL_CENTER } },
            Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().alpha(flashAlpha).background(Color.White))

        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDone) { Text("✕", color = Color.White, fontSize = 22.sp) }
            Spacer(Modifier.weight(1f))
            if (date != Repo.today()) Text("Logging to ${date}", color = Color.White, fontSize = 13.sp,
                modifier = Modifier.padding(end = 12.dp))
        }

        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 28.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f))
                .clickable { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                contentAlignment = Alignment.Center) { Text("🖼", fontSize = 24.sp) }
            Box(Modifier.size(84.dp).clip(CircleShape).border(5.dp, Color.White, CircleShape).padding(9.dp)
                .clip(CircleShape).background(if (capturing) Color.White.copy(alpha = 0.5f) else Color.White)
                .clickable(enabled = granted && !capturing) { shoot() })
            Spacer(Modifier.size(56.dp))
        }
        if (!granted && !denied) Text("Allow camera access to snap meals", Modifier.align(Alignment.Center), color = Color.White)
    }
}
