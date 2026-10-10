package com.radovanbauer.ounce.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.radovanbauer.ounce.data.Food
import com.radovanbauer.ounce.data.FoodDb
import com.radovanbauer.ounce.data.Meal
import com.radovanbauer.ounce.data.MealItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.radovanbauer.ounce.data.Repo
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val TYPES = listOf("breakfast", "lunch", "dinner", "snack")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MealScreen(id: Long, onBack: () -> Unit) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val data by repo.meals.meal(id).collectAsState(initial = null)
    val chat by repo.meals.chat(id).collectAsState(initial = emptyList())
    val error = repo.failed[id]
    var editing by remember { mutableStateOf<Long?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var localBusy by remember { mutableStateOf<String?>(null) }
    val busy = repo.busy[id] ?: localBusy
    val snackbar = remember { SnackbarHostState() }
    DisposableEffect(id) { onDispose { repo.discardIfEmpty(id) } }
    LaunchedEffect(Unit) {
        for (e in repo.events) snackbar.showSnackbar(e.message, withDismissAction = true,
            duration = if (e.isError) SnackbarDuration.Long else SnackbarDuration.Short)
    }

    val m = data ?: run {
        // deleted (or not loaded yet)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val meal = m.meal
    var title by remember(meal.id, meal.title) { mutableStateOf(meal.title) }
    fun save(updated: Meal) = scope.launch { repo.updateMeal(updated) }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            OutlinedTextField(title, { title = it }, Modifier.weight(1f), singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save(meal.copy(title = title)) }))
            if (title != meal.title) TextButton(onClick = { save(meal.copy(title = title)) }) { Text("Save") }
        }

        rememberPhoto(repo.photoDir, meal.photo, 1200)?.let {
            Image(it, null, Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
        }

        error?.let { e ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(e, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = busy == null, onClick = { repo.retry(meal.id) }) { Text("Retry analysis") }
                        TextButton(onClick = { repo.failed.remove(meal.id); adding = true }) { Text("Add foods manually") }
                    }
                }
            }
        }
        busy?.let { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("  $it") } }

        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TYPES.forEach { t -> FilterChip(meal.mealType == t, { save(meal.copy(mealType = t)) }, label = { Text(t) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // move a meal logged on the wrong day
                    TextButton(onClick = { save(meal.copy(date = LocalDate.parse(meal.date).minusDays(1).toString())) }) { Text("‹") }
                    Text(LocalDate.parse(meal.date).format(DateTimeFormatter.ofPattern("EEE, MMM d")), fontWeight = FontWeight.Medium)
                    TextButton(enabled = meal.date < Repo.today(),
                        onClick = { save(meal.copy(date = LocalDate.parse(meal.date).plusDays(1).toString())) }) { Text("›") }
                    Spacer(Modifier.weight(1f))
                    var time by remember(meal.time) { mutableStateOf(meal.time) }
                    val parsed = parseTime(time)
                    OutlinedTextField(time, { time = it }, Modifier.width(96.dp), singleLine = true, label = { Text("Time") },
                        isError = parsed == null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { parsed?.let { save(withTime(meal, it)) } }))
                    if (parsed != null && parsed != meal.time) TextButton(onClick = { save(withTime(meal, parsed)) }) { Text("Set") }
                }
                MacroLine(m.totals, MaterialTheme.typography.titleSmall)
                meal.estimatedBy?.let {
                    Text("Estimated by $it. Check the portions: tap an item to adjust it.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Card {
            Column(Modifier.padding(vertical = 4.dp)) {
                m.sortedItems.forEach { item ->
                    if (editing == item.id) ItemEditor(item, onDone = { editing = null },
                        onSave = { scope.launch { repo.updateItem(it); editing = null } },
                        onDelete = { scope.launch { repo.deleteItem(item); editing = null } })
                    else ItemRow(item) { editing = item.id }
                    HorizontalDivider()
                }
                TextButton(onClick = { adding = true }, Modifier.padding(horizontal = 4.dp)) { Text("+ Add food") }
            }
        }

        SectionTitle("Adjust")
        Text("Describe a correction, e.g. \"only half the rice\", \"no butter\", \"add a diet coke\".",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        chat.forEach { line ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start) {
                Surface(shape = RoundedCornerShape(14.dp),
                    color = if (line.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                    Text(line.text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 280.dp), fontSize = 14.sp)
                }
            }
        }
        var instruction by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(instruction, { instruction = it }, Modifier.weight(1f), enabled = busy == null,
                placeholder = { Text(if (m.items.isEmpty()) "What did you eat?" else "What should change?") }, singleLine = true)
            TextButton(enabled = busy == null && instruction.isNotBlank(), onClick = {
                val text = instruction
                instruction = ""
                if (m.items.isEmpty()) repo.addWithAi(meal.id, text)
                else { localBusy = "Updating…"; scope.launch { repo.adjust(meal.id, text); localBusy = null } }
            }) { Text("Send") }
        }

        Spacer(Modifier.height(8.dp))
        if (confirmDelete) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { confirmDelete = false }) { Text("Keep") }
            Button(onClick = { scope.launch { repo.deleteMeal(meal); onBack() } },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Delete meal") }
        } else TextButton(onClick = { confirmDelete = true }) { Text("Delete meal", color = MaterialTheme.colorScheme.error) }
    }

    if (adding) AddFoodDialog(
        onDismiss = { adding = false },
        onAddFood = { food, grams -> scope.launch { repo.addFood(meal.id, food, grams) }; adding = false },
        onAddCustom = { item -> scope.launch { repo.addItem(meal.id, item.copy(mealId = meal.id)) }; adding = false },
        onAddWithAi = { text -> repo.addWithAi(meal.id, text); adding = false },
    )
    Box(Modifier.fillMaxSize()) { SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) }
}

