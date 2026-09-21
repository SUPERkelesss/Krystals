@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.krystals.app

import com.krystals.crystal.analysis.editing.*
import com.krystals.crystal.analysis.model.*
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.krystals.app.ui.ThemeMode

@Composable
internal fun AppMenu(
    menuOpen: Boolean,
    onDismissMenu: () -> Unit,
    onOpen: () -> Unit,
    onOpenPreset: () -> Unit,
    onOnlineSource: () -> Unit,
    onNew: () -> Unit,
    onHelp: () -> Unit,
    onFeedback: () -> Unit,
    onAbout: () -> Unit,
    onSponsor: () -> Unit,
    onExit: () -> Unit,
    fileActions: (@Composable () -> Unit)? = null,
) {
    DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
        DropdownMenuItem(text = { Text(stringResource(R.string.import_local)) }, leadingIcon = { Icon(Icons.Default.FileOpen, null) }, onClick = { onDismissMenu(); onOpen() })
        DropdownMenuItem(text = { Text(stringResource(R.string.open_preset_library)) }, leadingIcon = { Icon(Icons.Default.Inventory2, null) }, onClick = { onDismissMenu(); onOpenPreset() })
        DropdownMenuItem(text = { Text(stringResource(R.string.import_online)) }, leadingIcon = { Icon(Icons.Default.CloudDownload, null) }, onClick = { onDismissMenu(); onOnlineSource() })
        DropdownMenuItem(text = { Text(stringResource(R.string.new_file)) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.NoteAdd, null) }, onClick = { onDismissMenu(); onNew() })
        HorizontalDivider()
        if (fileActions != null) {
            fileActions()
            HorizontalDivider()
        }
        // Per v0.7.0: tighter 2x2 grid (12dp gap) and icons tinted like the other menu items.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
            TextButton(onClick = { onDismissMenu(); onHelp() }) { Icon(Icons.AutoMirrored.Filled.Help, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.help), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            TextButton(onClick = { onDismissMenu(); onFeedback() }) { Icon(Icons.Default.Feedback, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.feedback), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
            TextButton(onClick = { onDismissMenu(); onAbout() }) { Icon(Icons.Default.Info, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.about), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            TextButton(onClick = { onDismissMenu(); onSponsor() }) { Icon(Icons.Default.Favorite, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.sponsor), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        HorizontalDivider()
        DropdownMenuItem(text = { Text(localized("关闭所有文件并退出", "Close all files and exit")) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, null) }, onClick = { onDismissMenu(); onExit() })
    }
}


@Composable
internal fun HomeScreen(
    onOpen: () -> Unit,
    onOpenPreset: () -> Unit,
    onNew: () -> Unit,
    onOnlineSource: () -> Unit,
    themeMode: ThemeMode,
    onTheme: (ThemeMode) -> Unit,
    language: String,
    onLanguage: (String) -> Unit,
    settingsValues: SettingsValues,
    onSettingsChange: (SettingsValues) -> Unit,
    onRestoreDefaults: (() -> Unit)? = null,
    onHelp: () -> Unit,
    onAbout: () -> Unit,
    onSponsor: () -> Unit,
    onFeedback: () -> Unit,
    onExit: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // Per v0.7.0: preferences reachable directly from the home screen.
    var settingsOpen by remember { mutableStateOf(false) }
    if (settingsOpen) SettingsPanel(
        settings = settingsValues,
        onChange = onSettingsChange,
        onDismiss = { settingsOpen = false },
        onRestoreDefaults = onRestoreDefaults,
    )
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
        Box(Modifier.align(Alignment.TopStart)) {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.Menu, null) }
            AppMenu(
                menuOpen = menuOpen,
                onDismissMenu = { menuOpen = false },
                onOpen = onOpen,
                onOpenPreset = onOpenPreset,
                onOnlineSource = onOnlineSource,
                onNew = onNew,
                onHelp = onHelp,
                onFeedback = onFeedback,
                onAbout = onAbout,
                onSponsor = onSponsor,
                onExit = onExit,
            )
        }
        // Per v0.7.0: preferences in the top-right corner, same position as the editor page.
        Box(Modifier.align(Alignment.TopEnd)) {
            IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Default.Settings, null) }
        }
        // Per v0.4.3: in landscape the four import buttons span the full (very wide) screen and
        // look stretched; cap their width to half the screen there. Portrait keeps fillMaxWidth.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            val buttonWidth = if (landscape) Modifier.fillMaxWidth(0.5f) else Modifier.fillMaxWidth()
            Column(
                Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 28.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                AssetImage("main.png", Modifier.size(230.dp), ContentScale.Fit)
                Text("Krystals", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(28.dp))
                // Per v0.3.3: order matches the app menu — import local, preset library, online, new.
                Button(onClick = onOpen, modifier = buttonWidth.height(52.dp)) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.import_local)) }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onOpenPreset, modifier = buttonWidth.height(52.dp)) { Icon(Icons.Default.Inventory2, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.open_preset_library)) }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onOnlineSource, modifier = buttonWidth.height(52.dp)) { Icon(Icons.Default.CloudDownload, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.import_online)) }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onNew, modifier = buttonWidth.height(52.dp)) { Icon(Icons.AutoMirrored.Filled.NoteAdd, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.new_file)) }
            }
        }
    }
}

/** Per v0.7.0: mutually-exclusive full-screen viewer panels. Consolidates five independent
 *  booleans (align/measure/display/info/appearance) into one state so only a single write is
 *  needed per panel switch. menuOpen (dropdown) and toolOpen (floating-ball fan) stay as plain
 *  booleans because they can be open simultaneously with a full-screen panel. */

@Composable
internal fun AssetImage(path: String, modifier: Modifier, contentScale: ContentScale) {
    val context = LocalContext.current
    val image = remember(path) { runCatching { context.assets.open(path).use { stream -> BitmapFactory.decodeStream(stream) }.asImageBitmap() }.getOrNull() }
    if (image != null) Image(image, contentDescription = null, modifier = modifier, contentScale = contentScale)
    else Box(modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(20.dp)))
}

