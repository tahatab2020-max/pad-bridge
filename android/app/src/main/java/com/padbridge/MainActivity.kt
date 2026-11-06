package com.padbridge

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Reads up to 4 Bluetooth gamepads and streams their state to the PC.
 * Each controller gets its own player slot, in the order they are first used.
 * USB mode: the PC runs `adb reverse tcp:5555 tcp:5555`, so 127.0.0.1:5555 on the phone reaches the PC.
 * Wi-Fi mode: type the PC's IP address in the settings instead.
 */
class MainActivity : Activity() {

    /** State of one physical controller. */
    private class Pad(val deviceId: Int, var name: String) {
        var buttons = 0
        var dpadKeys = 0
        var lt = 0
        var rt = 0
        var lx = 0f
        var ly = 0f
        var rx = 0f
        var ry = 0f
        var hatX = 0
        var hatY = 0

        fun reset() {
            buttons = 0
            dpadKeys = 0
            lt = 0
            rt = 0
            lx = 0f
            ly = 0f
            rx = 0f
            ry = 0f
            hatX = 0
            hatY = 0
        }
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var sender: Sender
    private lateinit var statusView: TextView
    private lateinit var playersView: TextView
    private lateinit var liveView: TextView
    private lateinit var settingsView: View
    private var statusText = "Starting..."

    // Settings (loaded from preferences)
    private var host = "127.0.0.1"
    private var port = 5555
    private var deadL = 0.05f
    private var deadR = 0.05f
    private var invL = false
    private var invR = false
    private var swapSticks = false
    private var nintendo = false
    private var swapPlayers = false
    private var showLive = true
    private var keepOn = true
    private var blackMode = false

    // One entry per player slot (null = free)
    private val slots = arrayOfNulls<Pad>(Sender.MAX)
    // Which output player numbers have been sent to at least once
    private val posted = BooleanArray(Sender.MAX)
    private var lastUi = 0L

    private val inputManager by lazy { getSystemService(Context.INPUT_SERVICE) as InputManager }

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {}
        override fun onInputDeviceChanged(deviceId: Int) {}
        override fun onInputDeviceRemoved(deviceId: Int) {
            // Controller turned off or disconnected: release everything and free its player slot
            val idx = slots.indexOfFirst { it != null && it.deviceId == deviceId }
            if (idx >= 0) {
                val p = slots[idx]
                if (p != null) {
                    p.reset()
                    push(p)
                }
                slots[idx] = null
                updatePlayers()
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("padbridge", Context.MODE_PRIVATE)
        loadSettings()
        settingsView = buildUi()
        setContentView(settingsView)

        sender = Sender { s ->
            statusText = s
            runOnUiThread { statusView.text = s }
        }
        sender.configure(host, port)
        sender.start()
    }

    override fun onResume() {
        super.onResume()
        inputManager.registerInputDeviceListener(deviceListener, null)
    }

    override fun onPause() {
        super.onPause()
        inputManager.unregisterInputDeviceListener(deviceListener)
        // Leaving the app: send a neutral state so no button stays held on the PC
        for (p in slots) {
            if (p != null) {
                p.reset()
                push(p)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sender.shutdown()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (blackMode) exitBlack() else super.onBackPressed()
    }

    // ---------------------------------------------------------------- settings

    private fun loadSettings() {
        host = (prefs.getString("host", "127.0.0.1") ?: "127.0.0.1").trim().ifEmpty { "127.0.0.1" }
        port = prefs.getInt("port", 5555)
        deadL = prefs.getInt("deadL", 5) / 100f
        deadR = prefs.getInt("deadR", 5) / 100f
        invL = prefs.getBoolean("invL", false)
        invR = prefs.getBoolean("invR", false)
        swapSticks = prefs.getBoolean("swapSticks", false)
        nintendo = prefs.getBoolean("nintendo", false)
        swapPlayers = prefs.getBoolean("swapPlayers", false)
        showLive = prefs.getBoolean("showLive", true)
        keepOn = prefs.getBoolean("keepOn", true)
        applyKeepOn()
    }

    private fun applyKeepOn() {
        if (keepOn || blackMode) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float, bold: Boolean = false): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = size
        if (bold) tv.setTypeface(tv.typeface, Typeface.BOLD)
        tv.setPadding(0, dp(12), 0, dp(4))
        return tv
    }

    private fun addSwitch(parent: LinearLayout, title: String, key: String, def: Boolean) {
        val sw = Switch(this)
        sw.text = title
        sw.isChecked = prefs.getBoolean(key, def)
        sw.setPadding(0, dp(8), 0, dp(8))
        sw.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(key, checked).apply()
            loadSettings()
            pushAll()
        }
        parent.addView(sw)
    }

    private fun addSeek(parent: LinearLayout, title: String, key: String, max: Int, def: Int) {
        val tv = label("", 14f)
        val sb = SeekBar(this)
        sb.max = max
        val cur = prefs.getInt(key, def)
        sb.progress = cur
        tv.text = "$title: $cur%"
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tv.text = "$title: $progress%"
                prefs.edit().putInt(key, progress).apply()
                loadSettings()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        parent.addView(tv)
        parent.addView(sb)
    }

    private fun addEdit(parent: LinearLayout, title: String, key: String, initial: String, numeric: Boolean) {
        parent.addView(label(title, 14f))
        val et = EditText(this)
        et.inputType = if (numeric) {
            InputType.TYPE_CLASS_NUMBER
        } else {
            (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        }
        et.setSingleLine(true)
        et.setText(initial)
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val t = (s?.toString() ?: "").trim()
                if (numeric) {
                    val n = t.toIntOrNull()
                    if (n == null || n < 1 || n > 65535) return
                    prefs.edit().putInt(key, n).apply()
                } else {
                    prefs.edit().putString(key, t).apply()
                }
                loadSettings()
                sender.configure(host, port)
            }
        })
        parent.addView(et)
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(40), dp(20), dp(32))
        scroll.addView(col)

