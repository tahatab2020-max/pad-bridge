package com.padbridge

import android.app.Activity
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Reads a Bluetooth gamepad and streams its state to the PC over USB.
 * The PC side is reached through `adb reverse tcp:5555 tcp:5555`, so the
 * phone just connects to its own localhost:5555 and the bytes ride the USB cable.
 */
class MainActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private var socket: Socket? = null
    private lateinit var status: TextView

    // Current pad state (XInput layout)
    private var buttons = 0
    private var lt = 0
    private var rt = 0
    private var lx = 0
    private var ly = 0
    private var rx = 0
    private var ry = 0
    private var hatX = 0
    private var hatY = 0
    private var dpadKeys = 0

    private val buf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = TextView(this).apply {
            textSize = 18f
            setPadding(48, 96, 48, 48)
            text = "PadBridge\n\n1. Connect phone to PC with USB (USB debugging on)\n" +
                "2. Run start_pc.bat on the PC\n3. Press any button on your controller"
        }
        setContentView(status)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.execute { try { socket?.close() } catch (_: Exception) {} }
        io.shutdown()
    }

    private fun isPad(src: Int) =
        (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (!isPad(e.source)) return super.dispatchKeyEvent(e)
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
            KeyEvent.KEYCODE_BUTTON_MODE -> 0x0400
            KeyEvent.KEYCODE_DPAD_UP -> { setDpadKey(0x0001, down); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { setDpadKey(0x0002, down); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { setDpadKey(0x0004, down); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { setDpadKey(0x0008, down); return true }
            // Some pads report triggers as buttons
            KeyEvent.KEYCODE_BUTTON_L2 -> { lt = if (down) 255 else 0; send(); return true }
            KeyEvent.KEYCODE_BUTTON_R2 -> { rt = if (down) 255 else 0; send(); return true }
            else -> return super.dispatchKeyEvent(e)
        }
        buttons = if (down) buttons or bit else buttons and bit.inv()
        send()
        return true
    }

    private fun setDpadKey(bit: Int, down: Boolean) {
        dpadKeys = if (down) dpadKeys or bit else dpadKeys and bit.inv()
        send()
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (!isPad(e.source) || e.action != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(e)
        }
        lx = stick(e.getAxisValue(MotionEvent.AXIS_X))
        ly = -stick(e.getAxisValue(MotionEvent.AXIS_Y))

        // Right stick is Z/RZ on most Xbox-style pads, RX/RY on others
        val zx = e.getAxisValue(MotionEvent.AXIS_Z)
        val rxa = e.getAxisValue(MotionEvent.AXIS_RX)
        val zy = e.getAxisValue(MotionEvent.AXIS_RZ)
        val rya = e.getAxisValue(MotionEvent.AXIS_RY)
        rx = stick(if (abs(zx) > abs(rxa)) zx else rxa)
        ry = -stick(if (abs(zy) > abs(rya)) zy else rya)

        val l = maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
        val r = maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))
        lt = (l.coerceIn(0f, 1f) * 255).toInt()
        rt = (r.coerceIn(0f, 1f) * 255).toInt()

        hatX = e.getAxisValue(MotionEvent.AXIS_HAT_X).toInt()
        hatY = e.getAxisValue(MotionEvent.AXIS_HAT_Y).toInt()
        send()
        return true
    }

    private fun stick(v: Float): Int {
        val d = if (abs(v) < 0.05f) 0f else v // tiny deadzone for drift
        return (d.coerceIn(-1f, 1f) * 32767).toInt()
    }

    private fun send() {
        var b = buttons or dpadKeys
        if (hatY < 0) b = b or 0x0001
        if (hatY > 0) b = b or 0x0002
        if (hatX < 0) b = b or 0x0004
        if (hatX > 0) b = b or 0x0008

        val frame = ByteArray(12)
        synchronized(buf) {
            buf.clear()
            buf.putShort(b.toShort())
            buf.put(lt.toByte())
            buf.put(rt.toByte())
            buf.putShort(lx.toShort())
            buf.putShort(ly.toShort())
            buf.putShort(rx.toShort())
            buf.putShort(ry.toShort())
            buf.flip()
            buf.get(frame)
        }

        if (io.isShutdown) return
        io.execute {
            try {
                var s = socket
                if (s == null || s.isClosed) {
                    s = Socket().apply {
                        tcpNoDelay = true
                        connect(InetSocketAddress("127.0.0.1", 5555), 300)
                    }
                    socket = s
                    runOnUiThread { status.text = "Connected to PC" }
                }
                s.getOutputStream().apply { write(frame); flush() }
            } catch (_: Exception) {
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                runOnUiThread { status.text = "Not connected. Is the PC script running?" }
            }
        }
    }
}
