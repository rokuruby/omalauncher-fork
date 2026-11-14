package dev.omalauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.omalauncher.wayland.AXIS_SOURCE_FINGER
import dev.omalauncher.wayland.AXIS_SOURCE_WHEEL
import dev.omalauncher.wayland.BTN_LEFT
import dev.omalauncher.wayland.BTN_MIDDLE
import dev.omalauncher.wayland.BTN_RIGHT
import dev.omalauncher.wayland.XdgToplevel
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The Linux pointer of a window that is drawn on a monitor instead of the phone (see LinuxWindowActivity).
 *
 * In plain terms: Android keeps its own mouse arrow on the phone's screen, so it can't point at things on the
 * monitor. This keeps a position on the monitor's picture ([width] x [height] pixels) that the phone moves by
 * small steps (a mouse's movement, or a finger dragged over the phone as a touchpad), and tells the Linux window
 * where it is; the Linux desktop draws its own arrow there.
 */
class MonitorPointer(
    private val wm: WindowManager,
    private val window: XdgToplevel,
    private val width: Int,
    private val height: Int,
) {
    private var x = width / 2f
    private var y = height / 2f

    /** The buttons held, as [LEFT], [RIGHT] and [MIDDLE] bits. */
    private var buttons = 0

    private fun hit() = window.renderer?.hitTest(x, y)

    fun moveBy(
        dx: Float,
        dy: Float,
    ) {
        x = (x + dx).coerceIn(0f, width - 1f)
        y = (y + dy).coerceIn(0f, height - 1f)
        val hit = hit()
        // Keep the grab on the pressed surface while dragging, as the mouse over a window on the phone does.
        if (buttons == 0 || wm.server.input.pointerFocus == hit?.surface) wm.pointerMove(hit)
    }

    fun button(
        bit: Int,
        pressed: Boolean,
    ) {
        val code = CODES[bit] ?: return
        if (pressed == (buttons and bit != 0)) return
        if (pressed) {
            val hit = hit()
            if (!wm.onPress(window, hit)) return
            wm.pointerMove(hit)
            buttons = buttons or bit
            wm.pointerButton(code, true)
        } else {
            buttons = buttons and bit.inv()
            wm.pointerButton(code, false)
        }
    }

    fun click(bit: Int) {
        button(bit, true)
        button(bit, false)
    }

    /** Presses and releases buttons so that exactly those in [mask] are held. */
    fun setButtons(mask: Int) {
        for (bit in CODES.keys) button(bit, mask and bit != 0)
    }

    /** A mouse wheel turned by [h] and [v] notches, as Android reports them (up is positive). */
    fun wheel(
        h: Float,
        v: Float,
    ) = wm.scroll(h * WHEEL_STEP, -v * WHEEL_STEP, AXIS_SOURCE_WHEEL, (-v).roundToInt())

    fun fingerScroll(
        dx: Float,
        dy: Float,
    ) = wm.scroll(dx, dy, AXIS_SOURCE_FINGER)

    fun scrollStop() = wm.scrollStop()

    companion object {
        const val LEFT = 1
        const val RIGHT = 2
        const val MIDDLE = 4
        private const val WHEEL_STEP = 15f
        private val CODES = mapOf(LEFT to BTN_LEFT, RIGHT to BTN_RIGHT, MIDDLE to BTN_MIDDLE)
    }
}

/**
 * The phone's screen as a touchpad for a [MonitorPointer], and the place a mouse is caught.
 *
 * Fingers: drag moves the pointer, a tap is a left click, a tap with two fingers a right click, a drag with two
 * fingers scrolls, and a tap followed at once by a drag holds the left button (to move a window or select text).
 *
 * Mouse: the first movement or click over this view "captures" the mouse: Android hides its own arrow and hands
 * over the raw movement instead of a position, which moves the pointer on the monitor. Android gives the mouse
 * back when this window loses focus (Home, Recents, another app).
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class TouchpadView(
    context: Context,
    private val pointer: MonitorPointer,
) : View(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private var lastX = 0f
    private var lastY = 0f
    private var travel = 0f
    private var downTime = 0L
    private var lastTap = 0L

    /** The most fingers down at once in this gesture. */
    private var fingers = 0
    private var moved = false
    private var scrolled = false
    private var dragging = false

    // Captured mouse events go to the focused view, so this one has to be able to take the focus.
    init {
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
    }

    private fun capture() {
        if (hasPointerCapture()) return
        requestFocus()
        requestPointerCapture()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        // Buttons held as the window goes would stay down: their release goes elsewhere.
        if (!hasWindowFocus) pointer.setButtons(0)
    }

    /** A mouse that isn't captured yet moves Android's arrow over this view. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            capture()
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    /** The captured mouse: x and y are how far it moved, not where it is. */
    override fun onCapturedPointerEvent(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)) return true
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            pointer.wheel(event.getAxisValue(MotionEvent.AXIS_HSCROLL), event.getAxisValue(MotionEvent.AXIS_VSCROLL))
            return true
        }
        var dx = event.x
        var dy = event.y
        for (i in 0 until event.historySize) {
            dx += event.getHistoricalX(i)
            dy += event.getHistoricalY(i)
        }
        if (dx != 0f || dy != 0f) pointer.moveBy(dx * MOUSE_SPEED, dy * MOUSE_SPEED)
        val state = event.buttonState
        pointer.setButtons(
            (if (state and MotionEvent.BUTTON_PRIMARY != 0) MonitorPointer.LEFT else 0) or
                (if (state and MotionEvent.BUTTON_SECONDARY != 0) MonitorPointer.RIGHT else 0) or
                (if (state and MotionEvent.BUTTON_TERTIARY != 0) MonitorPointer.MIDDLE else 0),
        )
        return true
    }

    /** Where the fingers are on average, leaving out the one at index [skip] (the finger being lifted). */
    private fun centre(
        event: MotionEvent,
        skip: Int = -1,
    ) {
        var x = 0f
        var y = 0f
        var n = 0
        for (i in 0 until event.pointerCount) {
            if (i == skip) continue
            x += event.getX(i)
            y += event.getY(i)
            n++
        }
        if (n == 0) return
        lastX = x / n
        lastY = y / n
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            capture()
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fingers = 1
                moved = false
                scrolled = false
                travel = 0f
                downTime = event.eventTime
                centre(event)
                if (event.eventTime - lastTap < DOUBLE_TAP_MS) {
                    dragging = true
                    pointer.button(MonitorPointer.LEFT, true)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                fingers = maxOf(fingers, event.pointerCount)
                centre(event)
            }
            MotionEvent.ACTION_POINTER_UP -> centre(event, skip = event.actionIndex)
            MotionEvent.ACTION_MOVE -> {
                val fromX = lastX
                val fromY = lastY
                centre(event)
                val dx = lastX - fromX
                val dy = lastY - fromY
                travel += hypot(dx, dy)
                if (travel > slop) moved = true
                if (moved) {
                    if (event.pointerCount >= 2) {
                        scrolled = true
                        pointer.fingerScroll(-dx, -dy)
                    } else {
                        pointer.moveBy(dx * TOUCH_SPEED, dy * TOUCH_SPEED)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (scrolled) pointer.scrollStop()
                if (dragging) {
                    dragging = false
                    pointer.button(MonitorPointer.LEFT, false)
                } else if (event.actionMasked == MotionEvent.ACTION_UP && !moved && event.eventTime - downTime < TAP_MS) {
                    if (fingers >= 2) {
                        pointer.click(MonitorPointer.RIGHT)
                    } else {
                        pointer.click(MonitorPointer.LEFT)
                        lastTap = event.eventTime
                    }
                }
                fingers = 0
            }
        }
        return true
    }

    private companion object {
        /** Monitor pixels per unit of mouse movement, and per phone pixel a finger moves. */
        const val MOUSE_SPEED = 1.5f
        const val TOUCH_SPEED = 1.5f
        const val TAP_MS = 250L
        const val DOUBLE_TAP_MS = 300L
    }
}
