package com.amber.pad.input

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.widget.Toast
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.amber.pad.R
import com.amber.pad.data.AppSettings
import com.amber.pad.data.Keymap
import com.amber.pad.data.SettingsRepo
import com.amber.pad.overlay.OverlayService
import kotlinx.coroutines.*

class KeyService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var repo: SettingsRepo
    @Volatile private var st = AppSettings()
    private val shizukuInj = ShizukuInjector()
    private val engine = KeymapEngine(ServerClient)

    @Volatile var typing = false
        private set
    private var typingManual = false

    override fun onServiceConnected() {
        instance = this
        repo = SettingsRepo(this)
        ServerClient.onRel = { dx, dy -> refreshDims(); engine.onMouseRel(dx, dy) }
        ServerClient.onBtn = { code, down -> refreshDims(); engine.onMouseButton(code, down) }
        ServerClient.onWheel = { v -> refreshDims(); engine.onMouseWheel(v) }
        ServerClient.onGrabFail = {
            Toast.makeText(this, R.string.grab_fail, Toast.LENGTH_LONG).show()
            engine.setView(false)
        }
        engine.onViewMode = { on ->
            if (engine.inj === ServerClient) ServerClient.grab(on)
            Toast.makeText(this, if (on) R.string.view_on else R.string.view_off, Toast.LENGTH_SHORT).show()
        }
        engine.onToggleBubble = { scope.launch { repo.setHide(!st.hideBubble) } }
        scope.launch {
            repo.ensureToken()
            repo.flow.collect { applySettings(it) }
        }
    }

    private fun applySettings(s: AppSettings) {
        val modeChanged = s.mode != st.mode
        st = s
        ServerClient.token = s.token
        engine.tapMs = if (s.touchCompat) 80L else 40L
        engine.sens = s.mouseSens
        engine.recoilOn = s.recoilEnabled
        engine.recoilStrength = s.recoilStrength
        engine.inj = if (s.mode == "shizuku_legacy") shizukuInj else ServerClient
        if (modeChanged) reload()
    }

    private fun inGame() = currentPkg?.let { it in st.games } == true

    private fun imeVisible() = try {
        windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    } catch (_: Throwable) { false }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = e.packageName?.toString() ?: return
                if (pkg == packageName || pkg == "com.android.systemui") return
                currentPkg = pkg
                reload()
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                if (st.typingAuto && !typing && inGame() && e.packageName?.toString() == currentPkg &&
                    e.source?.isEditable == true
                ) enterTyping(false)
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (st.typingAuto && !typing && inGame() && imeVisible()) enterTyping(false)
            }
        }
    }

    // ---------------- typing mode ----------------
    /** While typing, keys pass straight through to the game / keyboard. Esc leaves typing mode. */
    fun enterTyping(manual: Boolean) {
        if (typing) return
        typing = true
        typingManual = manual
        engine.set(null)
        if (engine.inj === ServerClient) ServerClient.mouse(false)
        OverlayService.typingWatcher(this, true)
    }

    fun exitTyping() {
        if (!typing) return
        typing = false
        OverlayService.typingWatcher(this, false)
        reload()
    }

    fun toggleTypingManual() { if (typing) exitTyping() else enterTyping(true) }

    /** Called when the user touched/clicked outside our overlay while typing (auto mode only). */
    fun recheckTyping() {
        if (!typing || typingManual) return
        main.postDelayed({
            val editable = try {
                rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isEditable == true
            } catch (_: Throwable) { false }
            if (!editable && !imeVisible()) exitTyping()
        }, 250)
    }

    // ---------------- keymap ----------------
    fun reload() {
        val pkg = currentPkg ?: return
        scope.launch {
            val s = repo.current()
            if (!typing && pkg in s.games) {
                engine.set(repo.keymap(pkg) ?: Keymap.default())
                if (engine.inj === ServerClient) {
                    ServerClient.ensure()
                    ServerClient.mouse(engine.wantsMouse)
                }
            } else {
                engine.set(null)
                if (engine.inj === ServerClient) ServerClient.mouse(false)
            }
        }
    }

    private fun refreshDims() {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        engine.w = dm.widthPixels.toFloat()
        engine.h = dm.heightPixels.toFloat()
    }

    override fun onKeyEvent(ev: KeyEvent): Boolean {
        if (typing) {
            if (ev.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                if (ev.action == KeyEvent.ACTION_DOWN) exitTyping()
                return true
            }
            return false
        }
        val down = ev.action == KeyEvent.ACTION_DOWN
        if (st.typingHotkey != 0 && ev.keyCode == st.typingHotkey) {
            if (down && ev.repeatCount == 0) enterTyping(true)
            return true
        }
        if (st.hotkey != 0 && ev.keyCode == st.hotkey) {
            if (down && ev.repeatCount == 0) OverlayService.toggle(this)
            return true
        }
        if (OverlayService.editing) return false
        refreshDims()
        return engine.onKey(ev)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        engine.set(null)
        scope.cancel()
        return super.onUnbind(intent)
    }

    companion object {
        @Volatile var instance: KeyService? = null
        @Volatile var currentPkg: String? = null
    }
}
