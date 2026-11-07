package com.amber.pad.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amber.pad.BuildConfig
import com.amber.pad.R
import com.amber.pad.data.AppSettings
import com.amber.pad.data.SettingsRepo
import com.amber.pad.input.Activation
import com.amber.pad.input.KeyService
import com.amber.pad.input.ServerClient
import com.amber.pad.overlay.OverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/** 0 = Shizuku not running, 1 = running without permission, 2 = ready. */
@Composable
fun shizukuState(): State<Int> = produceState(0) {
    while (true) {
        value = try {
            when {
                !Shizuku.pingBinder() -> 0
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> 2
                else -> 1
            }
        } catch (_: Throwable) {
            0
        }
        delay(1000)
    }
}

@Composable
fun serverState(): State<Boolean> = produceState(false) {
    while (true) {
        value = withContext(Dispatchers.IO) { ServerClient.probe() }
        delay(2000)
    }
}

private fun startGame(ctx: Context, pkg: String?) {
    if (!Settings.canDrawOverlays(ctx)) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return
    }
    if (KeyService.instance == null) {
        Toast.makeText(ctx, R.string.perm_accessibility, Toast.LENGTH_LONG).show()
        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return
    }
    ContextCompat.startForegroundService(ctx, Intent(ctx, OverlayService::class.java))
    pkg?.let { p -> ctx.packageManager.getLaunchIntentForPackage(p)?.let { ctx.startActivity(it) } }
}

private fun appLabel(ctx: Context, pkg: String): String = runCatching {
    val pm = ctx.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

// ------------------------------------------------------------ Games
@Composable
fun GamesScreen(repo: SettingsRepo, st: AppSettings, goActivate: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sh by shizukuState()
    val up by serverState()
    val active = up || (st.mode == "shizuku_legacy" && sh == 2)
    var adding by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            onClick = goActivate,
            shape = RoundedCornerShape(14.dp),
            color = if (active) cs.surfaceVariant else cs.secondaryContainer,
            border = BorderStroke(1.dp, if (active) cs.outline else cs.primary),
        ) {
            Text(
                stringResource(if (active) R.string.status_active else R.string.status_inactive),
                color = if (active) cs.onSurface else cs.primary,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }

        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(st.games.toList()) { pkg ->
                val selected = (st.selected ?: st.games.firstOrNull()) == pkg
                val icon = remember(pkg) {
                    runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(128, 128).asImageBitmap() }.getOrNull()
                }
                val shape = RoundedCornerShape(14.dp)
                Column(
                    Modifier.width(110.dp).fillMaxHeight().clip(shape).background(cs.surfaceVariant)
                        .border(if (selected) 2.dp else 1.dp, if (selected) cs.primary else cs.outline, shape)
                        .clickable { scope.launch { repo.setSelected(pkg) } }
                        .padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (icon != null) Image(icon, null, Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)))
                    Spacer(Modifier.height(8.dp))
                    Text(appLabel(ctx, pkg), fontSize = 13.sp, maxLines = 2, textAlign = TextAlign.Center)
                }
            }
            item {
                val shape = RoundedCornerShape(14.dp)
                Column(
                    Modifier.width(110.dp).fillMaxHeight().clip(shape)
                        .border(1.5.dp, cs.primary, shape).clickable { adding = true },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Add, null, tint = cs.primary, modifier = Modifier.size(32.dp))
                    Text(stringResource(R.string.add_game), color = cs.primary, fontSize = 13.sp)
                }
            }
        }

        Button(
            onClick = { startGame(ctx, st.selected ?: st.games.firstOrNull()) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            Icon(Icons.Default.PlayArrow, null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.start_game), fontWeight = FontWeight.Medium)
        }
    }

    if (adding) AddGameDialog(ctx, st.games, onApply = { scope.launch { repo.setGames(it) }; adding = false }) { adding = false }
}

@Composable
fun AddGameDialog(ctx: Context, chosen: Set<String>, onApply: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    val pm = ctx.packageManager
    val apps = remember {
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != ctx.packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }
    var sel by remember { mutableStateOf(chosen) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_game)) },
        confirmButton = { TextButton(onClick = { onApply(sel) }) { Text(stringResource(R.string.apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        text = {
            LazyColumn(Modifier.heightIn(max = 280.dp)) {
                items(apps) { (pkg, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { sel = if (pkg in sel) sel - pkg else sel + pkg }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = pkg in sel, onCheckedChange = null)
                        Spacer(Modifier.width(10.dp))
                        Text(label)
                    }
                }
            }
        }
    )
}

// ------------------------------------------------------------ Settings
@Composable
private fun Section(text: String) =
    Text(text, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 10.dp))