        col.addView(label("PadBridge", 24f, true))
        statusView = label(statusText, 16f)
        col.addView(statusView)

        col.addView(label("Players", 18f, true))
        playersView = label("", 14f)
        col.addView(playersView)
        updatePlayers()

        liveView = label("", 13f)
        liveView.typeface = Typeface.MONOSPACE
        col.addView(liveView)

        val resetPlayersBtn = Button(this)
        resetPlayersBtn.text = "Reassign players"
        resetPlayersBtn.setOnClickListener { resetPlayers() }
        col.addView(resetPlayersBtn)
        col.addView(label("Clears the player list. The next controller you press becomes Player 1, the one after Player 2, and so on.", 12f))
        addSwitch(col, "Swap player 1 and 2", "swapPlayers", false)

        col.addView(label("Connection", 18f, true))
        addEdit(col, "PC address (127.0.0.1 = USB cable, or the PC's Wi-Fi IP)", "host", host, false)
        addEdit(col, "Port", "port", port.toString(), true)

        col.addView(label("Sticks (applies to all controllers)", 18f, true))
        addSeek(col, "Left stick deadzone", "deadL", 40, 5)
        addSeek(col, "Right stick deadzone", "deadR", 40, 5)
        addSwitch(col, "Invert left stick Y", "invL", false)
        addSwitch(col, "Invert right stick Y", "invR", false)
        addSwitch(col, "Swap left and right sticks", "swapSticks", false)

        col.addView(label("Buttons", 18f, true))
        addSwitch(col, "Nintendo layout (swap A/B and X/Y)", "nintendo", false)

        col.addView(label("Screen", 18f, true))
        addSwitch(col, "Keep screen on while open", "keepOn", true)
        addSwitch(col, "Show live input", "showLive", true)

        val blackBtn = Button(this)
        blackBtn.text = "Black screen mode"
        blackBtn.setOnClickListener { enterBlack() }
        col.addView(blackBtn)
        col.addView(label("Dims the screen to its lowest brightness and keeps the app in front so the controllers keep working. Long-press the screen (or press Back) to exit.", 12f))

        val resetBtn = Button(this)
        resetBtn.text = "Reset all settings"
        resetBtn.setOnClickListener {
            prefs.edit().clear().apply()
            loadSettings()
            settingsView = buildUi()
            setContentView(settingsView)
            sender.configure(host, port)
            pushAll()
        }
        col.addView(resetBtn)

