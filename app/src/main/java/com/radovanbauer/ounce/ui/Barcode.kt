package com.radovanbauer.ounce.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.radovanbauer.ounce.data.OpenFoodFacts
import com.radovanbauer.ounce.data.Product
import com.radovanbauer.ounce.data.ProductNotFound
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Google's code scanner (runs in Play services, on-device, no camera permission needed).
 * Returns the barcode, or null if the user backed out.
 */
suspend fun scanBarcode(ctx: Context): String? = suspendCancellableCoroutine { cont ->
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
        .enableAutoZoom()
        .build()
    GmsBarcodeScanning.getClient(ctx, options).startScan()
        .addOnSuccessListener { if (cont.isActive) cont.resume(it.rawValue) }
        .addOnCanceledListener { if (cont.isActive) cont.resume(null) }
        .addOnFailureListener { if (cont.isActive) cont.resume(null) }
}

/**
 * Looks up [code] in Open Food Facts and lets the user pick an amount.
 * [confirmLabel] is "Log it" (new meal) or "Add" (to an existing meal).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BarcodeSheet(
    code: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (Product, Double) -> Unit,
    onTypeInstead: (() -> Unit)? = null,
) {
    var lookup by remember { mutableStateOf(code) }
    var product by remember { mutableStateOf<Product?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(lookup) {
        loading = true; error = null; product = null
        try { product = OpenFoodFacts.lookup(lookup) }
        catch (e: ProductNotFound) { error = e.message }
        catch (e: Exception) { error = e.message ?: "Lookup failed" }
        loading = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val p = product
            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("  Looking up $lookup…")
                }
                p != null -> ProductAmount(p, confirmLabel) { grams -> onConfirm(p, grams) }
                else -> {
                    Text(error ?: "Not found", style = MaterialTheme.typography.bodyLarge)
                    Text("You can check the number below, or describe the food instead.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    var typed by remember { mutableStateOf(lookup) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(typed, { v -> typed = v.filter { it.isDigit() } }, Modifier.weight(1f), singleLine = true,
                            label = { Text("Barcode number") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { lookup = typed }))
                        TextButton(enabled = typed.length >= 6, onClick = { lookup = typed }) { Text("Look up") }
                    }
                    if (onTypeInstead != null) OutlinedButton(onClick = onTypeInstead) { Text("✏️ Describe it instead") }
                }
            }
            Text("Product data: Open Food Facts (openfoodfacts.org), ODbL.", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProductAmount(p: Product, confirmLabel: String, onConfirm: (Double) -> Unit) {
    val serving = p.servingGrams
    var grams by remember(p) { mutableStateOf((serving ?: 100.0).roundToInt().toString()) }
    val g = grams.toDoubleOrNull() ?: 0.0
    Text(p.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    if (p.brand.isNotBlank()) Text(p.brand, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("Per 100 g: ${p.kcal.fmt0()} kcal · P ${p.protein.fmt0()} · C ${p.carbs.fmt0()} · F ${p.fat.fmt0()}",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val choices = if (serving != null) listOf(0.5, 1.0, 2.0).map { f ->
            (serving * f) to (when (f) { 0.5 -> "½ serving"; 1.0 -> "1 serving"; else -> "2 servings" })
        } else listOf(30.0, 50.0, 100.0, 200.0).map { it to "${it.toInt()} g" }
        choices.forEach { (amount, label) ->
            FilterChip(g == amount.roundToInt().toDouble(), { grams = amount.roundToInt().toString() }, label = { Text(label) })
        }
    }
    p.servingLabel?.let { Text("1 serving = $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    NumberField("grams", grams, Modifier.fillMaxWidth()) { grams = it }
    val item = p.item(g)
    MacroLine(com.radovanbauer.ounce.data.Totals(item.kcal, item.protein, item.carbs, item.fat), MaterialTheme.typography.titleMedium)
    Button(onClick = { onConfirm(g) }, enabled = g > 0, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text(confirmLabel) }
}
