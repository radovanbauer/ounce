package com.radovanbauer.ounce.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "meals", indices = [Index("date")])
data class Meal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,              // local YYYY-MM-DD
    val time: String,              // local HH:MM
    val mealType: String,          // breakfast | lunch | dinner | snack
    val title: String,
    val photo: String? = null,     // file name under filesDir/photos
    val source: String = "manual", // photo | text | manual
    val estimatedBy: String? = null, // "Gemini Nano", "gemini-3.8-flash", …
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "1.0")
    val portion: Double = 1.0,     // whole-meal portion factor already applied to the items (for the ½×…2× chips)
)

@Entity(
    tableName = "meal_items",
    foreignKeys = [ForeignKey(entity = Meal::class, parentColumns = ["id"], childColumns = ["mealId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("mealId")],
)
data class MealItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mealId: Long,
    val name: String,
    val grams: Double,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val position: Int = 0,
) {
    fun scaled(f: Double) = copy(grams = grams * f, kcal = kcal * f, protein = protein * f, carbs = carbs * f, fat = fat * f)
}

@Entity(tableName = "weights")
data class WeightEntry(
    @PrimaryKey val date: String,
    val kg: Double,
)

@Entity(
    tableName = "meal_chat",
    foreignKeys = [ForeignKey(entity = Meal::class, parentColumns = ["id"], childColumns = ["mealId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("mealId")],
)
data class ChatLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mealId: Long,
    val fromUser: Boolean,
    val text: String,
)

data class MealWithItems(
    @Embedded val meal: Meal,
    @Relation(parentColumn = "id", entityColumn = "mealId") val items: List<MealItem>,
) {
    val sortedItems get() = items.sortedBy { it.position }
    val totals get() = Totals.of(items)
}

data class Totals(val kcal: Double = 0.0, val protein: Double = 0.0, val carbs: Double = 0.0, val fat: Double = 0.0) {
    operator fun plus(o: Totals) = Totals(kcal + o.kcal, protein + o.protein, carbs + o.carbs, fat + o.fat)

    companion object {
        fun of(items: List<MealItem>) = items.fold(Totals()) { t, i -> t + Totals(i.kcal, i.protein, i.carbs, i.fat) }
    }
}

data class DayTotal(val date: String, val kcal: Double, val protein: Double, val carbs: Double, val fat: Double)

@Dao
interface MealDao {
    @Transaction
    @Query("SELECT * FROM meals WHERE date = :date ORDER BY time, id")
    fun day(date: String): Flow<List<MealWithItems>>

    @Transaction
    @Query("SELECT * FROM meals WHERE id = :id")
    fun meal(id: Long): Flow<MealWithItems?>

    @Query("""SELECT m.date AS date, SUM(i.kcal) AS kcal, SUM(i.protein) AS protein, SUM(i.carbs) AS carbs, SUM(i.fat) AS fat
              FROM meals m JOIN meal_items i ON i.mealId = m.id WHERE m.date >= :from GROUP BY m.date ORDER BY m.date""")
    fun dailyTotals(from: String): Flow<List<DayTotal>>

    @Insert suspend fun insert(meal: Meal): Long
    @Update suspend fun update(meal: Meal)
    @Delete suspend fun delete(meal: Meal)

    @Insert suspend fun insertItems(items: List<MealItem>)
    @Update suspend fun updateItem(item: MealItem)
    @Delete suspend fun deleteItem(item: MealItem)
    @Query("DELETE FROM meal_items WHERE mealId = :mealId") suspend fun clearItems(mealId: Long)

    @Transaction
    suspend fun replaceItems(mealId: Long, items: List<MealItem>) {
        clearItems(mealId)
        insertItems(items.mapIndexed { i, it -> it.copy(id = 0, mealId = mealId, position = i) })
    }

    @Transaction
    @Query("SELECT * FROM meals ORDER BY date, time, id")
    suspend fun allMeals(): List<MealWithItems>

    @Query("""SELECT m.date AS date, SUM(i.kcal) AS kcal, SUM(i.protein) AS protein, SUM(i.carbs) AS carbs, SUM(i.fat) AS fat
              FROM meals m JOIN meal_items i ON i.mealId = m.id WHERE m.date = :date GROUP BY m.date""")
    suspend fun totalsOn(date: String): DayTotal?

    @Query("SELECT text FROM meal_chat WHERE mealId = :mealId AND fromUser = 1 ORDER BY id LIMIT 1")
    suspend fun firstUserLine(mealId: Long): String?

    @Query("SELECT * FROM meal_chat WHERE mealId = :mealId ORDER BY id")
    fun chat(mealId: Long): Flow<List<ChatLine>>

    @Insert suspend fun insertChat(line: ChatLine)

    /** Meals started with "Add foods manually" that never got any food. */
    @Query("DELETE FROM meals WHERE source = 'manual' AND id NOT IN (SELECT mealId FROM meal_items)")
    suspend fun deleteEmptyManual(): Int

    @Query("DELETE FROM meals WHERE id = :id AND source = 'manual' AND id NOT IN (SELECT mealId FROM meal_items)")
    suspend fun deleteIfEmptyManual(id: Long): Int
}

@Dao
interface WeightDao {
    @Query("SELECT * FROM weights WHERE date >= :from ORDER BY date")
    fun since(from: String): Flow<List<WeightEntry>>

    @Query("SELECT * FROM weights WHERE date = :date")
    fun on(date: String): Flow<WeightEntry?>

    @Query("SELECT * FROM weights ORDER BY date")
    suspend fun all(): List<WeightEntry>

    @Upsert suspend fun upsert(w: WeightEntry)

    @Query("DELETE FROM weights WHERE date = :date") suspend fun delete(date: String)
}

@Database(entities = [Meal::class, MealItem::class, WeightEntry::class, ChatLine::class], version = 2)
abstract class AppDb : RoomDatabase() {
    abstract fun meals(): MealDao
    abstract fun weights(): WeightDao

    companion object {
        const val NAME = "ounce.db"
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE meals ADD COLUMN portion REAL NOT NULL DEFAULT 1.0")
            }
        }

        fun create(ctx: Context) = Room.databaseBuilder(ctx, AppDb::class.java, NAME)
            .addMigrations(MIGRATION_1_2)
            .build()
    }
}
