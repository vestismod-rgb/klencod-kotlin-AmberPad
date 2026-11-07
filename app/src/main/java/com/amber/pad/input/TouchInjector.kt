package com.amber.pad.input

import android.graphics.PointF
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

interface TouchInjector {
    fun down(id: Int, x: Float, y: Float)
    fun move(id: Int, x: Float, y: Float)
    fun up(id: Int)
    fun releaseAll()
}

/** Injects multi-touch events through the system InputManager using Shizuku's shell privileges. */
class ShizukuInjector : TouchInjector {
    private val ids = ArrayList<Int>()
    private val pos = HashMap<Int, PointF>()
    private var downTime = 0L

    private val bridge: Pair<Any, Method>? by lazy {
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions("L")
            val binder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("input"))
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            val im = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
            val iface = Class.forName("android.hardware.input.IInputManager")
            val m = iface.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            im to m
        }.onFailure { Log.e("AmberPad", "Injector init failed", it) }.getOrNull()
    }

    private fun send(action: Int) {
        val b = bridge ?: return
        val n = ids.size
        if (n == 0) return
        val props = Array(n) {
            MotionEvent.PointerProperties().apply { id = ids[it]; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }
        val coords = Array(n) {
            MotionEvent.PointerCoords().apply {
                val p = pos[ids[it]]!!
                x = p.x; y = p.y; pressure = 1f; size = 1f
            }
        }
        val ev = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, n, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try {
            b.second.invoke(b.first, ev, 0)
        } catch (t: Throwable) {
            Log.e("AmberPad", "inject failed", t)
        } finally {
            ev.recycle()
        }
    }

    @Synchronized
    override fun down(id: Int, x: Float, y: Float) {
        if (ids.contains(id)) return
        if (ids.isEmpty()) downTime = SystemClock.uptimeMillis()
        ids.add(id); pos[id] = PointF(x, y)
        val idx = ids.size - 1
        send(
            if (ids.size == 1) MotionEvent.ACTION_DOWN
            else MotionEvent.ACTION_POINTER_DOWN or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        )
    }

    @Synchronized
    override fun move(id: Int, x: Float, y: Float) {
        val p = pos[id] ?: return
        p.set(x, y)
        send(MotionEvent.ACTION_MOVE)
    }

    @Synchronized
    override fun up(id: Int) {
        val idx = ids.indexOf(id)
        if (idx < 0) return
        send(
            if (ids.size == 1) MotionEvent.ACTION_UP
            else MotionEvent.ACTION_POINTER_UP or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        )
        ids.removeAt(idx); pos.remove(id)
    }

    @Synchronized
    override fun releaseAll() {
        ids.toList().forEach { up(it) }
    }
}
