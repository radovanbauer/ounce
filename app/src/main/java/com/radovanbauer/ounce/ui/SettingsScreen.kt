package com.radovanbauer.ounce.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.mlkit.genai.common.DownloadStatus
import com.radovanbauer.ounce.ai.GeminiEngine
import com.radovanbauer.ounce.ai.NanoEngine
import com.radovanbauer.ounce.data.Goals
import com.radovanbauer.ounce.data.Settings
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val repo = LocalRepo.current
    val s = repo.settings
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

        // ------------------------------------------------------------------ goals
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Daily goals")
                var kcal by remember { mutableStateOf(s.goals.kcal.toString()) }
                var p by remember { mutableStateOf(s.goals.protein.toString()) }
                var c by remember { mutableStateOf(s.goals.carbs.toString()) }
                var f by remember { mutableStateOf(s.goals.fat.toString()) }
                NumberField("Calories (kcal)", kcal, Modifier.fillMaxWidth(), integer = true) { kcal = it }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumberField("Protein g", p, Modifier.weight(1f), integer = true) { p = it }
                    NumberField("Carbs g", c, Modifier.weight(1f), integer = true) { c = it }
                    NumberField("Fat g", f, Modifier.weight(1f), integer = true) { f = it }
                }
                val macroKcal = (p.toIntOrNull() ?: 0) * 4 + (c.toIntOrNull() ?: 0) * 4 + (f.toIntOrNull() ?: 0) * 9
                Text("Macros add up to $macroKcal kcal.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val new = Goals(kcal.toIntOrNull() ?: s.goals.kcal, p.toIntOrNull() ?: s.goals.protein,
                    c.toIntOrNull() ?: s.goals.carbs, f.toIntOrNull() ?: s.goals.fat)
                Button(onClick = { s.updateGoals(new) }, enabled = new != s.goals) { Text(if (new == s.goals) "Saved" else "Save goals") }
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Weight unit", Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    listOf("lb", "kg").forEachIndexed { i, u ->
                        SegmentedButton(s.weightUnit == u, { s.updateWeightUnit(u) }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(u) }
                    }
                }
            }
        }

        // ------------------------------------------------------------------ AI
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("On-device AI")
                Text("Photos are analyzed by Gemini Nano on your phone, so they never leave it. It's free and works offline. " +
                    "Its estimates are rougher, so check the portions.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                var status by remember { mutableStateOf<String?>(null) }
                var canDownload by remember { mutableStateOf(false) }
                suspend fun refresh() {
                    val st = repo.ai.nano.status()
                    canDownload = st == NanoEngine.Status.DOWNLOADABLE
                    status = when (st) {
                        NanoEngine.Status.AVAILABLE -> "Ready ✓"
                        NanoEngine.Status.DOWNLOADABLE -> "Not downloaded"
                        NanoEngine.Status.DOWNLOADING -> "Downloading…"
                        NanoEngine.Status.UNAVAILABLE -> "Not supported on this phone"
                    }
                }
                LaunchedEffect(Unit) { refresh() }
                Text("Status: ${status ?: "checking…"}")
                if (canDownload) Button(onClick = {
                    canDownload = false
                    scope.launch {
                        try {
                            repo.ai.nano.download().collect { d ->
                                status = when (d) {
                                    is DownloadStatus.DownloadStarted -> "Downloading (${d.bytesToDownload / 1_000_000} MB)…"
                                    is DownloadStatus.DownloadProgress -> "Downloaded ${d.totalBytesDownloaded / 1_000_000} MB…"
                                    is DownloadStatus.DownloadFailed -> "Download failed: ${d.e.message}"
                                    else -> "Download complete"
                                }
                            }
                        } catch (e: Exception) { status = "Download failed: ${e.message}" }
                        refresh()
                    }
                }) { Text("Download model") }
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Cloud AI (optional)")
                Text("Use your own Gemini API key for noticeably better estimates. In our tests it was about twice as accurate as on-device. " +
                    "Photos are then sent to Google. Get a key at aistudio.google.com/apikey. Free tier available; " +
                    "otherwise about \$0.003–0.007 per photo, billed by Google to you.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                var key by remember { mutableStateOf(s.apiKey) }
                var model by remember { mutableStateOf(s.cloudModel) }
                var test by remember { mutableStateOf<String?>(null) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Use cloud AI", Modifier.weight(1f))
                    Switch(s.useCloud, { s.setCloud(it, model) }, enabled = s.apiKey.isNotBlank())
                }
                OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Gemini API key") },
                    visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Model") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = key.trim() != s.apiKey || model.trim() != s.cloudModel, onClick = {
                        s.updateApiKey(key)
                        s.setCloud(s.apiKey.isNotBlank(), model) // saving a key turns cloud AI on; the switch turns it off
                        test = null
                    }) { Text("Save") }
                    // tests what's typed in the fields (saved or not)
                    OutlinedButton(enabled = key.isNotBlank(), onClick = {
                        test = "Testing…"
                        scope.launch {
                            test = try {
                                GeminiEngine(model.trim().ifEmpty { Settings.DEFAULT_MODEL }, key.trim()).generate("Reply with the word OK.")
                                "Key works ✓"
                            } catch (e: Exception) { e.message }
                        }
                    }) { Text("Test key") }
                }
                test?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Your key is stored encrypted on this phone and is not included in backups.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ------------------------------------------------------------------ data
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle("Your data")
                Text("Everything stays on this phone. There's no account and no server. Your food log, weights and goals are " +
                    "included in Android's backup to your Google account (Settings › System › Backup) and restore when you set " +
                    "up a new phone. Meal photos aren't backed up.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Nutrition data: USDA FoodData Central. Estimates are approximate.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
