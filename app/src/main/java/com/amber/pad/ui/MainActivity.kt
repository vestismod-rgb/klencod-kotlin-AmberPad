package com.amber.pad.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amber.pad.R
import com.amber.pad.data.AppSettings
import com.amber.pad.data.SettingsRepo

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AmberTheme { App() } }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepo(ctx) }
    val st by repo.flow.collectAsState(initial = AppSettings())
    var tab by remember { mutableIntStateOf(0) }
    val shape = RoundedCornerShape(20.dp)
    val items = listOf(
        R.string.nav_games to Icons.Default.SportsEsports,
        R.string.nav_settings to Icons.Default.Tune,
        R.string.nav_activation to Icons.Default.Bolt,
        R.string.nav_about to Icons.Default.Info,
    )

    Row(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .systemBarsPadding().padding(8.dp)
    ) {
        NavigationRail(containerColor = Color.Transparent) {
            items.forEachIndexed { i, (label, icon) ->
                NavigationRailItem(
                    selected = tab == i,
                    onClick = { tab = i },
                    icon = { Icon(icon, contentDescription = null) },
                    label = { Text(stringResource(label), fontSize = 11.sp) },
                    colors = NavigationRailItemDefaults.colors(
                        selectedIconColor = Color(0xFF111111),
                        indicatorColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                    )
                )
            }
        }
        Box(
            Modifier.weight(1f).fillMaxHeight().clip(shape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                .padding(16.dp)
        ) {
            when (tab) {
                0 -> GamesScreen(repo, st) { tab = 2 }
                1 -> SettingsScreen(repo, st)
                2 -> ActivationScreen(repo, st)
                else -> AboutScreen()
            }
        }
    }
}