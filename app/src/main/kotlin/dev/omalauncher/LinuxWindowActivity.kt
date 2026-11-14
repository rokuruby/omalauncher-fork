package dev.omalauncher

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.omalauncher.ui.ExtraKeys
import dev.omalauncher.ui.ImagePicker
import dev.omalauncher.ui.KeyboardSink
import dev.omalauncher.ui.WidgetAsks
import dev.omalauncher.ui.Mono
import dev.omalauncher.ui.MonitorPointer
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.TouchpadView
import dev.omalauncher.ui.linuxWindowInput
import dev.omalauncher.wayland.WindowRenderer
import dev.omalauncher.wayland.XdgToplevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Shows a single Linux window as its own Android task, so it gets its own
 * Recents card and behaves like a separate app.
 */
class LinuxWindowActivity : InstantActivity() {
    private var window: XdgToplevel? = null
    private val imagePicker = ImagePicker(this) { runtime }
    private val asks = WidgetAsks(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val window = runtime.wm.windowByHandle(intent.data?.lastPathSegment)
        if (window == null) {
            // The window is gone, e.g. the process restarted since Recents was built.
            finishAndRemoveTask()
            return
        }
        this.window = window
        setInstantContent {
            AppWindow(runtime, window)
            BackHandler { backInstantly() }
            LaunchedEffect(window) {
                snapshotFlow { window in runtime.wm.appWindows }.first { !it }
                closeInstantly()
            }
            // Terminals retitle constantly; only a new label changes the Recents card.
            val label = runtime.displayName(window.appId, window.title)
            val appIcon = runtime.iconNamed(runtime.agentOf(window.appId, label)?.name ?: label)
            LaunchedEffect(label, appIcon, Palette.accent) { describeTask(label, appIcon?.asAndroidBitmap()) }
        }
    }

    override fun onStart() {
        super.onStart()
        runtime.screenStarted()
    }

    override fun onResume() {
        super.onResume()
        window?.let(runtime.wm::focus)
        runtime.keyboard.imagePicker = imagePicker
        asks.resumed(runtime)
    }

    override fun onStop() {
        super.onStop()
        runtime.screenStopped()
    }

    /** Swiping the card away in Recents closes the Linux window, like closing an app. */
    override fun onDestroy() {
        super.onDestroy()
        val w = window ?: return
        if (isFinishing && w in runtime.wm.appWindows) runtime.wm.close(w)
    }

