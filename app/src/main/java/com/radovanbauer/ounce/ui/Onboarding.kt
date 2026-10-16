package com.radovanbauer.ounce.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.radovanbauer.ounce.ai.ModelCatalog
import com.radovanbauer.ounce.ai.NanoEngine
import com.radovanbauer.ounce.ai.cloudEngine
import com.radovanbauer.ounce.data.Provider
import kotlinx.coroutines.launch

private enum class Step { WELCOME, CHOOSE, GEMINI, DONE }

/** Gemini API keys: classic "AIza…" keys and the newer "AQ.…" format. */
private val GEMINI_KEY = Regex("""(AIza[0-9A-Za-z_-]{35}|AQ\.[0-9A-Za-z_.-]{20,200})""")

/**
 * First-run setup, built so anyone can finish it: welcome, choose how meals are estimated, and a three-step
 * guide to a free Gemini key that picks the key up from the clipboard by itself.
 * [startAtGemini] lets Settings reopen just the Gemini guide.
 */
@Composable
fun Onboarding(onDone: () -> Unit, startAtGemini: Boolean = false) {
    var step by remember { mutableStateOf(if (startAtGemini) Step.GEMINI else Step.WELCOME) }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (step) {
                Step.WELCOME -> Welcome { step = Step.CHOOSE }
                Step.CHOOSE -> Choose(onGemini = { step = Step.GEMINI }, onOnDevice = onDone)
                Step.GEMINI -> GeminiGuide(onBack = { if (startAtGemini) onDone() else step = Step.CHOOSE },
                    onConnected = { step = Step.DONE })
                Step.DONE -> Done(onDone)
            }
        }
    }
}