@Composable
private fun RowItem(label: String, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        content()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, fmt: (Float) -> String,
                      enabled: Boolean = true, onDone: (Float) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value) }
    RowItem(label) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(value = v, onValueChange = { v = it }, onValueChangeFinished = { onDone(v) },
                valueRange = range, enabled = enabled, modifier = Modifier.width(160.dp))
            Text(fmt(v), fontSize = 13.sp, modifier = Modifier.width(44.dp), textAlign = TextAlign.End)
        }
    }
}

private fun keyName(code: Int) =
    if (code == 0) "—" else KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")

@Composable
fun SettingsScreen(repo: SettingsRepo, st: AppSettings) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val overlayOk = remember(tick) { Settings.canDrawOverlays(ctx) }
    val a11yOk = remember(tick) { KeyService.instance != null }
    var capturing by remember { mutableIntStateOf(0) }   // 1 = bubble hotkey, 2 = typing hotkey

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.nav_settings), fontSize = 20.sp, fontWeight = FontWeight.Medium)

        Section(stringResource(R.string.bubble_section))
        SliderRow(stringResource(R.string.bubble_opacity), st.opacity, 0.2f..1f, { "${(it * 100).toInt()}%" }) {
            scope.launch { repo.setOpacity(it) }
        }
        RowItem(stringResource(R.string.bubble_hide)) {
            Switch(checked = st.hideBubble, onCheckedChange = { scope.launch { repo.setHide(it) } })
        }
        RowItem(stringResource(R.string.bubble_hotkey)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { capturing = 1 }) { Text(keyName(st.hotkey)) }
                TextButton(onClick = { scope.launch { repo.setHotkey(0) } }) { Text(stringResource(R.string.hotkey_none)) }
            }
        }

        Section(stringResource(R.string.mouse_section))
        SliderRow(stringResource(R.string.mouse_sens), st.mouseSens, 0.3f..3f, { "%.1f".format(it) }) {
            scope.launch { repo.setMouseSens(it) }
        }
        RowItem(stringResource(R.string.recoil_enable)) {
            Switch(checked = st.recoilEnabled, onCheckedChange = { scope.launch { repo.setRecoil(it) } })
        }
        SliderRow(stringResource(R.string.recoil_strength), st.recoilStrength, 0f..200f, { it.toInt().toString() },
            enabled = st.recoilEnabled) { scope.launch { repo.setRecoilStrength(it) } }

        Section(stringResource(R.string.typing_section))
        RowItem(stringResource(R.string.typing_auto)) {
            Switch(checked = st.typingAuto, onCheckedChange = { scope.launch { repo.setTypingAuto(it) } })
        }
        RowItem(stringResource(R.string.typing_hotkey)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { capturing = 2 }) { Text(keyName(st.typingHotkey)) }
                TextButton(onClick = { scope.launch { repo.setTypingHotkey(0) } }) { Text(stringResource(R.string.hotkey_none)) }
            }
        }

        Section(stringResource(R.string.general_section))
        RowItem(stringResource(R.string.streamer_mode)) {
            Switch(checked = st.streamer, onCheckedChange = { scope.launch { repo.setStreamer(it) } })
        }
        RowItem(stringResource(R.string.touch_compat)) {
            Switch(checked = st.touchCompat, onCheckedChange = { scope.launch { repo.setTouchCompat(it) } })
        }
        RowItem(stringResource(R.string.perm_overlay)) {
            if (overlayOk) Text(stringResource(R.string.perm_ok), color = MaterialTheme.colorScheme.primary)
            else OutlinedButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            }) { Text(stringResource(R.string.perm_missing)) }
        }
        RowItem(stringResource(R.string.perm_accessibility)) {
            if (a11yOk) Text(stringResource(R.string.perm_ok), color = MaterialTheme.colorScheme.primary)
            else OutlinedButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }) { Text(stringResource(R.string.perm_missing)) }
        }
        TextButton(onClick = { scope.launch { repo.resetAll() } }) { Text(stringResource(R.string.reset_all)) }
    }

    if (capturing != 0) HotkeyDialog(
        onKey = { code ->
            scope.launch { if (capturing == 1) repo.setHotkey(code) else repo.setTypingHotkey(code) }
            capturing = 0
        },
        onDismiss = { capturing = 0 }
    )
}

