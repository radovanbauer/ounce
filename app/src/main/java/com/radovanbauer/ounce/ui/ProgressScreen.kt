package com.radovanbauer.ounce.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.radovanbauer.ounce.data.DayTotal
import com.radovanbauer.ounce.data.Repo
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Composable
fun ProgressScreen(onOpenDay: (String) -> Unit) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    var days by remember { mutableIntStateOf(30) }
    val today = LocalDate.now()
    val from = today.minusDays(days.toLong() - 1).toString()
    val weights by repo.weights.since(from).collectAsState(initial = emptyList())
    val totals by repo.meals.dailyTotals(from).collectAsState(initial = emptyList())
    val unit = repo.settings.weightUnit
    val goals = repo.settings.goals

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Progress", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        SingleChoiceSegmentedButtonRow {
            listOf(14, 30, 90).forEachIndexed { i, d ->
                SegmentedButton(days == d, { days = d }, SegmentedButtonDefaults.itemShape(i, 3)) { Text("${d}d") }
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val values = weights.map { it.date to repo.fromKg(it.kg) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Weight")
                    Spacer(Modifier.weight(1f))
                    if (values.size >= 2) {
                        val change = values.last().second - values.first().second
                        Text("%+.1f %s".format(change, unit), fontWeight = FontWeight.SemiBold,
                            color = if (change <= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (values.size < 2) EmptyNote("Log your weight on at least two days to see a trend.")
                else WeightChart(values, today.minusDays(days.toLong() - 1), today)
                var w by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumberField("Today ($unit)", w, Modifier.weight(1f)) { w = it }
                    TextButton(enabled = (w.toDoubleOrNull() ?: 0.0) > 0, onClick = {
                        val v = w.toDouble(); w = ""; scope.launch { repo.setWeight(Repo.today(), v) }
                    }) { Text("Log") }
                }
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Calories")
                CalorieChart(totals, goals.kcal, today.minusDays(days.toLong() - 1), days, onOpenDay)
                if (totals.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        Stat("${totals.map { it.kcal }.average().fmt0()}", "avg kcal")
                        Stat("${totals.map { it.protein }.average().fmt0()} g", "protein")
                        Stat("${totals.map { it.carbs }.average().fmt0()} g", "carbs")
                        Stat("${totals.map { it.fat }.average().fmt0()} g", "fat")
                    }
                    Text("Averages over ${totals.size} logged day${if (totals.size == 1) "" else "s"}. Tap a bar to open that day.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else EmptyNote("No meals logged in this period.")
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WeightChart(values: List<Pair<String, Double>>, start: LocalDate, end: LocalDate) {
    val line = MaterialTheme.colorScheme.primary
    val dot = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val lo = values.minOf { it.second } - 1
    val hi = values.maxOf { it.second } + 1
    val span = ChronoUnit.DAYS.between(start, end).coerceAtLeast(1).toFloat()
    // trailing 7-day average smooths out day-to-day water weight noise
    val avg = values.map { (d, _) ->
        val day = LocalDate.parse(d)
        val win = values.filter { val x = LocalDate.parse(it.first); !x.isAfter(day) && x.isAfter(day.minusDays(7)) }
        d to win.map { it.second }.average()
    }
    Row {
        Column(Modifier.height(160.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text("%.0f".format(hi), style = MaterialTheme.typography.labelSmall)
            Text("%.0f".format(lo), style = MaterialTheme.typography.labelSmall)
        }
        Canvas(Modifier.fillMaxWidth().height(160.dp).padding(start = 6.dp)) {
            fun x(d: String) = ChronoUnit.DAYS.between(start, LocalDate.parse(d)) / span * size.width
            fun y(v: Double) = ((hi - v) / (hi - lo)).toFloat() * size.height
            for (g in 0..2) drawLine(grid, Offset(0f, size.height * g / 2), Offset(size.width, size.height * g / 2))
            val path = Path()
            avg.forEachIndexed { i, (d, v) -> if (i == 0) path.moveTo(x(d), y(v)) else path.lineTo(x(d), y(v)) }
            drawPath(path, line, style = Stroke(3.dp.toPx()))
            values.forEach { (d, v) -> drawCircle(dot, 3.5.dp.toPx(), Offset(x(d), y(v))) }
        }
    }
}

@Composable
private fun CalorieChart(totals: List<DayTotal>, goal: Int, start: LocalDate, days: Int, onOpenDay: (String) -> Unit) {
    val ok = MaterialTheme.colorScheme.primary
    val over = MaterialTheme.colorScheme.error
    val goalColor = MaterialTheme.colorScheme.onSurfaceVariant
    val byDay = totals.associateBy { it.date }
    val max = maxOf(goal * 1.25, totals.maxOfOrNull { it.kcal } ?: 0.0)
    Text("Goal $goal kcal (dashed line)", style = MaterialTheme.typography.labelSmall, color = goalColor)
    Canvas(Modifier.fillMaxWidth().height(140.dp).pointerInput(start, days) {
        detectTapGestures { pos ->
            val i = (pos.x / (size.width / days)).toInt().coerceIn(0, days - 1)
            val d = start.plusDays(i.toLong()).toString()
            if (d in byDay) onOpenDay(d)
        }
    }) {
        val bw = size.width / days
        for (i in 0 until days) {
            val t = byDay[start.plusDays(i.toLong()).toString()] ?: continue
            val h = (t.kcal / max).toFloat() * size.height
            drawRoundRect(if (t.kcal > goal) over else ok, Offset(i * bw + bw * 0.15f, size.height - h),
                Size(bw * 0.7f, h), CornerRadius(bw * 0.15f))
        }
        val gy = size.height - (goal / max).toFloat() * size.height
        drawLine(goalColor, Offset(0f, gy), Offset(size.width, gy), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
    }
    val fmt = java.time.format.DateTimeFormatter.ofPattern("MMM d")
    Row(Modifier.fillMaxWidth()) {
        Text(start.format(fmt), style = MaterialTheme.typography.labelSmall, color = goalColor)
        Spacer(Modifier.weight(1f))
        Text(start.plusDays(days / 2L).format(fmt), style = MaterialTheme.typography.labelSmall, color = goalColor)
        Spacer(Modifier.weight(1f))
        Text("Today", style = MaterialTheme.typography.labelSmall, color = goalColor)
    }
}