@Composable
private fun RingLogo(size: Int = 88) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(Color(0xFF16A34A)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size((size * 0.55).dp)) {
            val w = this.size.width * 0.17f
            val inset = w / 2
            val sz = Size(this.size.width - w, this.size.height - w)
            drawArc(Color.White.copy(alpha = 0.35f), 0f, 360f, false, Offset(inset, inset), sz, style = Stroke(w))
            drawArc(Color.White, -90f, 270f, false, Offset(inset, inset), sz, style = Stroke(w, cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun Welcome(onNext: () -> Unit) {
    Spacer(Modifier.height(24.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { RingLogo() }
    Text("Welcome to Ounce", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text("Snap a photo of your meal and Ounce figures out the calories, protein, carbs and fat. " +
        "Got something wrong? Just tap it or type a correction.",
        style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Text("Estimates come from AI and can be off, so give them a quick look. Ounce isn't a medical device and " +
            "doesn't give medical or dietary advice.",
            Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(Modifier.height(8.dp))
    BigButton("Get started", onNext)
}

@Composable
private fun Choose(onGemini: () -> Unit, onOnDevice: () -> Unit) {
    val repo = LocalRepo.current
    var nano by remember { mutableStateOf<NanoEngine.Status?>(null) }
    LaunchedEffect(Unit) { nano = repo.ai.nano.status() }
    Text("How should Ounce\nread your meals?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text("You can change this anytime in Settings.", color = MaterialTheme.colorScheme.onSurfaceVariant)

    OptionCard(
        title = "Google Gemini", badge = "Recommended",
        lines = listOf("Best accuracy", "Free with a Google account", "About 1 minute to set up"),
        note = "For ages 18+. Meal photos are sent to Google to estimate them.",
        highlighted = true, onClick = onGemini,
    )
    val onDevice = when (nano) {
        NanoEngine.Status.UNAVAILABLE -> null
        null -> "Checking this phone…"
        else -> "Rougher estimates, so you'll fix more by hand"
    }
    if (onDevice != null) OptionCard(
        title = "On this phone only", badge = null,
        lines = listOf("Photos never leave your phone", onDevice),
        note = null, highlighted = false, onClick = onOnDevice,
    ) else OptionCard(
        title = "Skip for now", badge = null,
        lines = listOf("This phone can't run AI on-device", "You can still scan barcodes and add foods by hand"),
        note = null, highlighted = false, onClick = onOnDevice,
    )
}

@Composable
private fun OptionCard(title: String, badge: String?, lines: List<String>, note: String?, highlighted: Boolean, onClick: () -> Unit) {
    val border = if (highlighted) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    OutlinedCard(onClick = onClick, border = border ?: CardDefaults.outlinedCardBorder(), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                badge?.let {
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary) {
                        Text(it, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            lines.forEach { Text("•  $it", style = MaterialTheme.typography.bodyLarge) }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun GeminiGuide(onBack: () -> Unit, onConnected: () -> Unit) {
    val repo = LocalRepo.current
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var opened by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf("") }

    fun connect(key: String) {
        if (checking) return
        checking = true; error = null
        scope.launch {
            try {
                val options = ModelCatalog.list(Provider.GEMINI, key)
                val model = ModelCatalog.recommend(Provider.GEMINI, options) ?: Provider.GEMINI.defaultModel
                cloudEngine(Provider.GEMINI, key, model).generate("Reply with the word OK.")
                val s = repo.settings
                s.updateKey(Provider.GEMINI, key)
                s.updateModel(Provider.GEMINI, model)
                s.selectProvider(Provider.GEMINI)
                s.setCloud(true)
                clearClipboard(ctx)
                onConnected()
            } catch (e: Exception) {
                error = "That key didn't work: ${e.message}"
            }
            checking = false
        }
    }

    // when the user comes back from the browser, look for a copied key
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && opened && !checking) clipboardKey(ctx)?.let { connect(it) }
        }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }

    TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("‹ Back") }
    Text("Get your free Gemini key", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text("It's like a password that lets Ounce use Google's AI with your account. You only do this once.",
        color = MaterialTheme.colorScheme.onSurfaceVariant)

    GuideStep(1, "Open Google AI Studio", "Sign in with your Google account if it asks, and tap through the welcome screens.") {
        BigButton(if (opened) "Open it again" else "Open Google AI Studio") {
            opened = true
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
        }
    }
    GuideStep(2, "Tap “Create API key”, then “Copy”",
        "If it asks you to pick a project, just choose the one that's already there (or tap Create).")
    GuideStep(3, "Come back to Ounce", "Ounce finds the copied key by itself and checks that it works.")

    when {
        checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
            Text("   Checking your key…", style = MaterialTheme.typography.bodyLarge)
        }
        error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
    }

    HorizontalDivider()
    Text("Didn't work automatically? Paste the key here:", style = MaterialTheme.typography.bodyMedium)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(manual, { manual = it.trim() }, Modifier.weight(1f), singleLine = true,
            placeholder = { Text("Paste key") }, visualTransformation = PasswordVisualTransformation())
        FilledTonalButton(onClick = {
            val k = manual.ifBlank { clipboardText(ctx) }.let { GEMINI_KEY.find(it)?.value ?: it.trim() }
            if (k.isBlank()) error = "Copy the key in AI Studio first, then tap here." else connect(k)
        }, enabled = !checking) { Text(if (manual.isBlank()) "Paste" else "Use key") }
    }
}

@Composable
private fun GuideStep(n: Int, title: String, body: String, content: @Composable () -> Unit = {}) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Text("$n", color = MaterialTheme.colorScheme.onPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun Done(onDone: () -> Unit) {
    Spacer(Modifier.height(40.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Text("✓", color = MaterialTheme.colorScheme.onPrimary, fontSize = 44.sp)
        }
    }
    Text("You're all set!", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text("Ounce will use Google Gemini. Tap 📷 to snap your first meal, or type what you ate.",
        style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    BigButton("Start tracking", onDone)
}

@Composable
private fun BigButton(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(text, fontSize = 17.sp) }
}

private fun clipboardText(ctx: Context): String = runCatching {
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
}.getOrDefault("")

private fun clipboardKey(ctx: Context): String? = GEMINI_KEY.find(clipboardText(ctx))?.value

/** Don't leave the key sitting in the clipboard where other apps could read it. */
private fun clearClipboard(ctx: Context) {
    runCatching { ctx.getSystemService(ClipboardManager::class.java).clearPrimaryClip() }
}