    // Lint flags super.dispatchKeyEvent: ComponentActivity marks its override @RestrictTo, but Activity's is public API.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Super+Esc leaves the window as Back does, where Esc itself is the window's: it keeps running in Recents.
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE && event.isMetaPressed && event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) backInstantly()
            return true
        }
        if (runtime.keyboard.handle(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /** Keys held as the window goes, like Super, would stay down for it: Android sends their lift elsewhere. */
    override fun onPause() {
        super.onPause()
        runtime.keyboard.releaseHeld()
        if (runtime.keyboard.imagePicker === imagePicker) runtime.keyboard.imagePicker = null
        asks.paused(runtime)
    }
}

/**
 * Recents shows the manifest label ("Linux", or an alias's), but it does use the
 * task icon: the app's drawer icon, else its initials in the theme colors.
 */
internal fun taskIcon(
    name: String,
    background: Int,
    accent: Int,
): Bitmap {
    val size = 192
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(background)
    val words = name.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
    val initials =
        when {
            words.size >= 2 -> "${words[0][0]}${words[1][0]}".uppercase()
            words.isNotEmpty() -> words[0].take(2).replaceFirstChar(Char::uppercase)
            else -> "◆"
        }
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            textSize = size * 0.42f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
    val y = size / 2f - (paint.descent() + paint.ascent()) / 2
    canvas.drawText(initials, size / 2f, y, paint)
    return bmp
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppWindow(
    runtime: Runtime,
    window: XdgToplevel,
) {
    var sink by remember { mutableStateOf<KeyboardSink?>(null) }
    // How much of the view the keyboard and the extra keys leave the window, in pixels.
    var visible by remember { mutableStateOf(0) }
    // Shows wherever the window has yet to draw, as when it grows; the terminal's own background, not a lighter one.
    val background = Palette.background.toArgb()
    val density = LocalDensity.current
    // Where the keyboard ends up, not each frame of its slide, and read here so the extra keys come and go in the same
    // frame: the window then resizes once, and a terminal reflows once.
    val keyboard = (WindowInsets.imeAnimationTarget.getBottom(density) - WindowInsets.navigationBars.getBottom(density)).coerceAtLeast(0)

    // In plain terms: with a monitor plugged in, Android normally just copies the phone's screen onto it, enlarged
    // and blurry. For the Linux desktop this draws the window straight onto the monitor instead (at up to 1440
    // lines), and the phone's screen becomes its touchpad and the place a mouse is caught. Without a monitor, or
    // after "Show on phone", the desktop is drawn on the phone as before.
    val context = LocalContext.current
    val monitor = rememberMonitor()
    var onMonitor by remember { mutableStateOf(true) }
    val desktop = isDesktop(window)
    val screen = monitor?.takeIf { desktop && onMonitor }
    // What the Display menu on the desktop asked for ("auto" or a size like 1024x768), followed as it changes.
    val wantedFile = remember { File(runtime.proot.home, MONITOR_WANTED) }
    val stateFile = remember { File(runtime.proot.home, MONITOR_STATE) }
    var wanted by remember { mutableStateOf(readWanted(wantedFile)) }
    DisposableEffect(wantedFile) {
        val main = Handler(Looper.getMainLooper())
        wantedFile.parentFile?.mkdirs()
        val observer =
            object : FileObserver(wantedFile.parentFile ?: wantedFile, CLOSE_WRITE or MOVED_TO) {
                override fun onEvent(
                    event: Int,
                    path: String?,
                ) {
                    if (path == wantedFile.name) main.post { wanted = readWanted(wantedFile) }
                }
            }
        observer.startWatching()
        onDispose { observer.stopWatching() }
    }
    // The monitor's own resolution, noted before this asks it for another one.
    val native = remember(screen?.displayId) { screen?.mode?.let { maxOf(it.physicalWidth, it.physicalHeight) to minOf(it.physicalWidth, it.physicalHeight) } }
    val target = remember(screen?.displayId, wanted) { if (screen != null && native != null) monitorTarget(screen, native, wanted) else null }
    val pointer = remember(window, target) { target?.let { MonitorPointer(runtime.wm, window, it.width, it.height) } }
    if (screen != null && target != null && native != null) {
        DisposableEffect(screen.displayId, window, target) {
            val presentation = WindowPresentation(context, screen, runtime, window, target, background)
            // The monitor can go between being listed and being shown on.
            if (runCatching { presentation.show() }.isFailure) onMonitor = false
            // For the Display menu in Linux: what's drawn now, and the resolutions this monitor takes as a signal.
            writeState(stateFile, "on ${target.width}x${target.height}\nnative: ${native.first}x${native.second}\nmodes: ${signalSizes(screen).joinToString(" ")}\n")
            onDispose {
                presentation.closing = true
                runCatching { presentation.dismiss() }
                writeState(stateFile, "off\n")
            }
        }
    }
    // Linux sets the desktop's scale and size for where it now is: a monitor and the phone's screen want different ones.
    if (desktop) {
        LaunchedEffect(window, target) {
            delay(MONITOR_SETTLE_MS)
            runCatching { runtime.widgets.shell("command -v desktop-display >/dev/null && desktop-display apply") }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.background)
            .systemBarsPadding(),
    ) {
        AndroidView(
            factory = { KeyboardSink(it, runtime.keyboard).also { v -> sink = v } },
            modifier = Modifier.size(1.dp),
        )
        // Always the whole screen: a SurfaceView that changes size shows its last frame stretched until the next one,
        // so the keyboard and the extra keys go over it, and only the Linux window gets smaller.
        if (pointer == null) {
            AndroidView(
                factory = { context -> WindowSurfaceView(context, runtime, window) },
                update = {
                    it.background = background
                    it.visibleHeight = visible
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        val keyboardHeight = with(density) { keyboard.toDp() }
        // Where the keyboard is still sliding in, so the window's old frame doesn't show beneath it.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(keyboardHeight).background(Palette.background))
        Column(Modifier.fillMaxSize().padding(bottom = keyboardHeight)) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { visible = it.height },
            ) {
                if (pointer == null) {
                    // Over the SurfaceView, which Compose's pointer input doesn't reach.
                    // A tap on the prompt line brings the keyboard back, as the ⌨ button does.
                    Box(Modifier.fillMaxSize().linuxWindowInput(runtime.wm, window) { sink?.takeUnless { it.showing }?.show() })
                } else {
                    // The window is on the monitor: the phone is its touchpad.
                    key(pointer) { AndroidView(factory = { TouchpadView(it, pointer) }, modifier = Modifier.fillMaxSize()) }
                    BasicText(
                        "The desktop is on the monitor.\n\n" +
                            "This screen is the touchpad:\n" +
                            "drag to move, tap to click,\n" +
                            "two fingers to scroll or right-click,\n" +
                            "tap then drag to hold the button.\n\n" +
                            "A mouse works too: move it once to start.",
                        style = Mono.copy(color = Palette.foreground),
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                }
                // Moves the desktop between the monitor and the phone; only there while a monitor is plugged in.
                if (desktop && monitor != null) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp)
                            .height(40.dp)
                            .background(Palette.overlay, CircleShape)
                            .clickable { onMonitor = !onMonitor }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(if (pointer == null) "Show on monitor" else "Show on phone", style = Mono.copy(color = Palette.foreground))
                    }
                }
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                        .size(40.dp)
                        .background(Palette.overlay, CircleShape)
                        .clickable { sink?.toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText("⌨", style = Mono.copy(color = Palette.foreground))
                }
            }
            // Below the window rather than over it, so the window shrinks and nothing hides behind the keys; only over a
            // docked keyboard (a hardware keyboard's toolbar takes no height, and that keyboard has these keys).
            if (keyboard > 0) ExtraKeys(runtime, Modifier.background(Palette.background))
        }
    }
}

/**
 * The window's pixels: a [WindowRenderer] draws the Linux surfaces straight
 * into this view's surface, without Compose or bitmaps in between. The
 * window is as wide as the view and [visibleHeight] tall, at its top.
 */
private class WindowSurfaceView(
    context: Context,
    private val runtime: Runtime,
    private val window: XdgToplevel,
    /**
     * For a window on a monitor: the size its picture is drawn at, which Android then stretches over the monitor
     * (see [monitorTarget]). Null on the phone, where the picture is as big as the view.
     */
    private val fixedSize: Pair<Int, Int>? = null,
) : SurfaceView(context),
    SurfaceHolder.Callback {
    private val renderer = WindowRenderer(runtime.server, window)

    var background by renderer::background

    private var surfaceWidth = 0
    private var surfaceHeight = 0

    /** How much of the view, from its top, the keyboard and the extra keys leave the window; 0 for all of it. */
    var visibleHeight = 0
        set(value) {
            if (value == field) return
            field = value
            layoutWindow()
        }

    private fun layoutWindow() {
        if (surfaceWidth == 0) return
        if (fixedSize != null) {
            // The desktop draws one pixel per pixel, whatever the scale Linux apps are told. Asking for the picture's
            // size times that scale makes the desktop exactly as big as the picture, so nothing is stretched or shrunk.
            renderer.visibleHeight = 0
            runtime.wm.applyAppLayout(window, surfaceWidth * runtime.wm.scale, surfaceHeight * runtime.wm.scale)
            return
        }
        val h = if (visibleHeight in 1..surfaceHeight) visibleHeight else surfaceHeight
        renderer.visibleHeight = h
        runtime.wm.applyAppLayout(window, surfaceWidth, h)
    }

    init {
        holder.addCallback(this)
        fixedSize?.let { holder.setFixedSize(it.first, it.second) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        window.renderer = renderer
    }

    override fun onDetachedFromWindow() {
        if (window.renderer === renderer) window.renderer = null
        renderer.release()
        super.onDetachedFromWindow()
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) {
        surfaceWidth = width
        surfaceHeight = height
        renderer.attach(holder.surface, width, height)
        layoutWindow()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = renderer.detach()
}

/** The most lines "auto" draws a window at on a monitor: 1440p. A 4K picture is four times a 1080p one to draw in software. */
private const val MAX_MONITOR_LINES = 1440

/** In the Linux home folder: the size the Display menu asked for on a monitor, and what the launcher is doing about it. */
private const val MONITOR_WANTED = ".config/omalauncher/monitor"
private const val MONITOR_STATE = ".cache/omalauncher/monitor"

/** How long the desktop gets to take its new size before Linux is told to set its scale. */
private const val MONITOR_SETTLE_MS = 1200L

/** Whether [window] is the Linux desktop: labwc inside the launcher is one window, which its library names "wlroots". */
private fun isDesktop(window: XdgToplevel) =
    window.appId == "wlroots" || window.appId == "labwc" || window.title.startsWith("wlroots") || window.title.startsWith("labwc")

/**
 * What to draw on a monitor: a picture of [width] x [height] pixels, and [modeId], the monitor's own signal of that
 * size to switch it to, or 0 when it has none (Android then stretches the picture over the signal it has).
 */
private data class MonitorTarget(
    val width: Int,
    val height: Int,
    val modeId: Int,
)

private fun readWanted(file: File) = runCatching { file.readText().trim() }.getOrDefault("").ifEmpty { "auto" }

private fun writeState(
    file: File,
    text: String,
) {
    runCatching {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }
}

/** The sizes [display] takes as a signal, widest side first, biggest first, each once. */
private fun signalSizes(display: Display) =
    display.supportedModes
        .map { maxOf(it.physicalWidth, it.physicalHeight) to minOf(it.physicalWidth, it.physicalHeight) }
        .distinct()
        .sortedByDescending { it.first * it.second }
        .map { "${it.first}x${it.second}" }

/** [display]'s signal of [width] x [height], the one nearest 60 pictures a second; 0 if it has none. */
private fun signalMode(
    display: Display,
    width: Int,
    height: Int,
) = display.supportedModes
    .filter { maxOf(it.physicalWidth, it.physicalHeight) == width && minOf(it.physicalWidth, it.physicalHeight) == height }
    .minByOrNull { kotlin.math.abs(it.refreshRate - 60f) }
    ?.modeId ?: 0

/**
 * What to draw on [display], whose own resolution is [native].
 *
 * In plain terms: a PC that sends a monitor less than its full resolution sends a real signal of that size and lets
 * the monitor enlarge it. This does the same where the monitor offers such a signal. [wanted] is "auto" or a size
 * like "1024x768" (the Display menu on the desktop writes it):
 * - auto: the monitor's own resolution, or, when that has more than [MAX_MONITOR_LINES] lines, its biggest signal of
 *   the same shape within them (a 3840x2160 monitor gets 2560x1440).
 * - a size: that size, as a real signal if the monitor has one (a CRT's 1024x768). If not, the picture is stretched
 *   over the whole signal, which a 4:3 screen behind a plain adapter then shows in the right shape.
 */
private fun monitorTarget(
    display: Display,
    native: Pair<Int, Int>,
    wanted: String,
): MonitorTarget {
    val asked = Regex("""(\d{3,4})x(\d{3,4})""").matchEntire(wanted)?.destructured?.let { (w, h) -> w.toInt() to h.toInt() }
    if (asked != null) return MonitorTarget(asked.first, asked.second, signalMode(display, asked.first, asked.second))
    val (w, h) = native
    if (h <= MAX_MONITOR_LINES) return MonitorTarget(w, h, signalMode(display, w, h))
    val shape = w.toFloat() / h
    val best =
        display.supportedModes
            .map { maxOf(it.physicalWidth, it.physicalHeight) to minOf(it.physicalWidth, it.physicalHeight) }
            .filter { it.second <= MAX_MONITOR_LINES && kotlin.math.abs(it.first.toFloat() / it.second - shape) < 0.02f }
            .maxByOrNull { it.second }
    if (best != null) return MonitorTarget(best.first, best.second, signalMode(display, best.first, best.second))
    return MonitorTarget((w.toLong() * MAX_MONITOR_LINES / h).toInt() / 2 * 2, MAX_MONITOR_LINES, 0)
}

/**
 * A plugged-in monitor (or TV, or glasses) that apps may draw on, null when there is none. Screens made by software
 * are left out by asking for a secure one: scrcpy's (the Phone windows of the desktop) also say they take
 * presentations, and drawing the desktop into one of its own windows would be a loop.
 */
private fun findMonitor(displays: DisplayManager): Display? =
    displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull {
        it.displayId != Display.DEFAULT_DISPLAY && it.flags and Display.FLAG_SECURE != 0 && it.flags and Display.FLAG_PRIVATE == 0
    }

/** [findMonitor], kept up to date as monitors are plugged in and pulled out. */
@Composable
private fun rememberMonitor(): Display? {
    val context = LocalContext.current
    val displays = remember { context.getSystemService(DisplayManager::class.java) }
    var monitor by remember { mutableStateOf(findMonitor(displays)) }
    DisposableEffect(displays) {
        val listener =
            object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {
                    monitor = findMonitor(displays)
                }

                override fun onDisplayRemoved(displayId: Int) {
                    monitor = findMonitor(displays)
                }

                override fun onDisplayChanged(displayId: Int) = Unit
            }
        displays.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { displays.unregisterDisplayListener(listener) }
    }
    return monitor
}

/**
 * A Linux window drawn on a monitor: Android's way for an app to put its own picture on a second screen instead of
 * the copy of the phone's. It takes no touches and no keys, so the activity on the phone keeps the keyboard and the
 * mouse. Android closes it by itself when the monitor is unplugged.
 */
private class WindowPresentation(
    context: Context,
    display: Display,
    private val runtime: Runtime,
    private val toplevel: XdgToplevel,
    private val target: MonitorTarget,
    private val fill: Int,
) : Presentation(context, display) {
    /** Set when the launcher itself takes it down. */
    var closing = false

    /**
     * Android cancels a presentation whenever its screen's size changes, and switching the monitor to another signal
     * is such a change. This one stays: it holds a single view that is laid out again. It still goes when the monitor
     * is unplugged.
     */
    override fun cancel() {
        if (closing || !display.isValid) super.cancel()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (target.modeId != 0) window?.let { w -> w.attributes = w.attributes.apply { preferredDisplayModeId = target.modeId } }
        window?.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        )
        window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
        val view = WindowSurfaceView(context, runtime, toplevel, target.width to target.height)
        view.background = fill
        setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}
