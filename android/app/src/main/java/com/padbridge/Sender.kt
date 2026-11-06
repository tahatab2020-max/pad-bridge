package com.padbridge

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Background thread that owns the TCP connection to the PC.
 * - Keeps only the newest state per player (older ones are dropped, so no lag builds up).
 * - Reconnects by itself every second until the PC script is running.
 * - Re-sends every player's last state once a second when idle, which also detects a dead link.
 * Wire format per player: 1 byte player index + 12 byte state.
 */
class Sender(private val onStatus: (String) -> Unit) : Thread("PadSender") {

    companion object {
        const val MAX = 4
    }

    @Volatile private var host = "127.0.0.1"
    @Volatile private var port = 5555
    @Volatile private var reconnect = false
    @Volatile private var running = true

    private val pending = Array(MAX) { AtomicReference<ByteArray?>(null) }
    private val last = arrayOfNulls<ByteArray>(MAX)
    private val signal = Semaphore(0)
    private var lastStatus = ""

    init {
        isDaemon = true
    }

    fun configure(newHost: String, newPort: Int) {
        host = newHost
        port = newPort
        reconnect = true
        signal.release()
    }

    fun post(player: Int, frame: ByteArray) {
        if (player < 0 || player >= MAX) return
        pending[player].set(frame)
        signal.release()
    }

    fun shutdown() {
        running = false
        signal.release()
    }

    private fun status(s: String) {
        if (s != lastStatus) {
            lastStatus = s
            onStatus(s)
        }
    }

    private fun waitSignal() {
        try {
            signal.tryAcquire(1, TimeUnit.SECONDS)
        } catch (ex: InterruptedException) {
            // ignore
        }
        signal.drainPermits()
    }

    override fun run() {
        var socket: Socket? = null
        while (running) {
            waitSignal()
            if (!running) break

            if (reconnect) {
                reconnect = false
                try { socket?.close() } catch (ex: Exception) { }
                socket = null
            }

            val fresh = arrayOfNulls<ByteArray>(MAX)
            for (i in 0 until MAX) {
                val f = pending[i].getAndSet(null)
                if (f != null) {
                    fresh[i] = f
                    last[i] = f
                }
            }

            var justConnected = false
            if (socket == null) {
                val s = Socket()
                try {
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(host, port), 500)
                    // With `adb reverse` the phone side always accepts, so check the PC really answered.
                    s.soTimeout = 150
                    try {
                        if (s.getInputStream().read() == -1) throw IOException("closed")
                    } catch (ex: SocketTimeoutException) {
                        // open and idle: the PC script is listening
                    }
                    s.soTimeout = 0
                    socket = s
                    justConnected = true
                    status("Connected to PC ($host:$port)")
                } catch (ex: Exception) {
                    try { s.close() } catch (ex2: Exception) { }
                    status("Not connected - is the PC script running?")
                    continue
                }
            }

            val sock = socket ?: continue
            // New connection or idle heartbeat: send everyone's last state. Otherwise only what changed.
            val src = if (justConnected || fresh.all { it == null }) last else fresh
            val out = ByteArrayOutputStream()
            for (i in 0 until MAX) {
                val f = src[i] ?: continue
                out.write(i)
                out.write(f, 0, f.size)
            }
            if (out.size() == 0) continue

            try {
                val stream = sock.getOutputStream()
                stream.write(out.toByteArray())
                stream.flush()
            } catch (ex: Exception) {
                try { sock.close() } catch (ex2: Exception) { }
                socket = null
                status("Disconnected - retrying...")
            }
        }
        try { socket?.close() } catch (ex: Exception) { }
    }
}
