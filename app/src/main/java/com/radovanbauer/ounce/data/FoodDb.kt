package com.radovanbauer.ounce.data

import android.content.Context
import kotlin.math.ln

/** A USDA FoodData Central entry, nutrients per 100 g. */
data class Food(
    val source: Char, val fdcId: String, val description: String,
    val kcal: Double, val protein: Double, val carbs: Double, val fat: Double, val fiber: Double,
) {
    val tokens: Set<String> = FoodDb.tokenize(description).toSet()
}

/**
 * Bundled nutrition table with a simple IDF-weighted token-overlap matcher.
 * Used for manual food search; AI estimates use the model's own numbers (they benchmarked better).
 */
class FoodDb(val foods: List<Food>) {
    private val idf: Map<String, Double>

    init {
        val df = HashMap<String, Int>()
        for (f in foods) for (t in f.tokens) df[t] = (df[t] ?: 0) + 1
        idf = df.mapValues { (_, n) -> ln(foods.size.toDouble() / n) + 1.0 }
    }

    data class Match(val food: Food, val score: Double)

    fun match(query: String): Match? = search(query, 1).firstOrNull()

    /** Best [limit] foods for a free-text query, highest score first. */
    fun search(query: String, limit: Int = 30): List<Match> {
        val q = tokenize(query).distinct()
        if (q.isEmpty()) return emptyList()
        val qWeight = q.sumOf { idf[it] ?: 3.0 }
        val top = java.util.PriorityQueue<Match>(compareBy { it.score })
        for (f in foods) {
            val s = score(f, q, qWeight) ?: continue
            if (top.size < limit) top.add(Match(f, s))
            else if (s > top.peek()!!.score) { top.poll(); top.add(Match(f, s)) }
        }
        return top.sortedWith(compareByDescending<Match> { it.score }.thenBy { it.food.tokens.size })
    }

    private fun score(f: Food, q: List<String>, qWeight: Double): Double? {
        var hit = 0.0
        for (t in q) if (t in f.tokens) hit += idf[t] ?: 0.0
        if (hit == 0.0) return null
        val extra = (f.tokens.size - q.count { it in f.tokens }).coerceAtLeast(0)
        var score = hit / qWeight - 0.03 * extra
        // the description's head noun phrase (before the first comma) is fully in the query
        val head = tokenize(f.description.substringBefore(',')).toSet()
        if (head.isNotEmpty() && head.all { it in q }) score += 0.1
        score -= 0.15 * f.tokens.count { it in TRANSFORMS && it !in q }
        if (BRAND.containsMatchIn(f.description)) score -= 0.3
        if (f.source == 'F') score += 0.08 // foods as eaten (cooked forms, typical recipes)
        if ("raw" in f.tokens && "raw" !in q && f.tokens.none { it in COOKED_HINTS } &&
            q.any { it in COOKED_HINTS }) score -= 0.2
        return score
    }

    companion object {
        private val STOP = setOf("a", "an", "the", "of", "with", "and", "in", "or", "on", "style",
            "nfs", "ns", "for", "to", "as", "made", "type", "piece", "slice", "slices", "serving")
        /** Description words that change energy density a lot; penalized unless the query asks for them. */
        private val TRANSFORMS = setOf("dried", "dehydrated", "dry", "uncooked", "powder", "powdered", "flour", "pie",
            "filling", "roll", "bit", "creamed", "candied", "chip", "juice", "concentrate", "sauce", "salad", "soup",
            "sandwich", "topping", "babyfood", "baby", "syrup", "nectar", "cake", "cookie", "bar", "mix", "dip", "spread",
            "chicken", "beef", "pork", "cheese", "sweetened", "fried", "breaded", "batter", "dessert", "cereal")
        private val BRAND = Regex("""\b[A-Z]{3,}\b""")
        private val COOKED_HINTS = setOf("cooked", "grilled", "fried", "baked", "roasted", "boiled",
            "steamed", "sauteed", "scrambled")

        fun tokenize(s: String): List<String> =
            s.lowercase().split(Regex("[^a-z0-9]+"))
                .filter { it.length > 1 && it !in STOP }
                .map(::stem)

        /** Tiny plural stemmer: berries->berry, tomatoes->tomato, dishes->dish, whites->white. */
        fun stem(t: String): String = when {
            t.length <= 3 -> t
            t.endsWith("ies") -> t.dropLast(3) + "y"
            listOf("oes", "ses", "xes", "ches", "shes").any { t.endsWith(it) } && !t.endsWith("sses") -> t.dropLast(2)
            t.endsWith("s") && listOf("ss", "us", "is").none { t.endsWith(it) } -> t.dropLast(1)
            else -> t
        }

        fun load(ctx: Context): FoodDb {
            val foods = ctx.assets.open("foods.tsv").bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val p = line.split('\t')
                    if (p.size < 8) null else Food(
                        p[0][0], p[1], p[2], p[3].toDouble(), p[4].toDouble(), p[5].toDouble(),
                        p[6].toDouble(), p[7].toDouble(),
                    )
                }.toList()
            }
            return FoodDb(foods)
        }
    }
}
