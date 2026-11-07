package com.amber.pad.overlay

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
import android.view.WindowManager.LayoutParams.FLAG_SECURE
import android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
import android.view.WindowManager.LayoutParams.MATCH_PARENT
import android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
import android.view.WindowManager.LayoutParams.WRAP_CONTENT
import android.widget.Chronometer
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.amber.pad.R
import com.amber.pad.data.AppSettings
import com.amber.pad.data.Keymap
import com.amber.pad.data.SettingsRepo
import com.amber.pad.input.KeyService
import kotlinx.coroutines.*
import kotlin.math.abs

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var repo: SettingsRepo
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var st = AppSettings()

    private var bubble: TextView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var editor: EditorView? = null
    private var crosshair: View? = null
    private var timer: View? = null
    private var watcher: View? = null

    private val d get() = resources.displayMetrics.density

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        repo = SettingsRepo(this)
        startAsForeground()
        addBubble()
        scope.launch { repo.flow.collect { st = it; applySettings() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> togglePanel()
            ACTION_STOP -> stopSelf()
            ACTION_WATCH_ON -> addWatcher()
            ACTION_WATCH_OFF -> removeWatcher()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        hidePanel(); removeEditor(); removeWatcher()
        listOf(bubble, crosshair, timer).forEach { v -> v?.let { runCatching { wm.removeView(it) } } }
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val ch = "amberpad"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ch, "AmberPad", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotificationCompat.Builder(this, ch)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(getString(R.string.notif_title))
            .setOngoing(true).build()
        ServiceCompat.startForeground(
            this, 1, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
    }

    private fun params(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h, TYPE_APPLICATION_OVERLAY, flags or FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT
    ).apply { if (st.streamer) this.flags = this.flags or FLAG_SECURE }

    // ---------- bubble ----------
    private fun addBubble() {
        val size = (48 * d).toInt()
        val p = params(size, size, FLAG_NOT_FOCUSABLE).apply {
            gravity = Gravity.TOP or Gravity.START; x = (12 * d).toInt(); y = (80 * d).toInt()
        }
        val v = TextView(this).apply {
            text = "A"; gravity = Gravity.CENTER; textSize = 18f
            setTextColor(0xFF111111.toInt())
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFFC61A.toInt()) }
        }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        v.setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = p.x; oy = p.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt(); val dy = (e.rawY - sy).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    p.x = ox + dx; p.y = oy + dy
                    wm.updateViewLayout(view, p); true
                }
                MotionEvent.ACTION_UP -> { if (!moved) togglePanel(); true }
                else -> false
            }
        }
        wm.addView(v, p)
        bubble = v; bubbleLp = p
    }

    private fun applySettings() {
        val b = bubble ?: return
        b.alpha = st.opacity
        b.visibility = if (st.hideBubble) View.GONE else View.VISIBLE
        bubbleLp?.let { p ->
            val has = p.flags and FLAG_SECURE != 0
            if (has != st.streamer) {
                p.flags = if (st.streamer) p.flags or FLAG_SECURE else p.flags and FLAG_SECURE.inv()
                runCatching { wm.updateViewLayout(b, p) }
            }
        }
    }

    // ---------- typing watcher: tells us when the user clicks somewhere else ----------
    private fun addWatcher() {
        if (watcher != null) return
        val v = View(this)
        v.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) KeyService.instance?.recheckTyping()
            false
        }
        wm.addView(v, params(1, 1, FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL or FLAG_WATCH_OUTSIDE_TOUCH).apply {
            gravity = Gravity.TOP or Gravity.START
        })
        watcher = v
    }

    private fun removeWatcher() {
        watcher?.let { runCatching { wm.removeView(it) } }
        watcher = null
    }

    // ---------- panel (left side in RTL languages, right side otherwise) ----------
    private fun togglePanel() { if (panel != null) hidePanel() else showPanel() }

    private fun hidePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    private fun showPanel() {
        val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val pad = (12 * d).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(0xF2141416.toInt()); cornerRadius = 18 * d; setStroke((1 * d).toInt(), 0xFF3A3A3E.toInt())
            }
        }
        col.addView(TextView(this).apply {
            text = getString(R.string.panel_title); textSize = 16f; setTextColor(0xFFF5F5F7.toInt())
            setPadding(0, 0, 0, pad)
        })

        val tools = ArrayList<Pair<Int, () -> Unit>>()
        fun tool(label: Int, action: () -> Unit) { tools.add(label to action) }
        tool(R.string.tool_edit) { hidePanel(); showEditor(false) }
        tool(R.string.tool_macro) { hidePanel(); showEditor(true) }
        tool(R.string.tool_typing) { hidePanel(); KeyService.instance?.toggleTypingManual() }
        tool(R.string.tool_recoil) { toggleRecoil() }
        tool(R.string.tool_reset) { resetKeymap() }
        tool(R.string.tool_crosshair) { toggleCrosshair() }
        tool(R.string.tool_timer) { toggleTimer() }
        tool(R.string.tool_monitor) { showMonitor() }
        tool(R.string.tool_close) { stopSelf() }
        fun cell(label: Int, action: () -> Unit) = TextView(this).apply {
            text = getString(label); textSize = 13f; setTextColor(0xFFFFC61A.toInt()); gravity = Gravity.CENTER
            setPadding((6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable().apply { setColor(0xFF1E1E21.toInt()); cornerRadius = 12 * d }
            setOnClickListener { action() }
        }
        tools.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, (label, action) ->
                row.addView(cell(label, action), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    topMargin = (5 * d).toInt(); if (i == 0) marginEnd = (5 * d).toInt()
                })
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            col.addView(row)
        }

        val sv = ScrollView(this).apply { addView(col) }
        val maxH = resources.displayMetrics.heightPixels - (24 * d).toInt()
        val p = params((300 * d).toInt(), WRAP_CONTENT, FLAG_NOT_FOCUSABLE).apply {
            gravity = Gravity.CENTER_VERTICAL or (if (rtl) Gravity.LEFT else Gravity.RIGHT)
            x = (8 * d).toInt()
        }
        sv.post { if (sv.height > maxH) { p.height = maxH; runCatching { wm.updateViewLayout(sv, p) } } }
        wm.addView(sv, p)
        panel = sv
    }

    // ---------- tools ----------
    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun resetKeymap() {
        val pkg = KeyService.currentPkg ?: return toast(R.string.no_game)
        scope.launch { repo.resetKeymap(pkg); KeyService.instance?.reload() }
        hidePanel()
    }

    private fun toggleRecoil() {
        val on = !st.recoilEnabled
        scope.launch { repo.setRecoil(on) }
        toast(if (on) R.string.recoil_on else R.string.recoil_off)
        hidePanel()
    }

    private fun toggleCrosshair() {
        hidePanel()
        crosshair?.let { runCatching { wm.removeView(it) }; crosshair = null; return }
        val v = TextView(this).apply {
            text = "+"; textSize = 28f; setTextColor(0xFFFFC61A.toInt()); gravity = Gravity.CENTER
        }
        wm.addView(v, params(WRAP_CONTENT, WRAP_CONTENT, FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE).apply {
            gravity = Gravity.CENTER
        })
        crosshair = v
    }

    private fun toggleTimer() {
        hidePanel()
        timer?.let { runCatching { wm.removeView(it) }; timer = null; return }
        val v = Chronometer(this).apply {
            setTextColor(0xFFFFC61A.toInt()); base = SystemClock.elapsedRealtime(); start()
        }
        wm.addView(v, params(WRAP_CONTENT, WRAP_CONTENT, FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (8 * d).toInt()
        })
        timer = v
    }

    private fun showMonitor() {
        hidePanel()
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val bat = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        Toast.makeText(
            this, "Battery $bat%  ·  RAM ${mi.availMem / 1048576} / ${mi.totalMem / 1048576} MB", Toast.LENGTH_LONG
        ).show()
    }

    // ---------- keymap editor ----------
    private fun showEditor(record: Boolean) {
        val pkg = KeyService.currentPkg ?: return toast(R.string.no_game)
        scope.launch {
            val km = repo.keymap(pkg) ?: Keymap.default()
            editing = true
            val v = EditorView(this@OverlayService, km, record) { result ->
                removeEditor()
                if (result != null) scope.launch { repo.saveKeymap(pkg, result); KeyService.instance?.reload() }
            }
            wm.addView(v, WindowManager.LayoutParams(
                MATCH_PARENT, MATCH_PARENT, TYPE_APPLICATION_OVERLAY,
                FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT
            ))
            v.requestFocus()
            editor = v
            toast(if (record) R.string.editor_macro_hint else R.string.editor_press_key)
        }
    }

    private fun removeEditor() {
        editor?.let { runCatching { wm.removeView(it) } }
        editor = null
        editing = false
    }

    companion object {
        const val ACTION_TOGGLE = "com.amber.pad.TOGGLE"
        const val ACTION_STOP = "com.amber.pad.STOP"
        const val ACTION_WATCH_ON = "com.amber.pad.WATCH_ON"
        const val ACTION_WATCH_OFF = "com.amber.pad.WATCH_OFF"
        @Volatile var editing = false

        private fun send(ctx: Context, action: String) {
            val i = Intent(ctx, OverlayService::class.java).setAction(action)
            runCatching { ContextCompat.startForegroundService(ctx, i) }
        }

        fun toggle(ctx: Context) = send(ctx, ACTION_TOGGLE)
        fun typingWatcher(ctx: Context, on: Boolean) = send(ctx, if (on) ACTION_WATCH_ON else ACTION_WATCH_OFF)
    }
}