        return scroll
    }

    private fun updatePlayers() {
        if (!::playersView.isInitialized) return
        val sb = StringBuilder()
        for (i in 0 until Sender.MAX) {
            val p = slots[i] ?: continue
            sb.append("Player ").append(outputIndex(i) + 1).append(": ").append(p.name).append('\n')
        }
        playersView.text = if (sb.isEmpty()) {
            "No controller yet. Press a button on controller 1, then on controller 2."
        } else {
            sb.toString().trimEnd()
        }
    }

    private fun resetPlayers() {
        for (i in 0 until Sender.MAX) {
            val p = slots[i]
            if (p != null) {
                p.reset()
                push(p)
                slots[i] = null
            }
        }
        updatePlayers()
        if (::liveView.isInitialized) liveView.text = ""
    }

    // ---------------------------------------------------------------- black screen mode

    @Suppress("DEPRECATION")
    private fun enterBlack() {
        blackMode = true
        val v = View(this)
        v.setBackgroundColor(Color.BLACK)
        v.setOnLongClickListener {
            exitBlack()
            true
        }
        setContentView(v)
        val lp = window.attributes
        lp.screenBrightness = 0f
        window.attributes = lp
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        applyKeepOn()
        Toast.makeText(this, "Screen dimmed. Long-press the screen to exit.", Toast.LENGTH_LONG).show()
    }

    @Suppress("DEPRECATION")
    private fun exitBlack() {
        blackMode = false
        setContentView(settingsView)
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        applyKeepOn()
    }

    // ---------------------------------------------------------------- input

    private fun isPad(src: Int) =
        (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    /** Finds the controller's slot, or gives it the first free one. Null if all 4 slots are taken. */
    private fun padFor(deviceId: Int, name: String?): Pad? {
        for (p in slots) {
            if (p != null && p.deviceId == deviceId) return p
        }
        val free = slots.indexOfFirst { it == null }
        if (free < 0) return null
        val p = Pad(deviceId, name ?: "Controller")
        slots[free] = p
        updatePlayers()
        return p
    }

    /** Player number sent to the PC (0-based). */
    private fun outputIndex(slot: Int): Int = if (swapPlayers && slot < 2) 1 - slot else slot

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (!isPad(e.source)) return super.dispatchKeyEvent(e)
        val pad = padFor(e.deviceId, e.device?.name) ?: return super.dispatchKeyEvent(e)
        val down = e.action == KeyEvent.ACTION_DOWN
        val bit = when (e.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> 0x1000
            KeyEvent.KEYCODE_BUTTON_B -> 0x2000
            KeyEvent.KEYCODE_BUTTON_X -> 0x4000
            KeyEvent.KEYCODE_BUTTON_Y -> 0x8000
            KeyEvent.KEYCODE_BUTTON_L1 -> 0x0100
            KeyEvent.KEYCODE_BUTTON_R1 -> 0x0200
            KeyEvent.KEYCODE_BUTTON_THUMBL -> 0x0040
            KeyEvent.KEYCODE_BUTTON_THUMBR -> 0x0080
            KeyEvent.KEYCODE_BUTTON_START -> 0x0010
            KeyEvent.KEYCODE_BUTTON_SELECT -> 0x0020
            KeyEvent.KEYCODE_BACK -> 0x0020
            KeyEvent.KEYCODE_BUTTON_MODE -> 0x0400
            KeyEvent.KEYCODE_DPAD_UP -> { setDpadKey(pad, 0x0001, down); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { setDpadKey(pad, 0x0002, down); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { setDpadKey(pad, 0x0004, down); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { setDpadKey(pad, 0x0008, down); return true }
            // Some pads report triggers as buttons
            KeyEvent.KEYCODE_BUTTON_L2 -> { pad.lt = if (down) 255 else 0; push(pad); return true }
            KeyEvent.KEYCODE_BUTTON_R2 -> { pad.rt = if (down) 255 else 0; push(pad); return true }
            else -> return super.dispatchKeyEvent(e)
        }
        pad.buttons = if (down) pad.buttons or bit else pad.buttons and bit.inv()
        push(pad)
        return true
    }

    private fun setDpadKey(pad: Pad, bit: Int, down: Boolean) {
        pad.dpadKeys = if (down) pad.dpadKeys or bit else pad.dpadKeys and bit.inv()
        push(pad)
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (!isPad(e.source) || e.action != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(e)
        }
        val pad = padFor(e.deviceId, e.device?.name) ?: return super.dispatchGenericMotionEvent(e)

        pad.lx = e.getAxisValue(MotionEvent.AXIS_X)
        pad.ly = e.getAxisValue(MotionEvent.AXIS_Y)

        // Right stick is Z/RZ on most Xbox-style pads, RX/RY on others
        val zx = e.getAxisValue(MotionEvent.AXIS_Z)
        val rxa = e.getAxisValue(MotionEvent.AXIS_RX)
        val zy = e.getAxisValue(MotionEvent.AXIS_RZ)
        val rya = e.getAxisValue(MotionEvent.AXIS_RY)
        pad.rx = if (abs(zx) > abs(rxa)) zx else rxa
        pad.ry = if (abs(zy) > abs(rya)) zy else rya

        val l = maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
        val r = maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))
        pad.lt = (l.coerceIn(0f, 1f) * 255).toInt()
        pad.rt = (r.coerceIn(0f, 1f) * 255).toInt()

        pad.hatX = e.getAxisValue(MotionEvent.AXIS_HAT_X).toInt()
        pad.hatY = e.getAxisValue(MotionEvent.AXIS_HAT_Y).toInt()
        push(pad)
        return true
    }

    // ---------------------------------------------------------------- frame building

    /** Circular deadzone with rescaling, so the stick still reaches full range. */
    private fun deadzone(x: Float, y: Float, dz: Float): Pair<Float, Float> {
        val mag = sqrt(x * x + y * y)
        if (mag <= dz || mag == 0f) return Pair(0f, 0f)
        val scaled = ((mag - dz) / (1f - dz)).coerceAtMost(1f)
        val k = scaled / mag
        return Pair(x * k, y * k)
    }

    private fun swapBits(value: Int, a: Int, b: Int): Int {
        val hasA = (value and a) != 0
        val hasB = (value and b) != 0
        var v = value and a.inv() and b.inv()
        if (hasA) v = v or b
        if (hasB) v = v or a
        return v
    }

    private fun toAxis(v: Float): Short = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()

    private fun buildFrame(p: Pad): ByteArray {
        var b = p.buttons or p.dpadKeys
        if (p.hatY < 0) b = b or 0x0001
        if (p.hatY > 0) b = b or 0x0002
        if (p.hatX < 0) b = b or 0x0004
        if (p.hatX > 0) b = b or 0x0008
        if (nintendo) {
            b = swapBits(b, 0x1000, 0x2000)
            b = swapBits(b, 0x4000, 0x8000)
        }

        val (lx0, ly0) = deadzone(p.lx, p.ly, deadL)
        val (rx0, ry0) = deadzone(p.rx, p.ry, deadR)
        // Android: up is negative. XInput: up is positive. So Y is flipped by default.
        var outLX = lx0
        var outLY = if (invL) ly0 else -ly0
        var outRX = rx0
        var outRY = if (invR) ry0 else -ry0
        if (swapSticks) {
            val tx = outLX
            val ty = outLY
            outLX = outRX
            outLY = outRY
            outRX = tx
            outRY = ty
            b = swapBits(b, 0x0040, 0x0080)
        }

        val bb = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(b.toShort())
        bb.put(p.lt.toByte())
        bb.put(p.rt.toByte())
        bb.putShort(toAxis(outLX))
        bb.putShort(toAxis(outLY))
        bb.putShort(toAxis(outRX))
        bb.putShort(toAxis(outRY))
        return bb.array()
    }

    private fun push(p: Pad) {
        val slot = slots.indexOf(p)
        if (slot >= 0) {
            val out = outputIndex(slot)
            posted[out] = true
            sender.post(out, buildFrame(p))
        }
        if (showLive && !blackMode) updateLive()
    }

    /** Re-sends every player, e.g. after swapping players. Players that moved away get a neutral state. */
    private fun pushAll() {
        if (!::sender.isInitialized) return
        val used = BooleanArray(Sender.MAX)
        for (i in 0 until Sender.MAX) {
            val p = slots[i] ?: continue
            val out = outputIndex(i)
            used[out] = true
            posted[out] = true
            sender.post(out, buildFrame(p))
        }
        for (o in 0 until Sender.MAX) {
            if (!used[o] && posted[o]) sender.post(o, ByteArray(12))
        }
        updatePlayers()
    }

    private fun updateLive() {
        val now = SystemClock.uptimeMillis()
        if (now - lastUi < 60) return
        lastUi = now
        val sb = StringBuilder()
        for (i in 0 until Sender.MAX) {
            val p = slots[i] ?: continue
            sb.append(
                String.format(
                    Locale.US,
                    "P%d  L %.2f,%.2f  R %.2f,%.2f\n    L2 %d  R2 %d  btn 0x%04X\n",
                    outputIndex(i) + 1, p.lx, p.ly, p.rx, p.ry, p.lt, p.rt, p.buttons or p.dpadKeys
                )
            )
        }
        liveView.text = sb.toString().trimEnd()
    }
}
