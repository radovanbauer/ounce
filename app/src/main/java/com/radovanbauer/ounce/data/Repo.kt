package com.radovanbauer.ounce.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import com.radovanbauer.ounce.ai.Ai
import com.radovanbauer.ounce.ai.ItemDraft
import com.radovanbauer.ounce.ai.titleFor
import androidx.compose.runtime.mutableStateMapOf
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.roundToInt

/** Something the UI should briefly tell the user about (e.g. a snackbar). */
data class LogEvent(val mealId: Long, val message: String, val isError: Boolean = false, val undoable: Boolean = false)

class Repo(private val ctx: Context, val db: AppDb, val settings: Settings, val ai: Ai) {
    val photoDir = File(ctx.filesDir, "photos").apply { mkdirs() }
    val meals = db.meals()
    val weights = db.weights()

    /** Logging runs here so it survives leaving the screen; heavy work hops to IO inside. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** mealId -> what's happening to it right now ("Analyzing…", "Updating…"). */
    val busy = mutableStateMapOf<Long, String>()
    /** mealId -> why the last AI step failed (cleared on success). */
    val failed = mutableStateMapOf<Long, String>()
    /** Buffered so nothing is lost while e.g. the camera screen is open; consumed once by Today. */
    val events = Channel<LogEvent>(Channel.BUFFERED)

    init {
        scope.launch { meals.deleteEmptyManual() } // leftovers from abandoned "Add foods manually"
    }

    @Volatile private var foodDb: FoodDb? = null
    suspend fun foods(): FoodDb = foodDb ?: withContext(Dispatchers.IO) { FoodDb.load(ctx).also { foodDb = it } }

    // ------------------------------------------------------------------------------- logging (fire and forget)

    /**
     * Log a meal photo in the background. Gallery photos keep the date/time they were taken (from EXIF);
     * camera shots use now. [cleanup] is deleted afterwards (the full-size camera capture).
     */
    fun logPhoto(uri: Uri, fallbackDate: String, fallbackTime: String, fromGallery: Boolean, cleanup: File? = null) =
        scope.launch {
            val saved = try { savePhoto(uri) } catch (e: Exception) {
                events.trySend(LogEvent(0, "Couldn't read that photo (${e.message})", isError = true)); return@launch
            } finally { cleanup?.delete() }
            val (date, time) = (if (fromGallery) photoDateTime(uri) else null) ?: (fallbackDate to fallbackTime)
            val id = meals.insert(Meal(date = date, time = time, mealType = mealTypeFor(time), title = "Meal",
                photo = saved.first, source = "photo"))
            analyzePhoto(id, saved.second, date)
        }

    fun logText(text: String, date: String, time: String) = scope.launch {
        val id = meals.insert(Meal(date = date, time = time, mealType = mealTypeFor(time),
            title = text.trim().take(40), source = "text"))
        meals.insertChat(ChatLine(mealId = id, fromUser = true, text = text.trim()))
        analyzeText(id, text, date)
    }

