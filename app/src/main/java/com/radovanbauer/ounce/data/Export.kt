package com.radovanbauer.ounce.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.round

/** The user's whole log as three tables (header row first), shared by the CSV and Google Sheets exports. */
class ExportTables(val daily: List<List<Any?>>, val meals: List<List<Any?>>, val weights: List<List<Any?>>)

private fun r1(x: Double) = round(x * 10) / 10
private fun r0(x: Double) = round(x)

suspend fun Repo.exportTables(): ExportTables {
    val all = meals.allMeals()
    val ws = weights.all()
    val unit = settings.weightUnit
    val goal = settings.goals.kcal

    val mealRows = mutableListOf<List<Any?>>(
        listOf("date", "time", "meal", "meal title", "food", "grams", "kcal", "protein g", "carbs g", "fat g", "source", "estimated by"))
    for (m in all) for (i in m.sortedItems) mealRows += listOf(
        m.meal.date, m.meal.time, m.meal.mealType, m.meal.title, i.name, r0(i.grams), r0(i.kcal),
        r1(i.protein), r1(i.carbs), r1(i.fat), m.meal.source, m.meal.estimatedBy ?: "")

    val weightBy = ws.associate { it.date to r1(fromKg(it.kg)) }
    val days = (all.map { it.meal.date } + ws.map { it.date }).toSortedSet()
    val dailyRows = mutableListOf<List<Any?>>(
        listOf("date", "kcal", "protein g", "carbs g", "fat g", "meals", "kcal goal", "weight $unit"))
    for (d in days) {
        val dm = all.filter { it.meal.date == d }
        val t = dm.fold(Totals()) { a, m -> a + m.totals }
        dailyRows += listOf(d, r0(t.kcal), r1(t.protein), r1(t.carbs), r1(t.fat), dm.size, goal, weightBy[d] ?: "")
    }

    val weightRows = mutableListOf<List<Any?>>(listOf("date", "weight $unit"))
    ws.forEach { weightRows += listOf(it.date, r1(fromKg(it.kg))) }
    return ExportTables(dailyRows, mealRows, weightRows)
}

/** Writes ounce-daily.csv, ounce-meals.csv and ounce-weights.csv to the cache (shared via FileProvider). */
suspend fun Repo.exportCsv(ctx: Context): List<File> = withContext(Dispatchers.IO) {
    val t = exportTables()
    val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
    listOf("daily" to t.daily, "meals" to t.meals, "weights" to t.weights).map { (name, rows) ->
        File(dir, "ounce-$name.csv").apply {
            writeText(rows.joinToString("\n", postfix = "\n") { row -> row.joinToString(",") { csvCell(it) } })
        }
    }
}

private fun csvCell(v: Any?): String {
    val s = when (v) {
        null -> ""
        is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
        else -> v.toString()
    }
    return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}

/**
 * Google Sheets export with an OAuth access token for the drive.file scope (only files this app creates).
 * Creates "Ounce" in the user's Drive the first time and rewrites its three tabs on every sync.
 */
object SheetsExport {
    private const val API = "https://sheets.googleapis.com/v4/spreadsheets"
    private val TABS = listOf("Daily", "Meals", "Weights")

    class Result(val spreadsheetId: String, val url: String)

    suspend fun sync(token: String, existingId: String?, t: ExportTables): Result = withContext(Dispatchers.IO) {
        val id = existingId?.takeIf { exists(token, it) } ?: create(token)
        call(token, "POST", "$API/$id/values:batchClear",
            JSONObject().put("ranges", JSONArray(TABS)))
        val data = JSONArray()
        listOf(t.daily, t.meals, t.weights).forEachIndexed { i, rows ->
            data.put(JSONObject().put("range", "${TABS[i]}!A1").put("values", JSONArray(rows.map { row -> JSONArray(row.map { cell(it) }) })))
        }
        call(token, "POST", "$API/$id/values:batchUpdate",
            JSONObject().put("valueInputOption", "USER_ENTERED").put("data", data))
        Result(id, "https://docs.google.com/spreadsheets/d/$id/edit")
    }

    /** Numbers stay numbers; text that Sheets would treat as a formula is quoted. */
    private fun cell(v: Any?): Any = when (v) {
        null -> ""
        is Number -> v
        else -> v.toString().let { if (it.isNotEmpty() && it[0] in "=+-@") "'$it" else it }
    }

    private fun exists(token: String, id: String): Boolean = try {
        call(token, "GET", "$API/$id?fields=spreadsheetId", null); true
    } catch (e: SheetsError) {
        if (e.code == 404 || e.code == 403) false else throw e
    }

    private fun create(token: String): String {
        val sheets = JSONArray(TABS.map { JSONObject().put("properties", JSONObject().put("title", it)) })
        val body = JSONObject().put("properties", JSONObject().put("title", "Ounce")).put("sheets", sheets)
        return call(token, "POST", API, body).getString("spreadsheetId")
    }

    class SheetsError(val code: Int, message: String) : Exception(message)

    private fun call(token: String, method: String, url: String, body: JSONObject?): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault(text.take(200))
                throw SheetsError(code, "Google Sheets error $code: $msg")
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
