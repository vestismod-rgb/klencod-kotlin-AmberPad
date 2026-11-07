package com.amber.pad.input

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** Talks to AmberServer over a localhost socket. All network work happens on a background thread. */
object ServerClient : TouchInjector {
    const val PORT = 41873

    @Volatile var token = ""
    @Volatile var onRel: (Int, Int) -> Unit = { _, _ -> }
    @Volatile var onBtn: (Int, Boolean) -> Unit = { _, _ -> }
    @Volatile var onWheel: (Int) -> Unit = { _ -> }
    @Volatile var onGrabFail: () -> Unit = {}

    private val ht = HandlerThread("amber-io").apply { start() }
    private val io = Handler(ht.looper)
    private val main = Handler(Looper.getMainLooper())
    private var sock: Socket? = null
    private var out: OutputStream? = null
    @Volatile private var wantMouse = false
    @Volatile private var wantGrab = false

    @Synchronized
    private fun closeSock() {
        try { sock?.close() } catch (_: Exception) {}
        sock = null; out = null
    }

    @Synchronized
    private fun connect(): Boolean {
        if (sock?.isClosed == false && out != null) return true
        closeSock()
        return try {
            val s = Socket()
            s.connect(InetSocketAddress("127.0.0.1", PORT), 400)
            s.tcpNoDelay = true
            sock = s
            out = s.getOutputStream().also { it.write("AUTH $token\n".toByteArray()); it.flush() }
            Thread { readLoop(s) }.apply { isDaemon = true }.start()
            if (wantMouse) out?.let { it.write("MOUSE 1\n".toByteArray()); it.flush() }
            if (wantGrab) out?.let { it.write("GRAB 1\n".toByteArray()); it.flush() }
            true
        } catch (_: Exception) {
            closeSock(); false
        }
    }

    private fun readLoop(s: Socket) {
        try {
            s.getInputStream().bufferedReader().forEachLine { line ->
                val p = line.split(' ')
                when (p[0]) {
                    "REL" -> main.post { onRel(p[1].toInt(), p[2].toInt()) }
                    "BTN" -> main.post { onBtn(p[1].toInt(), p[2] == "1") }
                    "WHL" -> main.post { onWheel(p[1].toInt()) }
                    "GRABFAIL" -> main.post { onGrabFail() }
                }
            }
        } catch (_: Exception) {
        } finally {
            synchronized(this) { if (sock === s) closeSock() }
        }
    }

    private fun write(line: String) {
        io.post {
            if (!connect()) return@post
            synchronized(this) {
                try { out?.let { it.write((line + "\n").toByteArray()); it.flush() } } catch (_: Exception) { closeSock() }
            }
        }
    }

    fun ensure() { io.post { connect() } }
    fun mouse(on: Boolean) { wantMouse = on; write("MOUSE ${if (on) 1 else 0}") }
    fun grab(on: Boolean) { wantGrab = on; write("GRAB ${if (on) 1 else 0}") }
    fun quit() = write("QUIT")

    /** Cheap "is the server listening" check (does not authenticate, so it never kicks the real session). */
    fun probe(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 250) }
        true
    } catch (_: Exception) { false }

    override fun down(id: Int, x: Float, y: Float) = write("D $id ${x.toInt()} ${y.toInt()}")
    override fun move(id: Int, x: Float, y: Float) = write("M $id ${x.toInt()} ${y.toInt()}")
    override fun up(id: Int) = write("U $id")
    override fun releaseAll() = write("R")
}
