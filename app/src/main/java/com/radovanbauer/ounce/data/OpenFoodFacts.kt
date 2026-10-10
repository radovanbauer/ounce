package com.radovanbauer.ounce.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A packaged food from Open Food Facts; nutrition per 100 g (or ml). */
data class Product(
    val code: String,
    val name: String,
    val brand: String,
    val servingLabel: String?,  // e.g. "1 bar (40 g)"
    val servingGrams: Double?,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
) {
    val displayName get() = if (brand.isBlank()) name else "$name ($brand)"

    fun item(grams: Double) = MealItem(mealId = 0, name = displayName, grams = grams, kcal = kcal * grams / 100,
        protein = protein * grams / 100, carbs = carbs * grams / 100, fat = fat * grams / 100)
}

class ProductNotFound(code: String) : Exception("Product $code isn't in Open Food Facts yet.")

/**
 * Barcode lookup in Open Food Facts (https://world.openfoodfacts.org), a free, open, crowd-sourced database.
 * This is the one feature that needs the internet; nothing about the user is sent besides the barcode.
 */
object OpenFoodFacts {
    private const val FIELDS = "product_name,product_name_en,generic_name,brands,serving_size,serving_quantity,nutriments"

    suspend fun lookup(code: String): Product = withContext(Dispatchers.IO) {
        val clean = code.filter { it.isDigit() }
        require(clean.length in 6..14) { "That doesn't look like a product barcode." }
        val conn = URL("https://world.openfoodfacts.org/api/v2/product/$clean?fields=$FIELDS").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            // OFF asks apps to identify themselves
            conn.setRequestProperty("User-Agent", "Ounce/0.4 (https://github.com/radovanbauer/ounce)")
            val code200 = conn.responseCode
            if (code200 == 404) throw ProductNotFound(clean)
            if (code200 !in 200..299) error("Open Food Facts returned HTTP $code200")
            val root = JSONObject(conn.inputStream.bufferedReader().readText())
            if (root.optInt("status") != 1) throw ProductNotFound(clean)
            parse(clean, root.getJSONObject("product"))
        } catch (e: java.io.IOException) {
            throw IllegalStateException("Couldn't reach Open Food Facts. Check your connection.", e)
        } finally {
            conn.disconnect()
        }
    }

    internal fun parse(code: String, p: JSONObject): Product {
        val n = p.optJSONObject("nutriments") ?: JSONObject()
        fun num(key: String): Double? = n.opt(key)?.toString()?.toDoubleOrNull()
        val kcal = num("energy-kcal_100g") ?: num("energy_100g")?.let { it / 4.184 } // energy_100g is kJ
            ?: throw ProductNotFound(code)
        val name = listOf("product_name", "product_name_en", "generic_name")
            .map { p.optString(it).trim() }.firstOrNull { it.isNotEmpty() } ?: "Product $code"
        val brand = p.optString("brands").split(',').firstOrNull()?.trim().orEmpty()
        val servingGrams = p.opt("serving_quantity")?.toString()?.toDoubleOrNull()?.takeIf { it > 0 }
        return Product(
            code = code, name = name, brand = brand,
            servingLabel = p.optString("serving_size").trim().ifEmpty { null },
            servingGrams = servingGrams,
            kcal = kcal, protein = num("proteins_100g") ?: 0.0,
            carbs = num("carbohydrates_100g") ?: 0.0, fat = num("fat_100g") ?: 0.0,
        )
    }
}
