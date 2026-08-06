@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.krystals.app

import com.krystals.crystal.analysis.editing.*
import com.krystals.crystal.analysis.model.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
internal fun CommentsPanel(tab: DocumentTab, onDismiss: () -> Unit) {
    // Per v0.7.0: adjustable comments panel with debounce auto-save.
    var text by remember(tab.id) { mutableStateOf(tab.comments) }
    LaunchedEffect(text) {
        if (text != tab.comments) {
            delay(500)
            tab.comments = text
            tab.dirty = true
        }
    }
    ResizableSlidePanel(
        ratioKey = "comments_panel_ratio",
        defaultRatio = 0.5f,
        onDismiss = onDismiss,
    ) { closePanel ->
        Column(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    localized("添加晶体备注", "Add Crystal Comments"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { closePanel() }) { Icon(Icons.Default.Close, null) }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().weight(1f),
                placeholder = { Text(localized("在此输入备注…", "Type comments here…")) },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                localized("Krystals 备注将在保存时写入文件中", "Krystals comments will be written to the file when saved"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