    /** Re-run the AI for a meal whose analysis failed. */
    fun retry(mealId: Long) = scope.launch {
        val m = meals.meal(mealId).first()?.meal ?: return@launch
        if (m.photo != null) {
            val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(File(photoDir, m.photo).path) } ?: return@launch
            analyzePhoto(mealId, bmp, m.date)
        } else meals.firstUserLine(mealId)?.let { analyzeText(mealId, it, m.date) }
    }

    /** Estimate free text ("a slice of pepperoni pizza") with the AI and append it to an existing meal. */
    fun addWithAi(mealId: Long, text: String) = scope.launch {
        busy[mealId] = "Adding…"
        try {
            val (items, by) = ai.analyzeText(text)
            items.forEach { addItem(mealId, it.toItem(mealId)) }
            meals.meal(mealId).first()?.meal?.let { m -> if (m.estimatedBy == null) meals.update(m.copy(estimatedBy = by)) }
            events.trySend(LogEvent(mealId, "Added ${items.joinToString { it.name.lowercase() }} · ${items.sumOf { it.kcal }.roundToInt()} kcal"))
        } catch (e: Exception) {
            events.trySend(LogEvent(mealId, "Couldn't add: ${e.message}", isError = true))
        } finally {
            busy.remove(mealId)
        }
    }

    /** Called when leaving a meal's screen: drop it if it was started manually and nothing was added. */
    fun discardIfEmpty(mealId: Long) = scope.launch { meals.deleteIfEmptyManual(mealId) }

    fun adjustAsync(mealId: Long, instruction: String) = scope.launch {
        busy[mealId] = "Updating…"
        val reply = adjust(mealId, instruction)
        busy.remove(mealId)
        events.trySend(LogEvent(mealId, reply, isError = reply.startsWith("⚠️")))
    }

    private suspend fun analyzePhoto(id: Long, bitmap: Bitmap, date: String) =
        runAi(id, date) { ai.analyzePhoto(bitmap, "") }

    private suspend fun analyzeText(id: Long, text: String, date: String) =
        runAi(id, date) { ai.analyzeText(text) }

    private suspend fun runAi(id: Long, date: String, call: suspend () -> Pair<List<ItemDraft>, String>) {
        busy[id] = "Analyzing…"
        failed.remove(id)
        try {
            val (items, by) = call()
            val m = meals.meal(id).first()?.meal ?: return
            val title = titleFor(items)
            meals.update(m.copy(title = title, estimatedBy = by, portion = 1.0))
            meals.replaceItems(id, items.map { it.toItem() })
            val day = if (date == today()) "" else " to ${LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EEE, MMM d"))}"
            events.trySend(LogEvent(id, "Logged $title$day · ${items.sumOf { it.kcal }.roundToInt()} kcal", undoable = true))
        } catch (e: Exception) {
            failed[id] = e.message ?: "Analysis failed"
            events.trySend(LogEvent(id, "Couldn't analyze: ${e.message}", isError = true))
        } finally {
            busy.remove(id)
        }
    }

    suspend fun createEmptyMeal(date: String, time: String): Long =
        meals.insert(Meal(date = date, time = time, mealType = mealTypeFor(time), title = "Meal"))

    /** Set the whole meal to [factor] × its original estimate (absolute, so repeated taps don't compound). */
    suspend fun setPortion(mealId: Long, factor: Double) {
        val m = meals.meal(mealId).first() ?: return
        val rel = factor / m.meal.portion
        if (rel == 1.0) return
        meals.replaceItems(mealId, m.sortedItems.map { it.scaled(rel) })
        meals.update(m.meal.copy(portion = factor))
    }

    /** When the photo was taken, if the file says so and it's recent; else null. */
    private suspend fun photoDateTime(uri: Uri): Pair<String, String>? = withContext(Dispatchers.IO) {
        runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                val raw = ExifInterface(input).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: return@use null
                val dt = java.time.LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
                if (dt.toLocalDate().isBefore(LocalDate.now().minusDays(60)) || dt.isAfter(java.time.LocalDateTime.now().plusHours(1))) null
                else dt.toLocalDate().toString() to dt.format(DateTimeFormatter.ofPattern("HH:mm"))
            }
        }.getOrNull()
    }

    /** Apply a natural-language correction ("no butter, half the rice") by having the AI rewrite the item list. */
    suspend fun adjust(mealId: Long, instruction: String): String {
        val before = meals.meal(mealId).first() ?: return "Meal not found"
        meals.insertChat(ChatLine(mealId = mealId, fromUser = true, text = instruction))
        val reply = try {
            val (raw, _) = ai.edit(before.sortedItems, instruction)
            val items = reconcile(before.sortedItems, raw)
            meals.replaceItems(mealId, items.map { it.toItem() })
            describeChange(before.sortedItems, items)
        } catch (e: Exception) {
            "⚠️ ${e.message}"
        }
        meals.insertChat(ChatLine(mealId = mealId, fromUser = false, text = reply))
        return reply
    }

    /**
     * Small models garble numbers when rewriting a list, so only trust the edit for structure (which foods, how many
     * grams): existing foods keep their original values, scaled by the gram ratio; only new foods use model numbers.
     */
    private fun reconcile(old: List<MealItem>, edited: List<ItemDraft>): List<ItemDraft> {
        val oldBy = old.associateBy { nameKey(it.name) }
        return edited.map { e ->
            val o = oldBy[nameKey(e.name)] ?: return@map e
            val f = if (o.grams > 0) e.grams / o.grams else 1.0
            ItemDraft(o.name, o.grams * f, o.kcal * f, o.protein * f, o.carbs * f, o.fat * f)
        }
    }

    private fun nameKey(s: String) = s.lowercase().substringBefore(',').trim()

    private fun describeChange(old: List<MealItem>, new: List<ItemDraft>): String {
        val oldBy = old.associateBy { nameKey(it.name) }
        val newBy = new.associateBy { nameKey(it.name) }
        val parts = mutableListOf<String>()
        for ((k, o) in oldBy) if (k !in newBy) parts += "removed ${o.name.lowercase()}"
        for ((k, n) in newBy) {
            val o = oldBy[k]
            when {
                o == null -> parts += "added ${n.name.lowercase()} (${n.kcal.roundToInt()} kcal)"
                (o.grams - n.grams).let { it * it } > 1 || (o.kcal - n.kcal).let { it * it } > 4 ->
                    parts += "${n.name.lowercase()} ${o.grams.roundToInt()}→${n.grams.roundToInt()} g"
            }
        }
        val total = new.sumOf { it.kcal }.roundToInt()
        return if (parts.isEmpty()) "No changes needed. Meal is $total kcal."
        else parts.joinToString("; ").replaceFirstChar { it.uppercase() } + ". Meal is now $total kcal."
    }

    // ------------------------------------------------------------------------------- editing

    suspend fun updateMeal(m: Meal) = meals.update(m)
    suspend fun updateItem(i: MealItem) = meals.updateItem(i)
    suspend fun deleteItem(i: MealItem) = meals.deleteItem(i)

    suspend fun addItem(mealId: Long, item: MealItem) {
        val m = meals.meal(mealId).first() ?: return
        val pos = (m.items.maxOfOrNull { it.position } ?: -1) + 1
        meals.insertItems(listOf(item.copy(id = 0, mealId = mealId, position = pos)))
        // a manually started meal is named after its first food
        if (m.meal.title == "Meal" && m.items.isEmpty())
            meals.update(m.meal.copy(title = item.name.substringBefore(',').trim().replaceFirstChar { it.uppercase() }))
    }

    suspend fun addFood(mealId: Long, food: Food, grams: Double) {
        val f = grams / 100.0
        addItem(mealId, MealItem(mealId = mealId, name = food.description, grams = grams, kcal = food.kcal * f,
            protein = food.protein * f, carbs = food.carbs * f, fat = food.fat * f))
    }

    suspend fun deleteMeal(m: Meal) {
        meals.delete(m)
        m.photo?.let { File(photoDir, it).delete() }
    }

    // ------------------------------------------------------------------------------- weight

    fun toKg(value: Double) = if (settings.weightUnit == "lb") value / Settings.LB_PER_KG else value
    fun fromKg(kg: Double) = if (settings.weightUnit == "lb") kg * Settings.LB_PER_KG else kg

    suspend fun setWeight(date: String, value: Double) = weights.upsert(WeightEntry(date, toKg(value)))

    // ------------------------------------------------------------------------------- photos

    private suspend fun savePhoto(uri: Uri): Pair<String, Bitmap> = withContext(Dispatchers.IO) {
        val src = if (uri.scheme == "file") ImageDecoder.createSource(File(uri.path!!))
                  else ImageDecoder.createSource(ctx.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val s = 1600f / maxOf(info.size.width, info.size.height)
            if (s < 1f) d.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
        }
        val name = "${UUID.randomUUID()}.jpg"
        File(photoDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        name to bmp
    }

    fun newCaptureFile() = File(photoDir, "capture-${System.currentTimeMillis()}.jpg")

    companion object {
        fun today(): String = LocalDate.now().toString()
        fun now(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

        fun mealTypeFor(time: String): String {
            val m = time.split(":").let { (it.getOrNull(0)?.toIntOrNull() ?: 12) * 60 + (it.getOrNull(1)?.toIntOrNull() ?: 0) }
            return when {
                m in 4 * 60 until 10 * 60 + 30 -> "breakfast"
                m in 11 * 60 until 14 * 60 + 30 -> "lunch"
                m in 17 * 60 until 21 * 60 + 30 -> "dinner"
                else -> "snack"
            }
        }
    }
}
