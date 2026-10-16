package com.radovanbauer.ounce.ui

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.radovanbauer.ounce.ai.Ai
import com.radovanbauer.ounce.data.AppDb
import com.radovanbauer.ounce.data.Repo
import com.radovanbauer.ounce.data.Settings
import com.radovanbauer.ounce.widget.OunceWidget
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class OunceApp : Application() {
    lateinit var repo: Repo
        private set

    override fun onCreate() {
        super.onCreate()
        val settings = Settings(this)
        val db = AppDb.create(this)
        repo = Repo(this, db, settings, Ai(settings))
        watchForWidget(db, settings)
    }

    /** Refresh the home-screen widget shortly after the food log or the goals change. */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun watchForWidget(db: AppDb, settings: Settings) {
        val changes = kotlinx.coroutines.flow.MutableStateFlow(0)
        db.invalidationTracker.addObserver(object : androidx.room.InvalidationTracker.Observer(arrayOf("meals", "meal_items")) {
            override fun onInvalidated(tables: Set<String>) { changes.value++ }
        })
        settings.onGoalsChanged = { changes.value++ }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default).launch {
            changes.drop(1).debounce(800).collect { runCatching { OunceWidget.refresh(this@OunceApp) } }
        }
    }
}

/** Minimal back stack; the app has only a handful of screens. */
sealed interface Screen {
    data class Day(val date: String? = null) : Screen   // null = today
    data class MealDetail(val id: Long) : Screen
    data class Camera(val date: String) : Screen
    data object Progress : Screen
    data object SettingsScreen : Screen
    data object GeminiSetup : Screen
}

val LocalRepo = staticCompositionLocalOf<Repo> { error("no repo") }

/** Launcher shortcuts (res/xml/shortcuts.xml) jump straight into the two main flows. */
const val ACTION_SNAP = "com.radovanbauer.ounce.SNAP"
const val ACTION_TYPE = "com.radovanbauer.ounce.TYPE"
/** Look up a barcode without scanning (extra "code"); handy for other apps and for testing. */
const val ACTION_BARCODE = "com.radovanbauer.ounce.BARCODE"

class MainActivity : ComponentActivity() {
    private var launchAction by mutableStateOf<String?>(null)
    private var launchCode by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) { launchAction = intent?.action; launchCode = intent?.getStringExtra("code") }
        val repo = (application as OunceApp).repo
        setContent {
            val ctx = LocalContext.current
            val dark = isSystemInDarkTheme()
            val colors = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            MaterialTheme(colorScheme = colors) {
                CompositionLocalProvider(LocalRepo provides repo) {
                    AppRoot(launchAction, launchCode) { launchAction = null; launchCode = null }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        launchAction = intent.action
        launchCode = intent.getStringExtra("code")
    }
}

@Composable
fun AppRoot(launchAction: String?, launchCode: String?, onLaunchHandled: () -> Unit) {
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Day())) }
    var focusComposer by remember { mutableStateOf(false) }
    var scannedCode by remember { mutableStateOf<String?>(null) }  // barcode to look up and log on Today
    val push = { s: Screen -> stack = stack + s }
    val pop = { if (stack.size > 1) stack = stack.dropLast(1) }
    val switchTab = { s: Screen -> stack = listOf(s) }
    val top = stack.last()
    BackHandler(enabled = stack.size > 1) { pop() }

    LaunchedEffect(launchAction) {
        when (launchAction) {
            ACTION_SNAP -> stack = listOf(Screen.Day(), Screen.Camera(Repo.today()))
            ACTION_TYPE -> { stack = listOf(Screen.Day()); focusComposer = true }
            ACTION_BARCODE -> if (launchCode != null) { stack = listOf(Screen.Day()); scannedCode = launchCode }
        }
        if (launchAction != null) onLaunchHandled()
    }

    val settings = LocalRepo.current.settings
    if (!settings.onboarded) {
        Onboarding(onDone = { settings.finishOnboarding() })
        return
    }
    if (top is Screen.GeminiSetup) {
        Onboarding(onDone = pop, startAtGemini = true)
        return
    }

    if (top is Screen.Camera) {
        CameraScreen(top.date, onDone = pop, onBarcode = { code -> pop(); scannedCode = code })
        return
    }
    Scaffold(
        bottomBar = {
            if (top !is Screen.MealDetail) NavigationBar {
                listOf(Triple(Screen.Day(), "Today", "🍽"), Triple(Screen.Progress, "Progress", "📈"),
                    Triple(Screen.SettingsScreen, "Settings", "⚙️")).forEach { (s, label, icon) ->
                    NavigationBarItem(
                        selected = top::class == s::class,
                        onClick = { switchTab(s) },
                        icon = { Text(icon, style = MaterialTheme.typography.titleLarge) },
                        label = { Text(label, fontWeight = FontWeight.Medium) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).consumeWindowInsets(pad)) {
            when (top) {
                is Screen.Day -> TodayScreen(top.date, focusComposer, onComposerFocused = { focusComposer = false },
                    scannedCode = scannedCode, onScannedHandled = { scannedCode = null },
                    onTypeInstead = { scannedCode = null; focusComposer = true },
                    onOpenMeal = { push(Screen.MealDetail(it)) },
                    onOpenCamera = { push(Screen.Camera(it)) },
                    onDateChange = { stack = stack.dropLast(1) + Screen.Day(it) })
                is Screen.MealDetail -> MealScreen(top.id, onBack = pop)
                Screen.Progress -> ProgressScreen(onOpenDay = { push(Screen.Day(it)) })
                Screen.SettingsScreen -> SettingsScreen(onGeminiSetup = { push(Screen.GeminiSetup) })
                is Screen.Camera, Screen.GeminiSetup -> {}
            }
        }
    }
}