@Composable
fun HotkeyDialog(onKey: (Int) -> Unit, onDismiss: () -> Unit) {
    val fr = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Box(
            Modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface)
                .padding(32.dp).focusRequester(fr).focusable()
                .onKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown) {
                        val code = e.nativeKeyEvent.keyCode
                        if (code == KeyEvent.KEYCODE_BACK) onDismiss() else onKey(code)
                        true
                    } else false
                }
        ) { Text(stringResource(R.string.hotkey_press)) }
        LaunchedEffect(Unit) { fr.requestFocus() }
    }
}

// ------------------------------------------------------------ Activation
@Composable
fun ActivationScreen(repo: SettingsRepo, st: AppSettings) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sh by shizukuState()
    val up by serverState()
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var cmd by remember { mutableStateOf("") }
    LaunchedEffect(st.token) {
        if (st.token.isNotEmpty()) cmd = withContext(Dispatchers.IO) { Activation.prepareScript(ctx, st.token) }
    }
    LaunchedEffect(Unit) { repo.ensureToken() }

    fun toast(res: Int) = Toast.makeText(ctx, res, Toast.LENGTH_SHORT).show()
    fun runStart(block: () -> Boolean) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { block() }
            toast(if (ok) R.string.act_started else R.string.act_failed)
        }
    }

    val modes = listOf(
        "wired" to R.string.mode_wired,
        "wireless" to R.string.mode_wireless,
        "root" to R.string.mode_root,
        "shizuku" to R.string.mode_shizuku,
        "shizuku_legacy" to R.string.mode_shizuku_legacy,
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.activation_title), fontSize = 20.sp, fontWeight = FontWeight.Medium)
        modes.forEach { (id, label) ->
            val selected = st.mode == id
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier.fillMaxWidth().clip(shape)
                    .background(if (selected) cs.secondaryContainer else cs.surfaceVariant)
                    .border(1.dp, if (selected) cs.primary else cs.outline, shape)
                    .selectable(selected = selected) { scope.launch { repo.setMode(id) } }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selected, onClick = null)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(label), Modifier.weight(1f))
                if (id == "shizuku") Text(stringResource(R.string.recommended), fontSize = 12.sp, color = cs.primary)
            }
        }

        Text(
            stringResource(if (up) R.string.act_server_up else R.string.act_server_down),
            color = if (up) cs.primary else cs.onSurfaceVariant, fontSize = 13.sp
        )

        when (st.mode) {
            "wired", "wireless" -> {
                Text(
                    if (st.mode == "wired") stringResource(R.string.act_wired_steps)
                    else stringResource(R.string.act_wireless_steps, Activation.localIp()),
                    fontSize = 13.sp
                )
                SelectionContainer {
                    Text(
                        cmd, fontSize = 13.sp, color = cs.primary,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(cs.surfaceVariant).padding(10.dp)
                    )
                }
                Button(onClick = { clipboard.setText(AnnotatedString(cmd)); toast(R.string.copied) }) {
                    Text(stringResource(R.string.copy_cmd))
                }
            }
            "root" -> Button(onClick = { runStart { Activation.startViaRoot(ctx, st.token) } }) {
                Text(stringResource(R.string.act_start_root))
            }
            "shizuku" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { ctx.startActivity(it) }
                }) { Text(stringResource(R.string.shizuku_open)) }
                if (sh == 1) Button(onClick = { runCatching { Shizuku.requestPermission(100) } }) {
                    Text(stringResource(R.string.shizuku_grant))
                }
                if (sh == 2) Button(onClick = { runStart { Activation.startViaShizuku(ctx, st.token) } }) {
                    Text(stringResource(R.string.act_start_shizuku))
                }
            }
            else -> Text(stringResource(R.string.act_legacy_note), fontSize = 13.sp, color = cs.onSurfaceVariant)
        }

        if (up) OutlinedButton(onClick = { ServerClient.quit() }) { Text(stringResource(R.string.act_stop_server)) }
    }
}

// ------------------------------------------------------------ About
@Composable
fun AboutScreen() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.app_name), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.about_text))
        Text("${stringResource(R.string.version)} ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
