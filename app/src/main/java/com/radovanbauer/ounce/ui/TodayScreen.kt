package com.radovanbauer.ounce.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.radovanbauer.ounce.ai.GeminiEngine
import com.radovanbauer.ounce.ai.NanoEngine
import com.radovanbauer.ounce.data.MealWithItems
import com.radovanbauer.ounce.data.Repo
import com.radovanbauer.ounce.data.Totals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private fun label(date: String): String {
    val d = LocalDate.parse(date)
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> d.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
    }
}

private val PORTIONS = listOf(0.5 to "½×", 0.75 to "¾×", 1.0 to "1×", 1.5 to "1½×", 2.0 to "2×")

/**
 * The home screen, built around the two main jobs: snap a meal (camera button → shutter) and log by text
 * (always-visible composer). Both return immediately; analysis happens in the background and the meal card
 * fills in when it's done. Corrections happen inline: portion chips on the card, or "Adjust" via the composer.
 */
@Composable
fun TodayScreen(
    dateArg: String?,
    focusComposer: Boolean,
    onComposerFocused: () -> Unit,
    onOpenMeal: (Long) -> Unit,
    onOpenCamera: (String) -> Unit,
    onDateChange: (String) -> Unit,
) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val date = dateArg ?: Repo.today()
    val meals by repo.meals.day(date).collectAsState(initial = null)
    val weight by repo.weights.on(date).collectAsState(initial = null)
    val totals = meals?.fold(Totals()) { t, m -> t + m.totals } ?: Totals()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var expanded by remember { mutableStateOf<Long?>(null) }
    var adjusting by remember { mutableStateOf<MealWithItems?>(null) }
    var aiWarning by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(repo.settings.useCloud, repo.settings.apiKey) {
        aiWarning = if (repo.ai.engine() is GeminiEngine) null else when (repo.ai.nano.status()) {
            NanoEngine.Status.AVAILABLE -> null
            NanoEngine.Status.DOWNLOADABLE, NanoEngine.Status.DOWNLOADING ->
                "The on-device AI model isn't downloaded yet. Download it in Settings, or add a Gemini API key."
            NanoEngine.Status.UNAVAILABLE ->
                "On-device AI isn't supported on this phone. Add a Gemini API key in Settings, or add foods manually."
        }
    }

    // background logging results -> snackbar (+ open the freshly logged meal's card)
    LaunchedEffect(Unit) {
        for (e in repo.events) {
            if (!e.isError && e.undoable) {
                expanded = e.mealId
                // the new meal is last in the list; once the snackbar padding is in, bring its expanded card into view
                scope.launch {
                    kotlinx.coroutines.delay(400)
                    listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                }
            }
            val action = when {
                e.undoable -> "Undo"
                e.isError && e.mealId != 0L && repo.failed.containsKey(e.mealId) -> "Retry"
                else -> null
            }
            val r = snackbar.showSnackbar(e.message, actionLabel = action, withDismissAction = action == null,
                duration = if (e.isError || e.undoable) SnackbarDuration.Long else SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) when (action) {
                "Undo" -> repo.meals.meal(e.mealId).first()?.let { repo.deleteMeal(it.meal) }
                "Retry" -> repo.retry(e.mealId)
            }
        }
    }
    // keep a newly added meal (it's last in the list, right above the weight row) in view
    val count = meals?.size
    var lastCount by remember(date) { mutableStateOf<Int?>(null) }
    LaunchedEffect(count) {
        if (count != null && lastCount != null && count > lastCount!!)
            listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
        lastCount = count
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // extra room at the end while a snackbar is up, so it doesn't cover the last card's buttons
            val bottomPad by androidx.compose.animation.core.animateDpAsState(
                if (snackbar.currentSnackbarData != null) 88.dp else 16.dp, label = "pad")
            LazyColumn(Modifier.weight(1f), state = listState,
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = bottomPad),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onDateChange(LocalDate.parse(date).minusDays(1).toString()) }) { Text("‹", fontSize = 28.sp) }
                        Text(label(date), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        TextButton(onClick = { onDateChange(LocalDate.parse(date).plusDays(1).toString()) },
                            enabled = date < Repo.today()) { Text("›", fontSize = 28.sp) }
                    }
                }
                item { SummaryCard(totals, repo.settings.goals) }
                aiWarning?.let { w ->
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                            Text(w, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                val list = meals
                if (list != null && list.isEmpty()) item {
                    EmptyNote("Nothing logged yet. Tap 📷 to snap your meal, or type what you ate below.")
                }
                items(list ?: emptyList(), key = { it.meal.id }) { m ->
                    MealCard(m, expanded = expanded == m.meal.id,
                        onToggle = { expanded = if (expanded == m.meal.id) null else m.meal.id },
                        onPortion = { f -> scope.launch { repo.setPortion(m.meal.id, f) } },
                        onAdjust = { adjusting = m },
                        onOpen = { onOpenMeal(m.meal.id) })
                }
                if (list != null) item {
                    TextButton(onClick = { scope.launch { onOpenMeal(repo.createEmptyMeal(date, defaultTime(date))) } }) {
                        Text("+ Add foods manually")
                    }
                }
                item { WeightRow(date, weight?.kg?.let { repo.fromKg(it) }, repo.settings.weightUnit) { v -> scope.launch { repo.setWeight(date, v) } } }
            }
            Composer(
                date = date,
                adjusting = adjusting,
                focus = focusComposer,
                onFocused = onComposerFocused,
                onCancelAdjust = { adjusting = null },
                onCamera = { onOpenCamera(date) },
                onSend = { text ->
                    val target = adjusting
                    if (target != null) { repo.adjustAsync(target.meal.id, text); adjusting = null }
                    else repo.logText(text, date, defaultTime(date))
                },
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 76.dp).imePadding())
    }
}

