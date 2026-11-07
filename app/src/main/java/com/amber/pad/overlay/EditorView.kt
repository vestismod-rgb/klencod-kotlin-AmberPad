package com.amber.pad.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.amber.pad.R
import com.amber.pad.data.CType
import com.amber.pad.data.Comp
import com.amber.pad.data.Keymap
import com.amber.pad.data.LookCfg
import com.amber.pad.data.MStep
import kotlin.math.hypot

/**
 * Full-screen keymap editor drawn over the game.
 * "Add component" opens a menu; tapping a component opens its configuration panel.
 */
class EditorView(
    ctx: Context,
    start: Keymap,
    startRecording: Boolean,
    private val onDone: (Keymap?) -> Unit,
) : FrameLayout(ctx) {
    private val comps = start.comps.toMutableList()
    private var pending = -1          // component waiting for a key press
    private var awaitEnd = -1         // swipe waiting for its end point
    private var drag = -1
    private var recording = false
    private val recSteps = mutableListOf<MStep>()
    private var lastTap = 0L
    private var capturing: ((Int) -> Unit)? = null
    private var panel: View? = null

    private val d = resources.displayMetrics.density
    private val hit = 28f * d

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = 0xFFFFC61A.toInt()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x55FFC61A }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFFFFC61A.toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d; color = 0xAAFFC61A.toInt()
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 11f * d; textAlign = Paint.Align.CENTER
    }
    private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF111111.toInt(); textSize = 11f * d; textAlign = Paint.Align.CENTER
    }

    private fun str(id: Int) = context.getString(id)
    private fun toast(id: Int) = Toast.makeText(context, id, Toast.LENGTH_SHORT).show()
    private fun keyName(k: Int) = if (k == 0) "—" else KeyEvent.keyCodeToString(k).removePrefix("KEYCODE_")

    private fun label(c: Comp) = when (c.type) {
        CType.JOYSTICK -> "WASD"
        CType.LOOK -> "VIEW"
        CType.FREELOOK -> "FREE·" + keyName(c.key)
        CType.MOUSE_L -> "Mouse-L"
        CType.MOUSE_R -> "Mouse-R"
        CType.MACRO -> "M·" + keyName(c.key) + "·" + listOf("x1", "hold", "tgl")[c.mode % 3] + "·" + c.steps.size
        CType.TAP -> keyName(c.key) + " •"
        CType.PRESS -> keyName(c.key)
        CType.SWIPE -> "SWIPE·" + keyName(c.key)
        CType.MSCROLL -> "SCROLL"
        CType.BALL -> "BALL·" + keyName(c.key)
    }

    private fun bigRadius(c: Comp) =
        c.type == CType.JOYSTICK || c.type == CType.LOOK || c.type == CType.FREELOOK || c.type == CType.MSCROLL

    private fun radiusOf(c: Comp, h: Int) = if (bigRadius(c)) c.r * h else hit

    private fun bindable(c: Comp) = when (c.type) {
        CType.PRESS, CType.TAP, CType.MACRO, CType.FREELOOK, CType.SWIPE, CType.BALL -> true
        else -> false
    }

    // ---------------- canvas ----------------
    private val canvasView = object : View(ctx) {
        override fun onDraw(c: Canvas) {
            comps.forEachIndexed { i, k ->
                val cx = k.x * width
                val cy = k.y * height
                val r = radiusOf(k, height)
                if (k.type == CType.SWIPE) {
                    val ex = k.x2 * width
                    val ey = k.y2 * height
                    c.drawLine(cx, cy, ex, ey, line)
                    c.drawCircle(ex, ey, 8f * d, dot)
                }
                c.drawCircle(cx, cy, r, stroke)
                if (i == pending || i == awaitEnd) c.drawCircle(cx, cy, r, fill)
                c.drawText(label(k), cx, cy + txt.textSize / 3, txt)
            }
            recSteps.forEachIndexed { i, s ->
                val cx = s.x * width
                val cy = s.y * height
                c.drawCircle(cx, cy, 12f * d, dot)
                c.drawText("${i + 1}", cx, cy + dark.textSize / 3, dark)
            }
        }
    }

    private fun idxAt(x: Float, y: Float): Int {
        for (i in comps.indices.reversed()) {
            val k = comps[i]
            if (hypot(x - k.x * canvasView.width, y - k.y * canvasView.height) <= radiusOf(k, canvasView.height)) return i
        }
        return -1
    }

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            drag = if (recording || awaitEnd >= 0) -1 else idxAt(e.x, e.y); return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val nx = (e.x / canvasView.width).coerceIn(0f, 1f)
            val ny = (e.y / canvasView.height).coerceIn(0f, 1f)
            if (recording) {
                val now = SystemClock.uptimeMillis()
                val delay = if (recSteps.isEmpty()) 0L else (now - lastTap).coerceAtMost(5000L)
                lastTap = now
                recSteps.add(MStep(delay, nx, ny))
                canvasView.invalidate()
                return true
            }
            if (awaitEnd in comps.indices) {
                comps[awaitEnd] = comps[awaitEnd].copy(x2 = nx, y2 = ny)
                pending = awaitEnd; awaitEnd = -1
                toast(R.string.editor_press_key)
                canvasView.invalidate()
                return true
            }
            val i = idxAt(e.x, e.y)
            if (i >= 0) showConfig(i)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (recording || awaitEnd >= 0) return
            val i = idxAt(e.x, e.y)
            if (i >= 0) { comps.removeAt(i); pending = -1; drag = -1; canvasView.invalidate() }
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (drag in comps.indices) {
                comps[drag] = comps[drag].copy(
                    x = (e2.x / canvasView.width).coerceIn(0f, 1f),
                    y = (e2.y / canvasView.height).coerceIn(0f, 1f)
                )
                canvasView.invalidate()
            }
            return true
        }
    })

    // ---------------- small view helpers ----------------
    private fun tv(text: String, size: Float = 14f, color: Int = 0xFFF5F5F7.toInt()) = TextView(context).apply {
        this.text = text; textSize = size; setTextColor(color)
    }

    private fun btn(text: String, filled: Boolean = true, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(if (filled) 0xFF111111.toInt() else 0xFFFFC61A.toInt())
        setPadding((14 * d).toInt(), (7 * d).toInt(), (14 * d).toInt(), (7 * d).toInt())
        background = GradientDrawable().apply {
            cornerRadius = 24 * d
            if (filled) setColor(0xFFFFC61A.toInt()) else { setColor(0x00000000); setStroke((1 * d).toInt(), 0xFFFFC61A.toInt()) }
        }
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View, gap: Float = 5f) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        views.forEach {
            addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginStart = (gap * d).toInt(); marginEnd = (gap * d).toInt(); topMargin = (3 * d).toInt()
            })
        }
    }

    // ---------------- recording ----------------
    private fun toggleRecording() {
        if (!recording) {
            recording = true; recSteps.clear(); lastTap = 0L; pending = -1
            toast(R.string.editor_macro_hint)
            macroBtn.text = str(R.string.editor_macro_stop)
        } else {
            recording = false
            macroBtn.text = str(R.string.comp_macro)
            if (recSteps.isNotEmpty()) {
                val f = recSteps.first()
                comps.add(Comp(CType.MACRO, 0, f.x, f.y, steps = recSteps.toList(), mode = 0))
                pending = comps.lastIndex
                toast(R.string.editor_press_key)
                recSteps.clear()
            }
        }
        canvasView.invalidate()
    }

    private lateinit var macroBtn: TextView

    // ---------------- add component menu ----------------
    private fun closePanel() { panel?.let { removeView(it) }; panel = null }

    private fun addOnce(type: CType, x: Float, y: Float, r: Float) {
        if (comps.none { it.type == type }) comps.add(Comp(type, 0, x, y, r))
        canvasView.invalidate()
    }

    private fun addBindable(c: Comp) {
        comps.add(c); pending = comps.lastIndex
        toast(R.string.editor_press_key)
        canvasView.invalidate()
    }

    private fun showAddMenu() {
        closePanel()
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
            background = GradientDrawable().apply {
                setColor(0xF2141416.toInt()); cornerRadius = 18 * d; setStroke((2 * d).toInt(), 0xFFFFC61A.toInt())
            }
        }
        box.addView(tv(str(R.string.add_component), 20f).apply { gravity = Gravity.CENTER })
        box.addView(tv(str(R.string.add_sub), 13f, 0xFFA8A8AD.toInt()).apply { gravity = Gravity.CENTER })

        val grid = GridLayout(context).apply { columnCount = 4 }
        fun tile(glyph: String, name: Int, action: () -> Unit) {
            val t = TextView(context).apply {
                text = "$glyph\n${str(name)}"
                textSize = 12f; gravity = Gravity.CENTER; setTextColor(0xFFF5F5F7.toInt())
                setPadding((6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt())
                background = GradientDrawable().apply {
                    setColor(0xFF1E1E21.toInt()); cornerRadius = 12 * d; setStroke((1 * d).toInt(), 0xFF3A3A3E.toInt())
                }
                setOnClickListener { closePanel(); action() }
            }
            grid.addView(t, GridLayout.LayoutParams().apply {
                width = (96 * d).toInt(); height = (76 * d).toInt()
                setMargins((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
            })
        }
        tile("↻", R.string.comp_macro) { if (!recording) toggleRecording() }
        tile("⇅", R.string.comp_scroll) { addOnce(CType.MSCROLL, 0.5f, 0.5f, 0.15f) }
        tile("↗", R.string.comp_swipe) {
            comps.add(Comp(CType.SWIPE, 0, 0.5f, 0.5f)); awaitEnd = comps.lastIndex
            toast(R.string.swipe_end_hint); canvasView.invalidate()
        }
        tile("●", R.string.comp_touch) { addBindable(Comp(CType.PRESS, 0, 0.5f, 0.5f)) }
        tile("⌖", R.string.comp_pick) { toast(R.string.soon) }
        tile("◎", R.string.comp_free) { addBindable(Comp(CType.FREELOOK, 0, 0.5f, 0.4f, 0.2f)) }
        tile("✛", R.string.comp_fps) { addOnce(CType.LOOK, 0.72f, 0.42f, 0.22f) }
        tile("✥", R.string.comp_joystick) { addOnce(CType.JOYSTICK, 0.16f, 0.68f, 0.12f) }
        tile("○", R.string.comp_ball) { addBindable(Comp(CType.BALL, 0, 0.5f, 0.1f)) }
        tile("⏱", R.string.comp_group) { toast(R.string.soon) }
        box.addView(ScrollView(context).apply { addView(grid) },
            LinearLayout.LayoutParams(WRAP_CONTENT, (190 * d).toInt()).apply { topMargin = (6 * d).toInt() })
        box.addView(btn(str(R.string.cfg_close), false) { closePanel() }.also {
            (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = (6 * d).toInt()
        })

        addView(box, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        panel = box
    }

    // ---------------- component configuration panel ----------------
    private fun seek(label: Int, min: Int, max: Int, value: Int, enabled: Boolean = true, onChange: (Int) -> Unit): View {
        val caption = tv("${str(label)}: $value", 13f)
        val bar = SeekBar(context).apply {
            this.max = max - min; progress = (value - min).coerceIn(0, max - min); isEnabled = enabled
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) { caption.text = "${str(label)}: ${p + min}"; onChange(p + min) }
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            alpha = if (enabled) 1f else 0.4f
            setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
            addView(caption); addView(bar)
        }
    }

    private fun switchRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
            addView(tv(str(label), 14f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(Switch(context).apply { isChecked = checked; setOnCheckedChangeListener { _, v -> onChange(v) } })
        }

    private fun segmented(options: List<Int>, selected: Int, onPick: (Int) -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            options.forEachIndexed { i, res ->
                val on = i == selected
                addView(TextView(context).apply {
                    text = str(res); textSize = 12f; gravity = Gravity.CENTER
                    setTextColor(if (on) 0xFF111111.toInt() else 0xFFF5F5F7.toInt())
                    setPadding((10 * d).toInt(), (7 * d).toInt(), (10 * d).toInt(), (7 * d).toInt())
                    background = GradientDrawable().apply {
                        setColor(if (on) 0xFFFFC61A.toInt() else 0xFF1E1E21.toInt())
                        setStroke((1 * d).toInt(), 0xFF3A3A3E.toInt()); cornerRadius = 8 * d
                    }
                    setOnClickListener { onPick(i) }
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
        }

    private fun keyRow(i: Int, label: Int, key: Int, clearable: Boolean, onSet: (Int) -> Unit): View {
        val r = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
        }
        r.addView(tv("${str(label)}: ${keyName(key)}", 14f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        r.addView(btn(str(R.string.cfg_modify)) {
            capturing = { code -> onSet(code); showConfig(i) }
            toast(R.string.editor_press_key)
        })
        if (clearable) r.addView(btn(str(R.string.cfg_clear), false) { onSet(0); showConfig(i) }, LinearLayout.LayoutParams(
            WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = (6 * d).toInt() })
        return r
    }

    private fun upd(i: Int, f: (LookCfg) -> LookCfg) { comps[i] = comps[i].copy(cfg = f(comps[i].cfg)) }

    private fun showConfig(i: Int) {
        if (i !in comps.indices) return
        closePanel()
        val c = comps[i]
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
        }
        col.addView(tv(str(R.string.cfg_title) + " · " + label(c), 18f).apply { gravity = Gravity.CENTER })

        // key binding + type
        when (c.type) {
            CType.TAP, CType.PRESS, CType.MOUSE_L, CType.MOUSE_R -> {
                if (c.type == CType.TAP || c.type == CType.PRESS) {
                    col.addView(keyRow(i, R.string.cfg_key, c.key, false) { comps[i] = comps[i].copy(key = it) })
                    col.addView(segmented(listOf(R.string.cfg_type_press, R.string.cfg_type_tap), if (c.type == CType.PRESS) 0 else 1) {
                        comps[i] = comps[i].copy(type = if (it == 0) CType.PRESS else CType.TAP); showConfig(i)
                    })
                }
                col.addView(segmented(listOf(R.string.cfg_mouse_l, R.string.cfg_mouse_r, R.string.cfg_keyboard),
                    when (c.type) { CType.MOUSE_L -> 0; CType.MOUSE_R -> 1; else -> 2 }) {
                    comps[i] = comps[i].copy(type = when (it) { 0 -> CType.MOUSE_L; 1 -> CType.MOUSE_R; else -> CType.PRESS })
                    showConfig(i)
                })
            }
            CType.MACRO -> {
                col.addView(keyRow(i, R.string.cfg_key, c.key, false) { comps[i] = comps[i].copy(key = it) })
                col.addView(segmented(listOf(R.string.cfg_macro_once, R.string.cfg_macro_hold, R.string.cfg_macro_toggle), c.mode) {
                    comps[i] = comps[i].copy(mode = it); showConfig(i)
                })
            }
            CType.SWIPE -> {
                col.addView(keyRow(i, R.string.cfg_key, c.key, false) { comps[i] = comps[i].copy(key = it) })
                col.addView(btn(str(R.string.cfg_swipe_end), false) { closePanel(); awaitEnd = i; toast(R.string.swipe_end_hint) })
            }
            CType.FREELOOK, CType.BALL ->
                col.addView(keyRow(i, R.string.cfg_key, c.key, false) { comps[i] = comps[i].copy(key = it) })
            else -> {}
        }

        // region size
        if (bigRadius(c)) {
            col.addView(seek(R.string.cfg_radius, 5, 45, (c.r * 100).toInt()) { comps[i] = comps[i].copy(r = it / 100f); canvasView.invalidate() })
        }

        // mouse look settings
        if (c.type == CType.LOOK || c.type == CType.FREELOOK) {
            val g = c.cfg
            if (c.type == CType.LOOK) {
                col.addView(keyRow(i, R.string.cfg_viewkey, g.viewKey, true) { k -> upd(i) { it.copy(viewKey = k) } })
                col.addView(keyRow(i, R.string.cfg_resetkey, g.resetKey, true) { k -> upd(i) { it.copy(resetKey = k) } })
            }
            col.addView(switchRow(R.string.cfg_link, g.linked) { v -> upd(i) { it.copy(linked = v) }; showConfig(i) })
            col.addView(seek(R.string.cfg_sens_x, 50, 1000, g.sensX.toInt()) { v -> upd(i) { it.copy(sensX = v.toFloat()) } })
            col.addView(seek(R.string.cfg_sens_y, 50, 1000, g.sensY.toInt(), enabled = !g.linked) { v -> upd(i) { it.copy(sensY = v.toFloat()) } })
            if (c.type == CType.LOOK) {
                col.addView(keyRow(i, R.string.cfg_switchkey, g.switchKey, true) { k -> upd(i) { it.copy(switchKey = k) } })
                col.addView(segmented(listOf(R.string.cfg_switch_click, R.string.cfg_switch_hold), if (g.switchHold) 1 else 0) {
                    upd(i) { c0 -> c0.copy(switchHold = it == 1) }; showConfig(i)
                })
                col.addView(seek(R.string.cfg_sens_x2, 50, 1000, g.sensX2.toInt()) { v -> upd(i) { it.copy(sensX2 = v.toFloat()) } })
                col.addView(seek(R.string.cfg_sens_y2, 50, 1000, g.sensY2.toInt(), enabled = !g.linked) { v -> upd(i) { it.copy(sensY2 = v.toFloat()) } })
            }
            col.addView(switchRow(R.string.cfg_edge, g.edgeOnly) { v -> upd(i) { it.copy(edgeOnly = v) }; showConfig(i) })
            col.addView(seek(R.string.cfg_idle, 100, 2000, g.idleMs.toInt(), enabled = !g.edgeOnly) { v -> upd(i) { it.copy(idleMs = v.toLong()) } })
            col.addView(seek(R.string.cfg_pressdelay, 0, 100, g.pressDelay.toInt()) { v -> upd(i) { it.copy(pressDelay = v.toLong()) } })
        }

        col.addView(row(
            btn(str(R.string.cfg_delete), false) { comps.removeAt(i); pending = -1; closePanel(); canvasView.invalidate() },
            btn(str(R.string.cfg_close)) { closePanel(); canvasView.invalidate() },
        ))

        val wrap = ScrollView(context).apply {
            addView(col)
            background = GradientDrawable().apply {
                setColor(0xF2141416.toInt()); cornerRadius = 18 * d; setStroke((2 * d).toInt(), 0xFFFFC61A.toInt())
            }
        }
        val w = (resources.displayMetrics.widthPixels * 0.55f).toInt()
        addView(wrap, LayoutParams(w, MATCH_PARENT, Gravity.START).apply {
            setMargins((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
        })
        panel = wrap
    }

    // ---------------- import / share ----------------
    private fun clipboard() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun share() {
        clipboard().setPrimaryClip(ClipData.newPlainText("keymap", Keymap(comps.toList()).toJson()))
        toast(R.string.copied)
    }

    private fun import() {
        val t = clipboard().primaryClip?.getItemAt(0)?.text?.toString()
        val km = t?.let { runCatching { Keymap.parse(it) }.getOrNull() }
        if (km == null) { toast(R.string.bad_clipboard); return }
        comps.clear(); comps.addAll(km.comps); pending = -1; canvasView.invalidate()
        toast(R.string.imported)
    }

    // ---------------- init ----------------
    init {
        setBackgroundColor(0xCC000000.toInt())
        isFocusable = true
        isFocusableInTouchMode = true
        canvasView.setOnTouchListener { _, e -> gd.onTouchEvent(e) }
        addView(canvasView, LayoutParams(MATCH_PARENT, MATCH_PARENT))

        macroBtn = TextView(ctx)   // placeholder text holder; real label is set in toggleRecording
        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        bar.addView(row(
            btn(str(R.string.editor_save)) { onDone(Keymap(comps.toList())) },
            btn(str(R.string.add_component)) { showAddMenu() },
            btn(str(R.string.editor_exit)) { onDone(null) },
        ))
        bar.addView(row(
            btn(str(R.string.editor_import), false) { import() },
            btn(str(R.string.editor_share), false) { share() },
            btn(str(R.string.editor_macro_stop), false) { if (recording) toggleRecording() },
        ))
        addView(bar, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = (8 * d).toInt()
        })

        addView(tv(str(R.string.editor_hint), 12f, 0xFFA8A8AD.toInt()),
            LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = (10 * d).toInt()
            })
        if (startRecording) post { toggleRecording() }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN) {
            capturing?.let { cb ->
                capturing = null
                if (e.keyCode != KeyEvent.KEYCODE_BACK) cb(e.keyCode)
                return true
            }
            if (e.keyCode == KeyEvent.KEYCODE_BACK) {
                if (panel != null) { closePanel(); return true }
                onDone(null); return true
            }
            if (pending in comps.indices && bindable(comps[pending])) {
                comps[pending] = comps[pending].copy(key = e.keyCode)
                pending = -1
                canvasView.invalidate()
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }
}
