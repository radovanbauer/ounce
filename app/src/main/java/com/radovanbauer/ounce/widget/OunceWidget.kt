package com.radovanbauer.ounce.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.radovanbauer.ounce.data.DayTotal
import com.radovanbauer.ounce.data.Goals
import com.radovanbauer.ounce.data.Repo
import com.radovanbauer.ounce.ui.ACTION_SNAP
import com.radovanbauer.ounce.ui.ACTION_TYPE
import com.radovanbauer.ounce.ui.MainActivity
import com.radovanbauer.ounce.ui.OunceApp
import kotlin.math.abs
import kotlin.math.roundToInt

/** Home-screen widget: today's calories left, macro progress, and one-tap snap / type buttons. */
class OunceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = (context.applicationContext as OunceApp).repo
        val totals = repo.meals.totalsOn(Repo.today())
        val goals = repo.settings.goals
        provideContent { GlanceTheme { WidgetContent(context, totals, goals) } }
    }

    companion object {
        suspend fun refresh(context: Context) = OunceWidget().updateAll(context)
    }
}

class OunceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = OunceWidget()
}

private val ProteinColor = ColorProvider(Color(0xFF3B82F6))
private val CarbsColor = ColorProvider(Color(0xFFF59E0B))
private val FatColor = ColorProvider(Color(0xFFEC4899))

@Composable
private fun WidgetContent(context: Context, t: DayTotal?, goals: Goals) {
    val kcal = t?.kcal ?: 0.0
    val left = goals.kcal - kcal
    val over = left < 0
    fun open(action: String?) = actionStartActivity(Intent(context, MainActivity::class.java).apply {
        if (action != null) setAction(action)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    })
    val onSurface = GlanceTheme.colors.onSurface
    val muted = GlanceTheme.colors.onSurfaceVariant

    Column(
        GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(20.dp)
            .padding(14.dp).clickable(open(null)),
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight()) {
                Text("${abs(left).roundToInt()} kcal ${if (over) "over" else "left"}",
                    style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        color = if (over) GlanceTheme.colors.error else onSurface))
                Text("${kcal.roundToInt()} / ${goals.kcal} today", style = TextStyle(fontSize = 12.sp, color = muted))
            }
            ActionButton("📷", open(ACTION_SNAP))
            Spacer(GlanceModifier.width(6.dp))
            ActionButton("✏️", open(ACTION_TYPE))
        }
        Spacer(GlanceModifier.height(8.dp))
        LinearProgressIndicator(
            progress = (kcal / goals.kcal.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f),
            modifier = GlanceModifier.fillMaxWidth().height(6.dp),
            color = if (over) GlanceTheme.colors.error else GlanceTheme.colors.primary,
            backgroundColor = GlanceTheme.colors.surfaceVariant,
        )
        Spacer(GlanceModifier.height(8.dp))
        Row(GlanceModifier.fillMaxWidth()) {
            Macro("P", t?.protein ?: 0.0, goals.protein, ProteinColor, GlanceModifier.defaultWeight())
            Macro("C", t?.carbs ?: 0.0, goals.carbs, CarbsColor, GlanceModifier.defaultWeight())
            Macro("F", t?.fat ?: 0.0, goals.fat, FatColor, GlanceModifier.defaultWeight())
        }
    }
}

@Composable
private fun ActionButton(label: String, action: androidx.glance.action.Action) {
    Box(
        GlanceModifier.width(44.dp).height(44.dp).cornerRadius(22.dp)
            .background(GlanceTheme.colors.primaryContainer).clickable(action),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = TextStyle(fontSize = 18.sp)) }
}

@Composable
private fun Macro(label: String, value: Double, goal: Int, color: ColorProvider, modifier: GlanceModifier) {
    Column(modifier.padding(end = 6.dp)) {
        Text("$label ${value.roundToInt()}/$goal", style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant))
        LinearProgressIndicator(
            progress = (value / goal.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f),
            modifier = GlanceModifier.fillMaxWidth().height(4.dp),
            color = color, backgroundColor = GlanceTheme.colors.surfaceVariant,
        )
    }
}
