package com.amber.pad.data

import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

enum class CType { TAP, PRESS, JOYSTICK, LOOK, FREELOOK, MOUSE_L, MOUSE_R, MACRO, SWIPE, MSCROLL, BALL }

/** One recorded macro step: wait [delay] ms, then tap (x, y) for [hold] ms. */
data class MStep(val delay: Long, val x: Float, val y: Float, val hold: Long = 40)

/** Mouse-look settings (for LOOK and FREELOOK components). Sensitivity 350 = 1 px per mouse count. */
data class LookCfg(
    val viewKey: Int = KeyEvent.KEYCODE_M,   // toggles mouse pointer <-> view (camera) mode
    val resetKey: Int = 0,                   // releases the camera finger
    val sensX: Float = 350f,
    val sensY: Float = 350f,
    val linked: Boolean = true,              // vertical follows horizontal
    val switchKey: Int = 0,                  // alternate sensitivity
    val switchHold: Boolean = false,         // false = click to toggle, true = while held
    val sensX2: Float = 300f,
    val sensY2: Float = 300f,
    val edgeOnly: Boolean = true,            // keep the finger down until it reaches the edge
    val idleMs: Long = 700L,                 // otherwise release after this idle time
    val pressDelay: Long = 15L,
)

/**
 * x, y and r are fractions of the screen (r is a fraction of screen height).
 * MACRO mode: 0 = once, 1 = loop while held, 2 = toggle. SWIPE uses (x2, y2) as its end point.
 */
data class Comp(
    val type: CType,
    val key: Int,
    val x: Float,
    val y: Float,
    val r: Float = 0.12f,
    val steps: List<MStep> = emptyList(),
    val mode: Int = 0,
    val x2: Float = 0f,
    val y2: Float = 0f,
    val cfg: LookCfg = LookCfg(),
)

data class Keymap(val comps: List<Comp>) {
    fun toJson(): String {
        val a = JSONArray()
        comps.forEach { c ->
            val o = JSONObject().put("t", c.type.name).put("k", c.key)
                .put("x", c.x.toDouble()).put("y", c.y.toDouble()).put("r", c.r.toDouble()).put("m", c.mode)
                .put("x2", c.x2.toDouble()).put("y2", c.y2.toDouble())
            if (c.steps.isNotEmpty()) {
                val s = JSONArray()
                c.steps.forEach { st -> s.put(JSONArray().put(st.delay).put(st.x.toDouble()).put(st.y.toDouble()).put(st.hold)) }
                o.put("s", s)
            }
            if (c.type == CType.LOOK || c.type == CType.FREELOOK) {
                val g = c.cfg
                o.put("cfg", JSONObject().put("vk", g.viewKey).put("rk", g.resetKey)
                    .put("sx", g.sensX.toDouble()).put("sy", g.sensY.toDouble()).put("ln", g.linked)
                    .put("sk", g.switchKey).put("sh", g.switchHold)
                    .put("sx2", g.sensX2.toDouble()).put("sy2", g.sensY2.toDouble())
                    .put("eo", g.edgeOnly).put("im", g.idleMs).put("pd", g.pressDelay))
            }
            a.put(o)
        }
        return a.toString()
    }

    companion object {
        fun parse(s: String): Keymap {
            val a = JSONArray(s)
            return Keymap((0 until a.length()).map {
                val o = a.getJSONObject(it)
                val steps = o.optJSONArray("s")?.let { arr ->
                    (0 until arr.length()).map { j ->
                        val st = arr.getJSONArray(j)
                        MStep(st.getLong(0), st.getDouble(1).toFloat(), st.getDouble(2).toFloat(), st.getLong(3))
                    }
                } ?: emptyList()
                val cfg = o.optJSONObject("cfg")?.let { g ->
                    val d = LookCfg()
                    LookCfg(
                        g.optInt("vk", d.viewKey), g.optInt("rk", d.resetKey),
                        g.optDouble("sx", d.sensX.toDouble()).toFloat(), g.optDouble("sy", d.sensY.toDouble()).toFloat(),
                        g.optBoolean("ln", d.linked), g.optInt("sk", d.switchKey), g.optBoolean("sh", d.switchHold),
                        g.optDouble("sx2", d.sensX2.toDouble()).toFloat(), g.optDouble("sy2", d.sensY2.toDouble()).toFloat(),
                        g.optBoolean("eo", d.edgeOnly), g.optLong("im", d.idleMs), g.optLong("pd", d.pressDelay)
                    )
                } ?: LookCfg()
                Comp(
                    CType.valueOf(o.getString("t")), o.getInt("k"),
                    o.getDouble("x").toFloat(), o.getDouble("y").toFloat(), o.getDouble("r").toFloat(),
                    steps, o.optInt("m", 0),
                    o.optDouble("x2", 0.0).toFloat(), o.optDouble("y2", 0.0).toFloat(), cfg
                )
            })
        }

        fun default() = Keymap(
            listOf(
                Comp(CType.JOYSTICK, 0, 0.16f, 0.68f, 0.12f),
                Comp(CType.LOOK, 0, 0.72f, 0.42f, 0.22f),
                Comp(CType.MOUSE_L, 0, 0.88f, 0.64f),
                Comp(CType.MOUSE_R, 0, 0.78f, 0.46f),
                Comp(CType.PRESS, KeyEvent.KEYCODE_SPACE, 0.86f, 0.80f),
                Comp(CType.PRESS, KeyEvent.KEYCODE_SHIFT_LEFT, 0.78f, 0.90f),
                Comp(CType.TAP, KeyEvent.KEYCODE_E, 0.66f, 0.56f)
            )
        )
    }
}
