package com.amber.pad.server

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.io.BufferedReader
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.lang.reflect.Method
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Started with shell privileges:
 *   CLASSPATH=<apk> app_process /system/bin com.amber.pad.server.AmberServer <token> <port>
 * Protocol (one command per line):
 *   AUTH <token> | D id x y | M id x y | U id | R | MOUSE 0/1 | PING | QUIT
 * Events sent back: REL dx dy | BTN code 0/1 | PONG
 */
object AmberServer {
    private var token = ""
    @Volatile private var current: Client? = null
    @Volatile private var mouseOn = false
    private lateinit var injector: Inj
    private val openedMice = HashSet<String>()
    private val miceStreams = HashMap<String, FileInputStream>()
    @Volatile private var grabWanted = false
    private var nativeOk = false

    @JvmStatic
    fun main(args: Array<String>) {
        token = args.getOrNull(0) ?: return
        val port = args.getOrNull(1)?.toIntOrNull() ?: 41873
        args.getOrNull(2)?.let {
            try { System.load("$it/libamberpad.so"); nativeOk = true } catch (_: Throwable) {}
        }
        val ss = try {
            ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            return // already running
        }
        injector = Inj()
        while (true) {
            val s = ss.accept()
            Thread { Client(s).run() }.apply { isDaemon = true }.start()
        }
    }

    private fun send(line: String) {
        current?.write(line)
    }

    private class Client(val s: Socket) {
        private val out: OutputStream = s.getOutputStream()

        fun write(line: String) {
            synchronized(out) {
                try { out.write((line + "\n").toByteArray()); out.flush() } catch (_: Exception) { close() }
            }
        }

        fun close() { try { s.close() } catch (_: Exception) {} }

        fun run() {
            try {
                val r = BufferedReader(InputStreamReader(s.getInputStream()))
                val first = r.readLine() ?: return
                if (first != "AUTH $token") { close(); return }
                current?.close()
                current = this
                while (true) {
                    val line = r.readLine() ?: break
                    val p = line.split(' ')
                    when (p[0]) {
                        "D" -> injector.down(p[1].toInt(), p[2].toFloat(), p[3].toFloat())
                        "M" -> injector.move(p[1].toInt(), p[2].toFloat(), p[3].toFloat())
                        "U" -> injector.up(p[1].toInt())
                        "R" -> injector.releaseAll()
                        "MOUSE" -> { mouseOn = p[1] == "1"; if (mouseOn) startMice() }
                        "GRAB" -> setGrab(p[1] == "1")
                        "PING" -> write("PONG")
                        "QUIT" -> { injector.releaseAll(); System.exit(0) }
                    }
                }
            } catch (_: Exception) {
            } finally {
                injector.releaseAll()
                if (current === this) { current = null; mouseOn = false; setGrab(false) }
                close()
            }
        }
    }

    // ---------------- mouse (evdev) ----------------
    private fun fdInt(fis: FileInputStream): Int {
        val f = FileDescriptor::class.java.getDeclaredField("descriptor")
        f.isAccessible = true
        return f.getInt(fis.fd)
    }

    private fun applyGrab(fis: FileInputStream, on: Boolean): Boolean = try {
        if (nativeOk) Native.grab(fdInt(fis), on) else false
    } catch (_: Throwable) { false }

    /** Exclusive grab hides the system pointer and stops mouse clicks reaching the game. */
    @Synchronized
    private fun setGrab(on: Boolean) {
        grabWanted = on
        var ok = nativeOk
        miceStreams.values.forEach { ok = applyGrab(it, on) && ok }
        if (on && !ok) send("GRABFAIL")
    }

    @Synchronized
    private fun startMice() {
        val text = try { File("/proc/bus/input/devices").readText() } catch (_: Exception) { return }
        text.split("\n\n").forEach { block ->
            val h = block.lines().firstOrNull { it.startsWith("H: Handlers=") } ?: return@forEach
            if (!h.contains("mouse")) return@forEach
            val ev = Regex("event(\\d+)").find(h)?.groupValues?.get(1) ?: return@forEach
            val path = "/dev/input/event$ev"
            if (openedMice.add(path)) Thread { readMouse(path) }.apply { isDaemon = true }.start()
        }
    }