/** New time; the meal type follows it unless the user had picked a type that doesn't match the old time. */
private fun withTime(meal: Meal, time: String) = meal.copy(time = time,
    mealType = if (meal.mealType == Repo.mealTypeFor(meal.time)) Repo.mealTypeFor(time) else meal.mealType)

/** "7:5" -> null, "7:05" -> "07:05", "25:00" -> null. */
private fun parseTime(s: String): String? {
    val m = Regex("""^\s*(\d{1,2}):(\d{2})\s*$""").find(s) ?: return null
    val (h, min) = m.destructured
    return if (h.toInt() in 0..23 && min.toInt() in 0..59) "%02d:%02d".format(h.toInt(), min.toInt()) else null
}

@Composable
private fun ItemRow(item: MealItem, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(item.name)
            if (item.grams > 0) Text("${item.grams.fmt0()} g", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${item.kcal.fmt0()} kcal", fontWeight = FontWeight.SemiBold)
            Text("P ${item.protein.fmt0()} · C ${item.carbs.fmt0()} · F ${item.fat.fmt0()}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Portion quick-picks scale the item as estimated; fields allow exact edits. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemEditor(item: MealItem, onDone: () -> Unit, onSave: (MealItem) -> Unit, onDelete: () -> Unit) {
    var factor by remember(item.id) { mutableDoubleStateOf(1.0) }
    var name by remember(item.id) { mutableStateOf(item.name) }
    var grams by remember(item.id) { mutableStateOf(item.grams.fmt0()) }
    var kcal by remember(item.id) { mutableStateOf(item.kcal.fmt0()) }
    var p by remember(item.id) { mutableStateOf("%.1f".format(item.protein)) }
    var c by remember(item.id) { mutableStateOf("%.1f".format(item.carbs)) }
    var f by remember(item.id) { mutableStateOf("%.1f".format(item.fat)) }
    fun applyFactor(x: Double) {
        factor = x
        val s = item.scaled(x)
        grams = s.grams.fmt0(); kcal = s.kcal.fmt0(); p = "%.1f".format(s.protein); c = "%.1f".format(s.carbs); f = "%.1f".format(s.fat)
    }
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Food") })
        Text("Portion", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.25 to "¼×", 0.5 to "½×", 0.75 to "¾×", 1.0 to "1×", 1.25 to "1¼×", 1.5 to "1½×", 2.0 to "2×").forEach { (x, l) ->
                FilterChip(factor == x, { applyFactor(x) }, label = { Text(l) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumberField("grams", grams, Modifier.weight(1f)) { grams = it }
            NumberField("kcal", kcal, Modifier.weight(1f)) { kcal = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumberField("protein g", p, Modifier.weight(1f)) { p = it }
            NumberField("carbs g", c, Modifier.weight(1f)) { c = it }
            NumberField("fat g", f, Modifier.weight(1f)) { f = it }
        }
        Row {
            TextButton(onClick = onDelete) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDone) { Text("Cancel") }
            Button(onClick = {
                onSave(item.copy(name = name.ifBlank { item.name }, grams = grams.toDoubleOrNull() ?: item.grams,
                    kcal = kcal.toDoubleOrNull() ?: item.kcal, protein = p.toDoubleOrNull() ?: item.protein,
                    carbs = c.toDoubleOrNull() ?: item.carbs, fat = f.toDoubleOrNull() ?: item.fat))
            }) { Text("Save") }
        }
    }
}

/**
 * Add a food to a meal: search the bundled USDA table (offline), describe it to the AI, or enter it by hand.
 * Picking a search result asks for the amount; every step has an explicit Add button.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddFoodDialog(
    onDismiss: () -> Unit,
    onAddFood: (Food, Double) -> Unit,
    onAddCustom: (MealItem) -> Unit,
    onAddWithAi: (String) -> Unit,
) {
    val repo = LocalRepo.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<FoodDb.Match>>(emptyList()) }
    var picked by remember { mutableStateOf<Food?>(null) }
    var custom by remember { mutableStateOf(false) }
    var grams by remember { mutableStateOf("100") }
    LaunchedEffect(query) {
        kotlinx.coroutines.delay(200)
        results = if (query.trim().length < 2) emptyList() else withContext(Dispatchers.Default) { repo.foods().search(query, 30) }
    }
    val g = grams.toDoubleOrNull() ?: 0.0
    val sel = picked

    if (custom) {
        CustomFoodDialog(query, onDismiss = { custom = false }, onAdd = onAddCustom)
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(sel?.let { "How much?" } ?: "Add food") },
        text = {
            if (sel == null) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("e.g. banana, greek yogurt, 2 slices of pizza") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }))
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    if (query.isNotBlank()) item {
                        Row(Modifier.fillMaxWidth().clickable { onAddWithAi(query.trim()) }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("✨  ", fontSize = 18.sp)
                            Column {
                                Text("Estimate \"${query.trim()}\" with AI", fontWeight = FontWeight.Medium)
                                Text("Best for dishes, brands and amounts like \"2 slices\"", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider()
                    }
                    if (results.isNotEmpty()) item {
                        Text("USDA foods: tap one, then choose the amount", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                    }
                    items(results) { r ->
                        Column(Modifier.fillMaxWidth().clickable { picked = r.food; focus.clearFocus() }.padding(vertical = 8.dp)) {
                            Text(r.food.description, fontSize = 14.sp)
                            Text("${r.food.kcal.fmt0()} kcal · P ${r.food.protein.fmt0()} C ${r.food.carbs.fmt0()} F ${r.food.fat.fmt0()} per 100 g",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                    }
                    item {
                        TextButton(onClick = { custom = true }) { Text("Enter nutrition manually…") }
                    }
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(sel.description)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(50, 100, 150, 200).forEach { q ->
                        FilterChip(g == q.toDouble(), { grams = q.toString() }, label = { Text("$q g", maxLines = 1, softWrap = false) })
                    }
                }
                NumberField("grams", grams, Modifier.fillMaxWidth()) { grams = it }
                Text("${(sel.kcal * g / 100).fmt0()} kcal · P ${(sel.protein * g / 100).fmt0()} C ${(sel.carbs * g / 100).fmt0()} " +
                    "F ${(sel.fat * g / 100).fmt0()}", fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = {
            if (sel != null) Button(enabled = g > 0, onClick = { onAddFood(sel, g) }) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = { if (sel != null) picked = null else onDismiss() }) { Text(if (sel != null) "Back" else "Cancel") }
        },
    )
}

@Composable
private fun CustomFoodDialog(initialName: String, onDismiss: () -> Unit, onAdd: (MealItem) -> Unit) {
    var name by remember { mutableStateOf(initialName.trim()) }
    var grams by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    var p by remember { mutableStateOf("") }
    var c by remember { mutableStateOf("") }
    var f by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom food") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name") })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumberField("kcal", kcal, Modifier.weight(1f)) { kcal = it }
                    NumberField("grams", grams, Modifier.weight(1f)) { grams = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumberField("protein", p, Modifier.weight(1f)) { p = it }
                    NumberField("carbs", c, Modifier.weight(1f)) { c = it }
                    NumberField("fat", f, Modifier.weight(1f)) { f = it }
                }
                Text("Macros in grams. Weight is optional.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank() && (kcal.toDoubleOrNull() ?: -1.0) >= 0, onClick = {
                onAdd(MealItem(mealId = 0, name = name.trim(), grams = grams.toDoubleOrNull() ?: 0.0, kcal = kcal.toDouble(),
                    protein = p.toDoubleOrNull() ?: 0.0, carbs = c.toDoubleOrNull() ?: 0.0, fat = f.toDoubleOrNull() ?: 0.0))
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Back") } },
    )
}
