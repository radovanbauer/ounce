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
import com.radovanbauer.ounce.data.exportCsv
import com.radovanbauer.ounce.data.exportTables
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

        // ------------------------------------------------------------------ export
        ExportCard()

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

/** CSV export via the share sheet, and a Google Sheets copy that is updated in place on each sync. */
@Composable
private fun ExportCard() {
    val repo = LocalRepo.current
    val s = repo.settings
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun syncWith(token: String?) {
        if (token == null) { status = "Google didn't return access. Try again."; busy = false; return }
        scope.launch {
            status = try {
                val r = com.radovanbauer.ounce.data.SheetsExport.sync(token, s.sheetId, repo.exportTables())
                s.updateSheet(r.spreadsheetId)
                "Synced ✓"
            } catch (e: Exception) { e.message }
            busy = false
        }
    }
    val consent = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) { res ->
        try {
            syncWith(com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(ctx)
                .getAuthorizationResultFromIntent(res.data).accessToken)
        } catch (e: Exception) { status = "Google access wasn't granted."; busy = false }
    }
    fun startSheetsSync() {
        busy = true; status = "Connecting to Google…"
        val request = com.google.android.gms.auth.api.identity.AuthorizationRequest.builder()
            // drive.file: only spreadsheets this app creates, nothing else in the user's Drive
            .setRequestedScopes(listOf(com.google.android.gms.common.api.Scope("https://www.googleapis.com/auth/drive.file")))
            .build()
        com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(ctx).authorize(request)
            .addOnSuccessListener { r ->
                val pending = r.pendingIntent
                if (r.hasResolution() && pending != null)
                    consent.launch(androidx.activity.result.IntentSenderRequest.Builder(pending.intentSender).build())
                else syncWith(r.accessToken)
            }
            .addOnFailureListener { e ->
                busy = false
                val code = (e as? com.google.android.gms.common.api.ApiException)?.statusCode
                status = if (code == 10) "Google Sheets isn't set up for this build yet (missing OAuth client for this app's signing key)."
                         else "Couldn't connect to Google: ${e.message}"
            }
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Export")
            Text("Your whole log as three tables: daily totals, every food item, and weights.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = {
                    scope.launch {
                        try {
                            val uris = ArrayList(repo.exportCsv(ctx).map {
                                androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", it)
                            })
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                                type = "text/csv"
                                putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            ctx.startActivity(android.content.Intent.createChooser(send, "Export CSV"))
                        } catch (e: Exception) { status = "Export failed: ${e.message}" }
                    }
                }) { Text("Share CSV") }
                Button(enabled = !busy, onClick = { startSheetsSync() }) {
                    Text(if (s.sheetId == null) "Export to Google Sheets" else "Sync to Google Sheets")
                }
            }
            s.sheetId?.let { id ->
                val at = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                    .format(java.util.Date(s.sheetSyncedAt))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Last synced $at", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://docs.google.com/spreadsheets/d/$id/edit")))
                    }) { Text("Open sheet") }
                }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
