package com.amber.pad.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.amber.pad.data.CType
import com.amber.pad.data.Comp
import com.amber.pad.data.Keymap
import com.amber.pad.data.LookCfg
import kotlin.math.hypot

class KeymapEngine(@Volatile var inj: TouchInjector) {
    @Volatile var keymap: Keymap? = null
    @Volatile var w = 1080f
    @Volatile var h = 2400f
    @Volatile var tapMs = 40L
    @Volatile var sens = 1f                  // global multiplier on top of each look config
    @Volatile var recoilOn = false
    @Volatile var recoilStrength = 60f
    @Volatile var onViewMode: (Boolean) -> Unit = {}
    @Volatile var onToggleBubble: () -> Unit = {}

    var viewMode = false
        private set
    private var switched = false
    private var freeHeld = false
    private var look: Comp? = null
    private var free: Comp? = null

    private val held = HashSet<Int>()
    private val handler = Handler(Looper.getMainLooper())

    /** A virtual finger that follows the mouse inside a circular region. */
    private inner class Cam(val id: Int) {
        var x = 0f
        var y = 0f
        var down = false
        var readyAt = 0L
        private val idle = Runnable { up() }

        fun move(c: Comp, dx: Float, dy: Float) {
            val cfg = c.cfg
            val cx = c.x * w
            val cy = c.y * h
            val now = SystemClock.uptimeMillis()
            if (!down) {
                x = cx; y = cy
                inj.down(id, x, y)
                down = true
                readyAt = now + cfg.pressDelay
            }
            handler.removeCallbacks(idle)
            if (now >= readyAt) {
                x += dx; y += dy
                if (hypot(x - cx, y - cy) > c.r * h) {
                    inj.up(id)
                    x = cx; y = cy
                    inj.down(id, x, y)
                    readyAt = now + cfg.pressDelay
                } else {
                    inj.move(id, x, y)
                }
            }
            if (!cfg.edgeOnly) handler.postDelayed(idle, cfg.idleMs)
        }

        fun up() {
            handler.removeCallbacks(idle)
            if (down) { inj.up(id); down = false }
        }
    }

    private val mainCam = Cam(CAM_ID)
    private val freeCam = Cam(FREE_ID)

    private var fireDown = false
    private val recoilTick = object : Runnable {
        override fun run() {
            val c = look
            if (fireDown && recoilOn && viewMode && c != null) {
                mainCam.move(c, 0f, recoilStrength * h / 2000f * 0.016f)
                handler.postDelayed(this, 16)
            }
        }
    }

    private val running = HashMap<Int, Int>()
    private var seq = 0

    val wantsMouse: Boolean
        get() = keymap?.comps?.any {
            it.type == CType.LOOK || it.type == CType.FREELOOK || it.type == CType.MOUSE_L ||
                it.type == CType.MOUSE_R || it.type == CType.MSCROLL
        } == true

    fun set(km: Keymap?) {
        running.keys.toList().forEach { stopMacro(it) }
        handler.removeCallbacks(recoilTick)
        mainCam.up(); freeCam.up()
        fireDown = false; switched = false; freeHeld = false
        inj.releaseAll()
        held.clear()
        keymap = km
        look = km?.comps?.firstOrNull { it.type == CType.LOOK }
        free = km?.comps?.firstOrNull { it.type == CType.FREELOOK }
        setView(false)
    }

    /** View mode: mouse controls the camera (system pointer grabbed). Otherwise the mouse is a normal pointer. */
    fun setView(on: Boolean) {
        if (viewMode == on) return
        viewMode = on
        if (!on) {
            handler.removeCallbacks(recoilTick)
            mainCam.up(); freeCam.up(); fireDown = false
            keymap?.comps?.forEachIndexed { i, c ->
                if (c.type == CType.MOUSE_L || c.type == CType.MOUSE_R) inj.up(i)
            }
        }
        onViewMode(on)
    }

    // ---------------- keyboard ----------------
    /** Returns true when the key was consumed by the keymap. */
    fun onKey(e: KeyEvent): Boolean {
        val km = keymap ?: return false
        val down = e.action == KeyEvent.ACTION_DOWN
        val first = down && e.repeatCount == 0
        look?.cfg?.let { g ->
            if (g.viewKey != 0 && e.keyCode == g.viewKey) { if (first) setView(!viewMode); return true }
            if (g.resetKey != 0 && e.keyCode == g.resetKey) { if (first) mainCam.up(); return true }
            if (g.switchKey != 0 && e.keyCode == g.switchKey) {
                if (g.switchHold) switched = down else if (first) switched = !switched
                return true
            }
        }
        var handled = false
        km.comps.forEachIndexed { i, c ->
            when (c.type) {
                CType.JOYSTICK -> if (e.keyCode in WASD) {
                    handled = true
                    if (down) held.add(e.keyCode) else held.remove(e.keyCode)
                    joystick(c)
                }
                CType.PRESS -> if (c.key == e.keyCode) {
                    handled = true
                    if (first) inj.down(i, c.x * w, c.y * h) else if (!down) inj.up(i)
                }
                CType.TAP -> if (c.key == e.keyCode) {
                    handled = true
                    if (first) {
                        inj.down(i, c.x * w, c.y * h)
                        handler.postDelayed({ inj.up(i) }, tapMs)
                    }
                }
                CType.MACRO -> if (c.key == e.keyCode) { handled = true; macroKey(i, c, down, e.repeatCount) }
                CType.FREELOOK -> if (c.key == e.keyCode) {
                    handled = true
                    freeHeld = down
                    if (!down) freeCam.up()
                }
                CType.SWIPE -> if (c.key == e.keyCode) { handled = true; if (first) swipe(i, c) }
                CType.BALL -> if (c.key == e.keyCode) { handled = true; if (first) onToggleBubble() }
                CType.LOOK, CType.MOUSE_L, CType.MOUSE_R, CType.MSCROLL -> {}
            }
        }
        return handled
    }