    private fun readMouse(path: String) {
        try {
            FileInputStream(path).use { fis ->
                synchronized(this) { miceStreams[path] = fis }
                if (grabWanted) applyGrab(fis, true)
                val sz = if (android.os.Process.is64Bit()) 24 else 16
                val buf = ByteArray(sz)
                var dx = 0
                var dy = 0
                while (true) {
                    var n = 0
                    while (n < sz) {
                        val r = fis.read(buf, n, sz - n)
                        if (r < 0) return
                        n += r
                    }
                    val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                    val o = sz - 8
                    val type = bb.getShort(o).toInt()
                    val code = bb.getShort(o + 2).toInt() and 0xFFFF
                    val value = bb.getInt(o + 4)
                    when (type) {
                        2 -> if (code == 0) dx += value else if (code == 1) dy += value
                             else if (code == 8 && mouseOn) send("WHL $value")
                        1 -> if (code in 0x110..0x112 && value != 2 && mouseOn) send("BTN $code $value")
                        0 -> if (code == 0) {
                            if ((dx != 0 || dy != 0) && mouseOn) send("REL $dx $dy")
                            dx = 0; dy = 0
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            synchronized(this) { openedMice.remove(path); miceStreams.remove(path) }
        }
    }

    // ---------------- touch injection ----------------
    private class Inj {
        private val ids = ArrayList<Int>()
        private val pos = HashMap<Int, FloatArray>()
        private var downTime = 0L
        private var target: Any? = null
        private var method: Method? = null
        private var threeArgs = false

        init {
            val candidates = listOf("android.hardware.input.InputManager", "android.hardware.input.InputManagerGlobal")
            for (name in candidates) {
                if (target != null) break
                try {
                    val cls = Class.forName(name)
                    val inst = cls.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)
                    try {
                        method = cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                    } catch (_: NoSuchMethodException) {
                        method = cls.getMethod(
                            "injectInputEvent", InputEvent::class.java,
                            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                        )
                        threeArgs = true
                    }
                    target = inst
                } catch (_: Throwable) {}
            }
            if (target == null) {
                try {
                    val sm = Class.forName("android.os.ServiceManager")
                    val binder = sm.getMethod("getService", String::class.java).invoke(null, "input") as android.os.IBinder
                    val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
                    target = stub.getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
                    method = Class.forName("android.hardware.input.IInputManager")
                        .getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                } catch (_: Throwable) {}
            }
        }

        private fun send(action: Int) {
            val t = target ?: return
            val m = method ?: return
            val n = ids.size
            if (n == 0) return
            val props = Array(n) { MotionEvent.PointerProperties().apply { id = ids[it]; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(n) {
                MotionEvent.PointerCoords().apply {
                    val p = pos[ids[it]]!!
                    x = p[0]; y = p[1]; pressure = 1f; size = 1f
                }
            }
            val ev = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action, n, props, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            try {
                if (threeArgs) m.invoke(t, ev, 0, -1) else m.invoke(t, ev, 0)
            } catch (_: Throwable) {
            } finally { ev.recycle() }
        }

        @Synchronized fun down(id: Int, x: Float, y: Float) {
            if (ids.contains(id)) return
            if (ids.isEmpty()) downTime = SystemClock.uptimeMillis()
            ids.add(id); pos[id] = floatArrayOf(x, y)
            val idx = ids.size - 1
            send(if (ids.size == 1) MotionEvent.ACTION_DOWN
                 else MotionEvent.ACTION_POINTER_DOWN or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        }

        @Synchronized fun move(id: Int, x: Float, y: Float) {
            val p = pos[id] ?: return
            p[0] = x; p[1] = y
            send(MotionEvent.ACTION_MOVE)
        }

        @Synchronized fun up(id: Int) {
            val idx = ids.indexOf(id)
            if (idx < 0) return
            send(if (ids.size == 1) MotionEvent.ACTION_UP
                 else MotionEvent.ACTION_POINTER_UP or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
            ids.removeAt(idx); pos.remove(id)
        }

        @Synchronized fun releaseAll() { ids.toList().forEach { up(it) } }
    }
}
