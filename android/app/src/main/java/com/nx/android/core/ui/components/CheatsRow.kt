package com.nx.android.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nx.android.R
import com.nx.android.core.ui.theme.NxPalette

/**
 * The cheats row: the cheat files the player imported (the app does not carry them).
 *
 * The same shape as the firmware row: a status dot, what is imported (how many games, how many files), and
 * the buttons to import a zip or to clear it. The files live in the app's own folder (files/cheats/), so a
 * rebuilt data pack does not touch them.
 *
 * @param onClear the clear button's action; without it (null) the button is not shown
 */
@Composable
fun CheatsRow(
    games: Int,
    files: Int,
    onImport: () -> Unit,
    onClear: (() -> Unit)? = null,
) {
    InfoRow(
        title = stringResource(R.string.cheats_title),
        // 没导入时不写「没导入」：状态点的颜色已经说了，重复一遍只是噪音
        value = if (games > 0) stringResource(R.string.cheats_imported, games, files) else null,
        leading = { StatusDot(if (games > 0) NxPalette.Ready else NxPalette.Unbuilt, 8.dp) },
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                QuietButton(
                    if (games > 0) stringResource(R.string.cheats_reimport) else stringResource(R.string.cheats_import),
                    onClick = onImport,
                )
                if (games > 0 && onClear != null) {
                    QuietButton(stringResource(R.string.builds_clear), onClick = onClear)
                }
            }
        },
    )
}

/** The cheats row below it: a line saying where the files come from */
@Composable
fun CheatsHint() {
    Text(
        stringResource(R.string.cheats_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
    )
}