    private fun joystick(c: Comp) {
        var dx = 0f
        var dy = 0f
        if (KeyEvent.KEYCODE_W in held) dy -= 1f
        if (KeyEvent.KEYCODE_S in held) dy += 1f
        if (KeyEvent.KEYCODE_A in held) dx -= 1f
        if (KeyEvent.KEYCODE_D in held) dx += 1f
        if (dx == 0f && dy == 0f) { inj.up(JOY_ID); return }
        val cx = c.x * w
        val cy = c.y * h
        val len = hypot(dx, dy)
        val rad = c.r * h
        inj.down(JOY_ID, cx, cy)
        inj.move(JOY_ID, cx + dx / len * rad, cy + dy / len * rad)
    }

    private fun swipe(i: Int, c: Comp) {
        val id = SWIPE_BASE + i
        val x1 = c.x * w; val y1 = c.y * h
        val x2 = c.x2 * w; val y2 = c.y2 * h
        inj.down(id, x1, y1)
        handler.postDelayed({ inj.move(id, (x1 + x2) / 2, (y1 + y2) / 2) }, 30)
        handler.postDelayed({ inj.move(id, x2, y2) }, 60)
        handler.postDelayed({ inj.up(id) }, 100)
    }

    // ---------------- mouse ----------------
    private fun sensOf(g: LookCfg): Pair<Float, Float> {
        val a = if (switched) g.sensX2 else g.sensX
        val b = if (g.linked) a else if (switched) g.sensY2 else g.sensY
        return (a / 350f) * sens to (b / 350f) * sens
    }

    fun onMouseRel(dx: Int, dy: Int) {
        if (!viewMode) return
        val f = free
        if (freeHeld && f != null) {
            val (sx, sy) = sensOf(f.cfg)
            freeCam.move(f, dx * sx, dy * sy)
        } else {
            val c = look ?: return
            val (sx, sy) = sensOf(c.cfg)
            mainCam.move(c, dx * sx, dy * sy)
        }
    }

    fun onMouseWheel(v: Int) {
        if (!viewMode) return
        val c = keymap?.comps?.firstOrNull { it.type == CType.MSCROLL } ?: return
        val dir = if (v > 0) -1f else 1f
        val x = c.x * w
        val y = c.y * h
        inj.down(SCROLL_ID, x, y)
        handler.postDelayed({ inj.move(SCROLL_ID, x, y + dir * c.r * h) }, 30)
        handler.postDelayed({ inj.up(SCROLL_ID) }, 70)
    }

    fun onMouseButton(code: Int, down: Boolean) {
        if (!viewMode) return
        val km = keymap ?: return
        val type = when (code) {
            0x110 -> CType.MOUSE_L
            0x111 -> CType.MOUSE_R
            else -> return
        }
        val idx = km.comps.indexOfFirst { it.type == type }
        if (idx < 0) return
        val c = km.comps[idx]
        if (down) inj.down(idx, c.x * w, c.y * h) else inj.up(idx)
        if (type == CType.MOUSE_L) {
            fireDown = down
            handler.removeCallbacks(recoilTick)
            if (down && recoilOn) handler.postDelayed(recoilTick, 16)
        }
    }

    // ---------------- macros ----------------
    private fun macroKey(i: Int, c: Comp, down: Boolean, rc: Int) {
        when (c.mode) {
            0 -> if (down && rc == 0) startMacro(i, c, false)
            1 -> if (down && rc == 0) startMacro(i, c, true) else if (!down) stopMacro(i)
            else -> if (down && rc == 0) {
                if (running.containsKey(i)) stopMacro(i) else startMacro(i, c, true)
            }
        }
    }

    private fun startMacro(i: Int, c: Comp, loop: Boolean) {
        if (c.steps.isEmpty()) return
        val t = ++seq
        running[i] = t
        step(i, c, 0, t, loop)
    }

    private fun stopMacro(i: Int) {
        running.remove(i)
        inj.up(MACRO_BASE + i)
    }

    private fun step(i: Int, c: Comp, k: Int, t: Int, loop: Boolean) {
        if (running[i] != t) return
        if (k >= c.steps.size) {
            if (loop) handler.postDelayed({ step(i, c, 0, t, true) }, 30) else running.remove(i)
            return
        }
        val s = c.steps[k]
        handler.postDelayed({
            if (running[i] != t) return@postDelayed
            inj.down(MACRO_BASE + i, s.x * w, s.y * h)
            handler.postDelayed({
                inj.up(MACRO_BASE + i)
                step(i, c, k + 1, t, loop)
            }, s.hold)
        }, s.delay)
    }

    companion object {
        const val JOY_ID = 30
        const val CAM_ID = 31
        const val FREE_ID = 32
        const val MACRO_BASE = 40
        const val SCROLL_ID = 50
        const val SWIPE_BASE = 60
        val WASD = setOf(KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D)
    }
}
