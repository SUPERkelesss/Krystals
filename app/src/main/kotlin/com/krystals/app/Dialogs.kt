@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.krystals.app

import androidx.core.content.edit
import com.krystals.crystal.analysis.editing.*
import com.krystals.crystal.analysis.model.*
import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Help
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.krystals.app.ui.AppPalette

@Composable
internal fun AlignDialog(onDismiss: () -> Unit, onChoice: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized("对齐", "Align")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(localized("空间直角坐标系", "Cartesian"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("X", "Y", "Z").forEach { ChoiceTile(it, onChoice, textStyle = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(14.dp))
                Text(localized("晶胞轴", "Cell axes"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("a", "b", "c").forEach { ChoiceTile(it, onChoice, textStyle = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}


@Composable
internal fun MeasureDialog(
    length: String,
    angle: String,
    dihedral: String,
    off: String,
    activeMode: String? = null,
    onDismiss: () -> Unit,
    onChoice: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized("测量", "Measure")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(length, angle).forEach { label ->
                        ChoiceTile(label, onChoice, tileHeight = 72.dp, active = label == activeMode && activeMode != off, modifier = Modifier.weight(1f))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(dihedral, off).forEach { label ->
                        ChoiceTile(label, onChoice, tileHeight = 72.dp, active = label == activeMode && activeMode != off, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}


@Composable
private fun ChoiceTile(
    label: String,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    tileHeight: androidx.compose.ui.unit.Dp = 80.dp,
    active: Boolean = false,
    textStyle: androidx.compose.ui.text.TextStyle? = null,
) {
    // Per v0.6.2: in dark mode use light purple (0xFFCFA7F5) for highlights; deep purple in light mode.
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val activeColor = Color(AppPalette.highlight(dark))
    val resolvedStyle = textStyle ?: if (tileHeight >= 76.dp) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium
    Card(
        onClick = { onClick(label) },
        modifier = modifier.padding(4.dp).height(tileHeight).fillMaxWidth(),
        colors = if (active) CardDefaults.cardColors(containerColor = activeColor.copy(alpha = 0.15f)) else CardDefaults.cardColors(),
        border = if (active) androidx.compose.foundation.BorderStroke(2.dp, activeColor) else null,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, style = resolvedStyle, color = if (active) activeColor else Color.Unspecified)
        }
    }
}


@Composable
internal fun ActivationDialog(
    context: Context,
    onDismiss: () -> Unit,
    onConfirmed: () -> Unit,
    onMessage: (String) -> Unit,
) {
    var code by remember { mutableStateOf(ActivationManager.getActivationCode(context) ?: "") }
    // Pre-resolve the bilingual toast strings in the @Composable body; `localized` is itself a
    // @Composable function, so it cannot be called inside the non-composable onClick lambda.
    val activationSuccessText = localized("激活成功！感谢♪(^∇^*)", "Activated! Thanks ♪(^∇^*)")
    val activationInvalidText = localized("激活码无效", "Invalid activation code")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized("输入激活码", "Enter activation code")) },
        text = {
            Column {
                Text(localized("赞助后请输入您收到的 16 位激活码。", "Enter the 16-char activation code you received after sponsoring."), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(code, { code = it.uppercase() }, label = { Text(localized("激活码", "Activation code")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = code.isNotBlank(),
                onClick = {
                    if (ActivationManager.activate(context, code)) {
                        onMessage(activationSuccessText)
                        onConfirmed()
                    } else {
                        onMessage(activationInvalidText)
                    }
                },
            ) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}


@Composable
internal fun MpPremiumDialog(onDismiss: () -> Unit, onSponsor: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized("高级内容", "Premium content")) },
        text = {
            Text(localized("Materials Project中导入晶体需要获取apikey，属于高级内容，并且尚未完全开发，使用COD数据库完全可以解决大部分问题。\n · 如果需要使用，请赞助一点点以支持开发！", "Importing crystals from Materials Project requires an API key and is a premium feature, and has not been developed compeletely yet — the COD handles most needs.\n · To use it, please sponsor a little to support development!"))
        },
        confirmButton = { TextButton(onClick = onSponsor) { Text(localized("我要赞助！", "Sponsor!")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized("再考虑一下…", "Maybe later…")) } },
    )
}


/**
 * Per v0.7.0: restored sponsor dialog (the "投喂" cat-feeding message with three actions).
 * The v0.6.5 unified link-confirmation dialog replaced this window; the sponsor button and the
 * launch-count prompt now open it again instead of jumping straight to the browser link.
 */
@Composable
internal fun SponsorDialog(
    onDismiss: () -> Unit,
    launchCount: Int = 0,
    onSponsor: () -> Unit = {},
    onAlreadySponsored: () -> Unit = {},
    isActivated: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized("赞助 Krystals", "Sponsor Krystals")) },
        text = {
            Column {
                Text(
                    if (launchCount > 0) localized(
                        "Krystals 已经为您启动了 $launchCount 次啦！如果想要支持开发，请多多赞助作者 kelesss 哦！",
                        "Krystals has been launched $launchCount times! If you'd like to support development, please sponsor kelesss!",
                    )
                    else localized(
                        "支付一点大米让kelesss猫猫努力工作的说……ο(=•ω＜=)ρ⌒☆",
                        "Toss a little money to keep kelesss working hard…ο(=•ω＜=)ρ⌒☆",
                    )
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    localized(
                        "· 赞助后可永久关闭赞助提醒，并可以接入materials project检索。未赞助不影响绝大部分功能的使用。",
                        "· Sponsoring permanently dismisses this prompt and unlocks Materials Project search. Not sponsoring does not affect most features.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onSponsor) { Text(localized("我要赞助！", "Sponsor!")) }
                // Per 2026-08-09: 已激活(赞助过)的用户不再需要输入激活码,
                // "我已赞助"按键隐藏——它只对未赞助用户有意义。
                if (!isActivated) {
                    TextButton(onClick = onAlreadySponsored) { Text(localized("我已赞助", "I've sponsored!")) }
                }
                TextButton(onClick = onDismiss) { Text(localized("狠心拒绝", "Maybe later")) }
            }
        },
    )
}


@Composable
internal fun MpCautionDialog(preferences: SharedPreferences, onDismiss: () -> Unit, onContinue: () -> Unit) {
    var dontShow by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized("注意", "Note")) },
        text = {
            Column {
                Text(
                    localized(
                        "Krystals使用Materials Project的新API进行晶体搜索和下载。新API需要apikey才能使用。\n\n· 搜索结果中的晶胞为原始素晶胞，Krystals会自动将其转换为正当晶胞后展示。\n\n· 若转换失败，将回退为素晶胞展示。",
                        "Krystals uses the Materials Project new API for crystal search and download. The new API requires an API key.\n\n· Search results return primitive cells; Krystals automatically converts them to conventional cells before display.\n\n· If conversion fails, the primitive cell is shown instead."
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = dontShow, onCheckedChange = { dontShow = it })
                    Text(localized("不再显示", "Don't show again"), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (dontShow) preferences.edit { putBoolean(PreferencesStore.KEY_MP_CAUTION_DISMISSED, true) }
                onContinue()
            }) { Text(localized("我知道了", "Comfirm")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}


@Composable
internal fun AboutScreen(onBack: () -> Unit, onCheckUpdates: () -> Unit = {}, isCheckingUpdates: Boolean = false, updateMessage: String? = null, onOpenLink: (String) -> Unit = {}) {
    BackHandler(enabled = true) { onBack() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.navigationBars)) {
        TopAppBar(
            title = { Text(stringResource(R.string.about)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            AssetImage("icon_foreground.png", Modifier.size(96.dp), ContentScale.Fit)
            Spacer(Modifier.height(16.dp))
            Text("Krystals", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(8.dp))
            Text("${stringResource(R.string.version)} ${com.krystals.app.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(24.dp))
            Text(localized("Krystals 是由 凯楽斯kelesss 与 AI辅助开发的一款 Android 平台轻量级晶体结构查看和编辑工具。", "Krystals is a lightweight Android CIF crystal structure viewer and editor, developed by kelesss with AI assistance."), style = MaterialTheme.typography.bodyLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(16.dp))
            // Per v0.7.1: "Check for Updates" button moved below the software description.
            Button(
                onClick = onCheckUpdates,
                enabled = !isCheckingUpdates,
                shape = RoundedCornerShape(12.dp),
            ) {
                if (isCheckingUpdates) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(localized("检查更新", "Check for Updates"))
            }
            // Per v0.7.1: show update check result directly on the About screen
            // (previously used showMessage/Snackbar which was hidden behind this full-screen overlay).
            if (updateMessage != null) {
                Spacer(Modifier.height(8.dp))
                Text(updateMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
            Text(localized("关于作者", "About the author"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 8.dp))
            // Per v0.2.3: links in one horizontal row, separated by " | " (dropped Bilibili live + Zhihu).
            val links = listOf(
                localized("个人网站", "Website") to "https://www.kelesss.art",
                "GitHub" to "https://github.com/SUPERkelesss",
                "Bilibili" to "https://space.bilibili.com/334614292",
                localized("Q群", "QQ group") to "https://qm.qq.com/q/YXattqg3Kg",
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                links.forEachIndexed { index, (label, url) ->
                    if (index > 0) Text("  |  ", color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        label,
                        color = MaterialTheme.colorScheme.primary,
                        // Per v0.7.0: route through the external-link confirmation dialog (same
                        // as Help/Sponsor/Feedback) instead of firing the browser intent directly.
                        modifier = Modifier.clickable { onOpenLink(url) },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(localized("鸣谢名单", "Acknowledgements"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 4.dp))
            Text("阿焱焱焱.cif" ,style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
            Text("罗马" ,style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
            Text("拉格朗月" ,style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
            Text("1,3丁二烯" ,style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
            Text(localized("参与软件测试人员", "participants of the beta version"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(32.dp))
            Text(localized("© 2026 made with ♥ by kelesss", "© 2026 made with ♥ by kelesss"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(localized("软件使用 ChatGPT Codex 和 Kimi Code 辅助构建。", "Built with assistance from ChatGPT Codex and Kimi Code."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

