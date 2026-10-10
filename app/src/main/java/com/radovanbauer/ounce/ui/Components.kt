package com.radovanbauer.ounce.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.radovanbauer.ounce.data.Goals
import com.radovanbauer.ounce.data.Totals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

val ProteinColor = Color(0xFF3B82F6)
val CarbsColor = Color(0xFFF59E0B)
val FatColor = Color(0xFFEC4899)

fun Double.fmt0() = roundToInt().toString()

private val photoCache = object : android.util.LruCache<String, ImageBitmap>(40 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

/** Decode a stored meal photo, downsampled to roughly [maxSide] px, off the main thread. */
@Composable
fun rememberPhoto(dir: File, name: String?, maxSide: Int): ImageBitmap? {
    if (name == null) return null
    val key = "$name@$maxSide"
    var img by remember(key) { mutableStateOf(photoCache.get(key)) }
    LaunchedEffect(key) {
        if (img == null) img = withContext(Dispatchers.IO) {
            val f = File(dir, name)
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var s = 1
            while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
                ?.asImageBitmap()?.also { photoCache.put(key, it) }
        }
    }
    return img
}

@Composable
fun SummaryCard(totals: Totals, goals: Goals) {
    val left = goals.kcal - totals.kcal
    val over = left < 0
    val ringColor = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(116.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = 11.dp.toPx()
                    val inset = w / 2
                    val sz = Size(size.width - w, size.height - w)
                    drawArc(track, 0f, 360f, false, Offset(inset, inset), sz, style = Stroke(w))
                    val sweep = (360f * (totals.kcal / goals.kcal.coerceAtLeast(1)).toFloat()).coerceIn(0f, 360f)
                    drawArc(ringColor, -90f, sweep, false, Offset(inset, inset), sz, style = Stroke(w, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(abs(left).fmt0(), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(if (over) "kcal over" else "kcal left", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.padding(start = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${totals.kcal.fmt0()} / ${goals.kcal} kcal", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MacroBar("Protein", totals.protein, goals.protein, ProteinColor)
                MacroBar("Carbs", totals.carbs, goals.carbs, CarbsColor)
                MacroBar("Fat", totals.fat, goals.fat, FatColor)
            }
        }
    }
}

@Composable
private fun MacroBar(label: String, value: Double, goal: Int, color: Color) {
    Column {
        Row {
            Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("${value.fmt0()} / $goal g", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(
            progress = { (value / goal.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = color, trackColor = MaterialTheme.colorScheme.surfaceVariant, drawStopIndicator = {},
        )
    }
}

@Composable
fun MacroLine(t: Totals, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodySmall) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${t.kcal.fmt0()} kcal", style = style, fontWeight = FontWeight.SemiBold)
        Text("P ${t.protein.fmt0()}", style = style, color = ProteinColor)
        Text("C ${t.carbs.fmt0()}", style = style, color = CarbsColor)
        Text("F ${t.fat.fmt0()}", style = style, color = FatColor)
    }
}

@Composable
fun NumberField(label: String, value: String, modifier: Modifier = Modifier, integer: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { v ->
            // digits and at most one decimal point (none for integer fields)
            val cleaned = v.filter { it.isDigit() || (!integer && it == '.') }
            onChange(if (cleaned.count { it == '.' } > 1) value else cleaned)
        }, modifier,
        label = { Text(label, fontSize = 12.sp) }, singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = if (integer) androidx.compose.ui.text.input.KeyboardType.Number else androidx.compose.ui.text.input.KeyboardType.Decimal),
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
fun EmptyNote(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(vertical = 16.dp), textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
