package com.amber.pad.data

import android.content.Context
import android.view.KeyEvent
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom

private val Context.ds by preferencesDataStore("amberpad")

data class AppSettings(
    val opacity: Float = 0.7f,
    val hideBubble: Boolean = false,
    val hotkey: Int = KeyEvent.KEYCODE_F1,        // 0 = disabled
    val streamer: Boolean = false,
    val touchCompat: Boolean = false,
    val mode: String = "shizuku",
    val games: Set<String> = emptySet(),
    val selected: String? = null,
    val recoilEnabled: Boolean = false,
    val recoilStrength: Float = 60f,               // 0..200
    val mouseSens: Float = 1f,                     // 0.3..3
    val typingAuto: Boolean = true,
    val typingHotkey: Int = KeyEvent.KEYCODE_F2,   // 0 = disabled
    val token: String = "",
)

class SettingsRepo(private val ctx: Context) {
    private object K {
        val opacity = floatPreferencesKey("opacity")
        val hide = booleanPreferencesKey("hide")
        val hotkey = intPreferencesKey("hotkey")
        val streamer = booleanPreferencesKey("streamer")
        val touch = booleanPreferencesKey("touch")
        val mode = stringPreferencesKey("mode")
        val games = stringSetPreferencesKey("games")
        val selected = stringPreferencesKey("selected")
        val recoil = booleanPreferencesKey("recoil")
        val recoilStr = floatPreferencesKey("recoil_str")
        val sens = floatPreferencesKey("sens")
        val typingAuto = booleanPreferencesKey("typing_auto")
        val typingKey = intPreferencesKey("typing_key")
        val token = stringPreferencesKey("token")
        fun km(pkg: String) = stringPreferencesKey("km_$pkg")
    }

    val flow: Flow<AppSettings> = ctx.ds.data.map { p ->
        AppSettings(
            p[K.opacity] ?: 0.7f, p[K.hide] ?: false, p[K.hotkey] ?: KeyEvent.KEYCODE_F1,
            p[K.streamer] ?: false, p[K.touch] ?: false, p[K.mode] ?: "shizuku",
            p[K.games] ?: emptySet(), p[K.selected],
            p[K.recoil] ?: false, p[K.recoilStr] ?: 60f, p[K.sens] ?: 1f,
            p[K.typingAuto] ?: true, p[K.typingKey] ?: KeyEvent.KEYCODE_F2, p[K.token] ?: ""
        )
    }

    suspend fun current() = flow.first()
    suspend fun setOpacity(v: Float) { ctx.ds.edit { it[K.opacity] = v } }
    suspend fun setHide(v: Boolean) { ctx.ds.edit { it[K.hide] = v } }
    suspend fun setHotkey(v: Int) { ctx.ds.edit { it[K.hotkey] = v } }
    suspend fun setStreamer(v: Boolean) { ctx.ds.edit { it[K.streamer] = v } }
    suspend fun setTouchCompat(v: Boolean) { ctx.ds.edit { it[K.touch] = v } }
    suspend fun setMode(v: String) { ctx.ds.edit { it[K.mode] = v } }
    suspend fun setGames(v: Set<String>) { ctx.ds.edit { it[K.games] = v } }
    suspend fun setSelected(v: String) { ctx.ds.edit { it[K.selected] = v } }
    suspend fun setRecoil(v: Boolean) { ctx.ds.edit { it[K.recoil] = v } }
    suspend fun setRecoilStrength(v: Float) { ctx.ds.edit { it[K.recoilStr] = v } }
    suspend fun setMouseSens(v: Float) { ctx.ds.edit { it[K.sens] = v } }
    suspend fun setTypingAuto(v: Boolean) { ctx.ds.edit { it[K.typingAuto] = v } }
    suspend fun setTypingHotkey(v: Int) { ctx.ds.edit { it[K.typingKey] = v } }

    /** Secret shared with the privileged server so other apps cannot inject input. */
    suspend fun ensureToken(): String {
        val cur = current().token
        if (cur.isNotEmpty()) return cur
        val b = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val t = b.joinToString("") { "%02x".format(it) }
        ctx.ds.edit { it[K.token] = t }
        return t
    }

    suspend fun resetAll() {
        val t = current().token
        ctx.ds.edit { it.clear(); if (t.isNotEmpty()) it[K.token] = t }
    }

    suspend fun keymap(pkg: String): Keymap? =
        ctx.ds.data.first()[K.km(pkg)]?.let { runCatching { Keymap.parse(it) }.getOrNull() }

    suspend fun saveKeymap(pkg: String, km: Keymap) { ctx.ds.edit { it[K.km(pkg)] = km.toJson() } }
    suspend fun resetKeymap(pkg: String) { ctx.ds.edit { it.remove(K.km(pkg)) } }
}
