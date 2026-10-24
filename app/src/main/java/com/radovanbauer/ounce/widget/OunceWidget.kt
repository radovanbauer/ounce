package com.radovanbauer.ounce.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import com.radovanbauer.ounce.data.Repo
import com.radovanbauer.ounce.ui.MainActivity
import com.radovanbauer.ounce.ui.OunceApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Home-screen widget: just the calorie ring from the Today screen. Tapping it opens the app.
 *
 * Glance calls [provideGlance] once per session and keeps the session alive for a while, so updates that arrive
 * during a session only recompose [provideContent]. The numbers are therefore read inside the composition, from
 * flows, rather than once up front (which made the widget show stale totals after quick successive changes).
 */
class OunceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = (context.applicationContext as OunceApp).repo
        val firstDay = Repo.today()
        val firstEaten = repo.meals.totalsOn(firstDay)?.kcal ?: 0.0
        scheduleNewDayUpdate(context)
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        provideContent {
            val day by remember { dayFlow() }.collectAsState(firstDay)
            val eaten by remember(day) { repo.meals.totalsOnFlow(day).map { it?.kcal ?: 0.0 } }
                .collectAsState(if (day == firstDay) firstEaten else 0.0)
            val goal = repo.settings.goals.kcal
            val left = (goal - eaten).roundToInt()
            val ring = remember(eaten, goal) { drawRing(context, eaten, goal) }
            Box(GlanceModifier.fillMaxSize().padding(4.dp).clickable(actionStartActivity(open)),
                contentAlignment = Alignment.Center) {
                Image(ImageProvider(ring),
                    contentDescription = if (left >= 0) "$left calories left today" else "${-left} calories over today",
                    contentScale = ContentScale.Fit, modifier = GlanceModifier.fillMaxSize())
            }
        }
    }

    companion object {
        const val ACTION_NEW_DAY = "com.radovanbauer.ounce.widget.NEW_DAY"

        suspend fun refresh(context: Context) = OunceWidget().updateAll(context)

        /** Today's date, re-emitted just after each midnight while a session is open. */
        private fun dayFlow() = flow {
            while (true) {
                emit(Repo.today())
                delay(millisToMidnight() + 1_000)
            }
        }

        private fun millisToMidnight(): Long {
            val now = ZonedDateTime.now()
            return Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.zone)).toMillis()
        }

        /**
         * Wake the widget within 15 minutes after midnight so it doesn't keep showing yesterday's total. Inexact
         * (no exact-alarm permission needed) and non-waking: it's delivered the next time the phone is awake, which is when the widget can be seen.
         */
        private fun scheduleNewDayUpdate(context: Context) {
            val intent = Intent(context, OunceWidgetReceiver::class.java).setAction(ACTION_NEW_DAY)
            val pi = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            context.getSystemService(AlarmManager::class.java)
                .setWindow(AlarmManager.RTC, System.currentTimeMillis() + millisToMidnight() + 5_000, 15 * 60_000L, pi)
        }
    }
}

class OunceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = OunceWidget()

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            OunceWidget.ACTION_NEW_DAY, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.Default).launch {
                    try { runCatching { OunceWidget.refresh(context) } } finally { pending.finish() }
                }
            }
        }
    }
}

/**
 * Same design as the app's SummaryCard ring, drawn to a bitmap (widgets can't draw arcs), in the
 * Material You colours the app uses: primary = accent1 200/600, track = neutral2 700/100.
 */
private fun drawRing(context: Context, eaten: Double, goal: Int): Bitmap {
    val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    fun c(id: Int) = context.getColor(id)
    val surface = c(if (dark) android.R.color.system_neutral1_900 else android.R.color.system_neutral1_10)
    val track = c(if (dark) android.R.color.system_neutral2_700 else android.R.color.system_neutral2_100)
    val primary = c(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
    val onSurface = c(if (dark) android.R.color.system_neutral1_50 else android.R.color.system_neutral1_900)
    val muted = c(if (dark) android.R.color.system_neutral2_200 else android.R.color.system_neutral2_700)
    val error = if (dark) 0xFFF2B8B5.toInt() else 0xFFB3261E.toInt() // Material 3 error

    val size = 400
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val cv = Canvas(bmp)
    val center = size / 2f
    val p = Paint(Paint.ANTI_ALIAS_FLAG)

    p.color = surface
    cv.drawCircle(center, center, center, p)

    val stroke = size * 0.085f
    val inset = size * 0.1f + stroke / 2
    val oval = RectF(inset, inset, size - inset, size - inset)
    p.style = Paint.Style.STROKE
    p.strokeWidth = stroke
    p.color = track
    cv.drawArc(oval, 0f, 360f, false, p)
    val over = eaten > goal
    val sweep = (360f * (eaten / goal.coerceAtLeast(1)).toFloat()).coerceIn(0f, 360f)
    if (sweep > 0f) {
        p.color = if (over) error else primary
        p.strokeCap = Paint.Cap.ROUND
        cv.drawArc(oval, -90f, sweep, false, p)
    }

    p.style = Paint.Style.FILL
    p.textAlign = Paint.Align.CENTER
    p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    p.color = onSurface
    p.textSize = size * 0.2f
    cv.drawText(abs(goal - eaten).roundToInt().toString(), center, center + size * 0.04f, p)
    p.typeface = Typeface.DEFAULT
    p.color = muted
    p.textSize = size * 0.075f
    cv.drawText(if (over) "kcal over" else "kcal left", center, center + size * 0.15f, p)
    return bmp
}