private fun defaultTime(date: String) = if (date == Repo.today()) Repo.now() else "12:00"

/** Docked input: camera button + "what did you eat?" (or a correction for the selected meal). */
@Composable
private fun Composer(
    date: String,
    adjusting: MealWithItems?,
    focus: Boolean,
    onFocused: () -> Unit,
    onCancelAdjust: () -> Unit,
    onCamera: () -> Unit,
    onSend: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(focus) { if (focus) { focusRequester.requestFocus(); onFocused() } }
    LaunchedEffect(adjusting?.meal?.id) { if (adjusting != null) focusRequester.requestFocus() }
    fun send() {
        val t = text.trim()
        if (t.isEmpty()) return
        onSend(t)
        text = ""
        focusManager.clearFocus()
    }
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().imePadding()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            AnimatedVisibility(adjusting != null) {
                InputChip(selected = true, onClick = onCancelAdjust,
                    label = { Text("Correcting: ${adjusting?.meal?.title ?: ""}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = { Text("✕") }, modifier = Modifier.padding(bottom = 6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledIconButton(onClick = onCamera, modifier = Modifier.size(52.dp)) { Text("📷", fontSize = 22.sp) }
                OutlinedTextField(
                    text, { v ->
                        // a hardware/IME Enter that arrives as a newline should send, not grow the field
                        if (v.endsWith("\n")) { text = v.trimEnd('\n'); send() } else text = v
                    },
                    Modifier.weight(1f).focusRequester(focusRequester),
                    placeholder = {
                        Text(when {
                            adjusting != null -> "e.g. half the rice, no dressing"
                            date == Repo.today() -> "What did you eat?"
                            else -> "What did you eat on ${label(date)}?"
                        }, maxLines = 1)
                    },
                    shape = RoundedCornerShape(26.dp),
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                )
                if (text.isNotBlank()) FilledIconButton(onClick = { send() }, modifier = Modifier.size(52.dp)) { Text("➤", fontSize = 20.sp) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MealCard(
    m: MealWithItems,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPortion: (Double) -> Unit,
    onAdjust: () -> Unit,
    onOpen: () -> Unit,
) {
    val repo = LocalRepo.current
    val busy = repo.busy[m.meal.id]
    val error = repo.failed[m.meal.id]
        ?: if (busy == null && m.items.isEmpty() && m.meal.source != "manual") "No foods found yet" else null
    Card(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        Column {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                val img = rememberPhoto(repo.photoDir, m.meal.photo, 200)
                Box(Modifier.size(60.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center) {
                    if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Text(if (m.meal.source == "text") "✏️" else "🍴", fontSize = 22.sp)
                }
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(if (busy == "Analyzing…") "Analyzing…" else m.meal.title, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${m.meal.mealType} · ${m.meal.time}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    when {
                        busy != null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp))
                        error != null -> Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        else -> MacroLine(m.totals)
                    }
                }
            }
            AnimatedVisibility(expanded && busy == null) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    m.sortedItems.forEach { i ->
                        Row {
                            Text(i.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (i.grams > 0) Text("${i.grams.fmt0()} g", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp))
                            Text("${i.kcal.fmt0()} kcal", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (m.items.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PORTIONS.forEach { (f, l) ->
                            FilterChip(m.meal.portion == f, { onPortion(f) }, label = { Text(l, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1, softWrap = false) },
                                modifier = Modifier.weight(1f))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (error != null && repo.failed.containsKey(m.meal.id) || m.items.isEmpty() && m.meal.source != "manual")
                            FilledTonalButton(onClick = { repo.retry(m.meal.id) }) { Text("Retry") }
                        if (m.items.isNotEmpty()) FilledTonalButton(onClick = onAdjust) { Text("✏️ Correct") }
                        OutlinedButton(onClick = onOpen) { Text("Details") }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeightRow(date: String, value: Double?, unit: String, onSave: (Double) -> Unit) {
    var text by remember(date, value) { mutableStateOf(value?.let { "%.1f".format(it) } ?: "") }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("⚖️  Weight", Modifier.weight(1f))
            NumberField(unit, text, Modifier.width(110.dp)) { text = it }
            TextButton(onClick = { text.toDoubleOrNull()?.takeIf { it > 0 }?.let(onSave) },
                enabled = text.toDoubleOrNull()?.let { it > 0 && "%.1f".format(it) != value?.let { v -> "%.1f".format(v) } } == true) { Text("Save") }
        }
    }
}
