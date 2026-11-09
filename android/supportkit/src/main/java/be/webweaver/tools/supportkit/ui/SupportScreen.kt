package be.webweaver.tools.supportkit.ui

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import be.webweaver.tools.supportkit.R
import be.webweaver.tools.supportkit.Sensitivity
import be.webweaver.tools.supportkit.SupportKit
import be.webweaver.tools.supportkit.SupportPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The orange used for the bar and the audio warning: readable on light and dark backgrounds. */
@Composable
fun supportOrange(): Color = if (isSystemInDarkTheme()) Color(0xFFFFB74D) else Color(0xFFB45309)

/**
 * The support screen (SPEC §7, WoofPilot iOS `DiagnosticsView`): small log + "Send log", the
 * support mode switch with consent, what is kept, the pack with its items, "Delete log".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportScreen(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val config = SupportKit.config
    val on by SupportKit.supportMode.collectAsState()
    val size by SupportKit.sizeBytes.collectAsState()
    val scope = rememberCoroutineScope()
    var askConsent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    // Android doesn't say what the owner did in the share sheet: only what was handed to it.
    // Shown under the button that was used, so it's on screen when the owner comes back.
    var handed by remember { mutableStateOf<Pair<Int, String>?>(null) }
    val values = remember { mutableStateMapOf<String, Int>().apply { put(SupportPack.LOG_ID, 1); config.packItems.forEach { put(it.id, it.defaultValue) } } }
    LaunchedEffect(Unit) { SupportKit.refreshSize() }

    fun shareFile(make: () -> java.io.File?, mime: String, @androidx.annotation.StringRes done: Int) {
        busy = true
        failed = false
        handed = null
        scope.launch {
            val file = withContext(Dispatchers.IO) { make() }
            busy = false
            if (file != null) {
                handed = done to context.getString(done, Formatter.formatShortFileSize(context, file.length()))
                SupportKit.share(context, file, mime)
            } else {
                failed = true
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.supportkit_title), modifier = Modifier.semantics { heading() }) },
                actions = { TextButton(onClick = onDone) { Text(stringResource(R.string.supportkit_done)) } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (config.smallLog && config.texts.smallLog != null) {
                SectionLabel(stringResource(R.string.supportkit_small_log_heading))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(config.texts.smallLog), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { shareFile({ SupportKit.exportLog() }, "text/plain", R.string.supportkit_log_handed) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.supportkit_send_log))
                        }
                        Handed(handed, R.string.supportkit_log_handed)
                        Text(stringResource(R.string.supportkit_send_log_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text(stringResource(config.texts.intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .toggleable(value = on, role = Role.Switch, onValueChange = { want -> if (want) askConsent = true else SupportKit.setSupportMode(false) })
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.supportkit_switch), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.supportkit_switch_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = on, onCheckedChange = null)
                }
            }
            SectionLabel(stringResource(R.string.supportkit_kept_heading))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    config.texts.kept.forEach { Bullet(stringResource(it)) }
                    Row(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.supportkit_current_log), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(Formatter.formatShortFileSize(context, size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            SectionLabel(stringResource(R.string.supportkit_pack_heading))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.supportkit_pack_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SwitchRow(stringResource(R.string.supportkit_pack_log), values[SupportPack.LOG_ID] != 0) { values[SupportPack.LOG_ID] = if (it) 1 else 0 }
                    config.packItems.forEach { item ->
                        val value = values[item.id] ?: 0
                        val choices = item.choices
                        if (choices == null) {
                            SwitchRow(stringResource(item.title), value != 0) { values[item.id] = if (it) 1 else 0 }
                        } else {
                            Text(stringResource(item.title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
                            choices.forEach { choice ->
                                Row(
                                    Modifier.fillMaxWidth().selectable(selected = value == choice.value, role = Role.RadioButton) { values[item.id] = choice.value }.heightIn(min = 44.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = value == choice.value, onClick = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(choice.label))
                                }
                            }
                        }
                        val warning = item.warning
                        if (value != 0 && (warning != null || item.sensitivity == Sensitivity.AUDIO)) {
                            warning?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = supportOrange()) }
                            val bytes = remember(value) { runCatching { item.estimateBytes(value) }.getOrDefault(0) }
                            if (bytes > 0) Text(Formatter.formatShortFileSize(context, bytes), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = supportOrange())
                        }
                    }
                }
            }
            if (failed) Text(stringResource(R.string.supportkit_pack_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Handed(handed, R.string.supportkit_pack_handed)
            Button(
                onClick = { shareFile({ SupportPack.build(values.toMap()) }, "application/zip", R.string.supportkit_pack_handed) },
                enabled = !busy && values.values.any { it != 0 },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Text(stringResource(if (busy) R.string.supportkit_making_pack else R.string.supportkit_make_pack))
            }
            OutlinedButton(
                onClick = { SupportKit.clearLog() },
                enabled = size > 0,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Text(stringResource(R.string.supportkit_delete_log))
            }
            Spacer(Modifier.heightIn(min = 16.dp))
        }
    }

    if (askConsent) {
        AlertDialog(
            onDismissRequest = { askConsent = false },
            title = { Text(stringResource(R.string.supportkit_consent_title)) },
            text = { Text(stringResource(config.texts.consent)) },
            confirmButton = {
                TextButton(onClick = { askConsent = false; SupportKit.setSupportMode(true) }) { Text(stringResource(R.string.supportkit_consent_on)) }
            },
            dismissButton = { TextButton(onClick = { askConsent = false }) { Text(stringResource(R.string.supportkit_cancel)) } },
        )
    }
}

/**
 * The orange bar on every screen while support mode is on (E43: the visible side of the consent).
 * Shows nothing when support mode is off.
 */
@Composable
fun SupportBanner(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val on by SupportKit.supportMode.collectAsState()
    if (!on) return
    val orange = supportOrange()
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .background(orange.copy(alpha = 0.14f), RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 36.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.supportkit_banner), color = orange, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.supportkit_banner_tap), color = orange, style = MaterialTheme.typography.bodySmall)
    }
}

/** "Log made (6 kB) …" under its own button: what was handed to the share sheet (not "sent"). */
@Composable
private fun Handed(handed: Pair<Int, String>?, which: Int) {
    if (handed?.first != which) return
    Text(handed.second, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp).semantics { heading() })
}

@Composable
private fun Bullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text("•", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}
