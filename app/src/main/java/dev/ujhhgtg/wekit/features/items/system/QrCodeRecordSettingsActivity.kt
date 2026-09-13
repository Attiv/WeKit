package dev.ujhhgtg.wekit.features.items.system

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.Keep
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CheckableDropdownMenuItem
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Content_copy
import com.composables.icons.materialsymbols.outlined.Check
import com.composables.icons.materialsymbols.outlined.Delete_sweep
import com.composables.icons.materialsymbols.outlined.Globe
import com.composables.icons.materialsymbols.outlined.History
import com.composables.icons.materialsymbols.outlined.Info
import com.composables.icons.materialsymbols.outlined.More_vert
import com.composables.icons.materialsymbols.outlined.Person
import com.composables.icons.materialsymbols.outlined.Shopping_cart
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.WeKitLocaleProvider
import dev.ujhhgtg.wekit.ui.content.IconButton
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseItemContainer
import dev.ujhhgtg.wekit.ui.content.m3.SETTINGS_CONTENT_BOTTOM_INSET
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SettingsConfirmDialog
import dev.ujhhgtg.wekit.ui.content.m3.SettingsScaffold
import dev.ujhhgtg.wekit.ui.utils.theme.ModuleTheme
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.formatEpoch

@Keep
class QrCodeRecordSettingsActivity : ComponentActivity() {
    private var records by mutableStateOf(emptyList<QrCodeRecord.QrRecord>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WeKitLocaleProvider(mode = LocaleResourceMode.InjectedHost) {
                ModuleTheme {
                    QrCodeRecordSettingsScreen(
                        records = records,
                        onFinish = ::finish,
                        onClear = {
                            QrCodeRecord.clearAllRecords()
                            records = emptyList()
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        records = QrCodeRecord.recordsSnapshot()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QrCodeRecordSettingsScreen(
    records: List<QrCodeRecord.QrRecord>,
    onFinish: () -> Unit,
    onClear: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var homeMenuEnabled by remember { mutableStateOf(QrCodeRecord.showInHomeMenu) }

    SettingsScaffold(
        title = stringResource(R.string.feature_qr_code_record_name),
        onBack = onFinish,
        actions = {
            Box {
                IconButton({ menuExpanded = true }) {
                    Icon(
                        MaterialSymbols.Outlined.More_vert,
                        contentDescription = stringResource(R.string.qr_code_record_menu),
                    )
                }
                // Share DropDownMenuWidget's expressive popup and grouped menu shapes.
                DropdownMenuPopup(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 2)) {
                        CheckableDropdownMenuItem(
                            checked = homeMenuEnabled,
                            onCheckedChange = { enabled ->
                                menuExpanded = false
                                homeMenuEnabled = enabled
                                QrCodeRecord.showInHomeMenu = enabled
                            },
                            text = { Text(stringResource(R.string.qr_code_record_home_menu_enabled)) },
                            supportingText = { Text(stringResource(R.string.qr_code_record_home_menu_description)) },
                            trailingContent = if (homeMenuEnabled) {
                                { Icon(MaterialSymbols.Outlined.Check, contentDescription = null) }
                            } else null,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            shapes = MenuDefaults.itemShape(0, 1),
                        )
                    }
                    Spacer(Modifier.size(2.dp))
                    DropdownMenuGroup(shapes = MenuDefaults.groupShape(1, 2)) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_clear)) },
                            leadingIcon = { Icon(MaterialSymbols.Outlined.Delete_sweep, null) },
                            enabled = records.isNotEmpty(),
                            onClick = { menuExpanded = false; confirmClear = true },
                            shape = MenuDefaults.standaloneItemShape,
                            colors = MenuDefaults.itemColors(
                                textColor = MaterialTheme.colorScheme.error,
                                leadingIconColor = MaterialTheme.colorScheme.error,
                            ),
                        )
                    }
                }
            }
        },
    ) {
        if (records.isEmpty()) {
            item {
                SegmentedColumn {
                    item {
                        BaseItemContainer {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    MaterialSymbols.Outlined.History,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    stringResource(R.string.system_qr_code_record_empty),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Text(
                                    stringResource(R.string.qr_code_record_empty_description),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            itemsIndexed(records, key = { index, record -> "${record.time}:$index" }) { _, record ->
                QrRecordCard(record)
            }
        }
        item { Spacer(Modifier.size(SETTINGS_CONTENT_BOTTOM_INSET)) }
    }

    SettingsConfirmDialog(
        show = confirmClear,
        title = stringResource(R.string.action_clear),
        message = stringResource(R.string.system_qr_code_record_clear_description),
        confirmLabel = stringResource(R.string.action_clear),
        dismissLabel = stringResource(R.string.dialog_cancel),
        destructive = true,
        onConfirm = {
            onClear()
            confirmClear = false
        },
        onDismiss = { confirmClear = false },
    )
}

@Composable
private fun QrRecordCard(record: QrCodeRecord.QrRecord) {
    val context = LocalContext.current
    val activity = LocalActivity.current!!
    val copiedMessage = stringResource(R.string.copied_to_clipboard)
    val openFailedMessage = stringResource(R.string.qr_code_record_open_failed)
    var expanded by rememberSaveable(record.url, record.time) { mutableStateOf(false) }
    var truncated by remember(record.url) { mutableStateOf(false) }
    val uri = remember(record.url) { record.url.toUri() }
    val (icon, typeRes) = when {
        uri.host.equals("u.wechat.com", ignoreCase = true) ->
            MaterialSymbols.Outlined.Person to R.string.qr_code_record_type_contact
        uri.host.equals("wx.tenpay.com", ignoreCase = true) || record.url.startsWith("weixin://wxpay") ->
            MaterialSymbols.Outlined.Shopping_cart to R.string.qr_code_record_type_payment
        else -> MaterialSymbols.Outlined.Info to R.string.qr_code_record_type_other
    }

    SegmentedColumn {
        item {
            BaseItemContainer {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(typeRes), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                            Text(formatEpoch(record.time, true), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                        Row {
                            IconButton({
                                runCatching { QrCodeRecord.openInWeChat(activity, record) }
                                    .onFailure {
                                        WeLogger.e("QrCodeRecord", "Failed to open native scan handler", it)
                                        showToast(context, openFailedMessage)
                                    }
                            }) { Icon(MaterialSymbols.Outlined.Globe, stringResource(R.string.system_qr_code_record_open)) }
                            IconButton({ copyToClipboard(context, record.url); showToast(context, copiedMessage) }) {
                                Icon(MaterialSymbols.Outlined.Content_copy, stringResource(R.string.system_qr_code_record_copy))
                            }
                        }
                    }
                    SelectionContainer {
                        Text(
                            record.url,
                            modifier = Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (expanded) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { if (!expanded) truncated = it.hasVisualOverflow },
                        )
                    }
                    if (truncated || expanded) {
                        TextButton(modifier = Modifier.padding(horizontal = 16.dp), onClick = { expanded = !expanded }) {
                            Text(stringResource(if (expanded) R.string.qr_code_record_collapse else R.string.qr_code_record_expand))
                        }
                    }
                    Spacer(Modifier.size(4.dp))
                }
            }
        }
    }
}
