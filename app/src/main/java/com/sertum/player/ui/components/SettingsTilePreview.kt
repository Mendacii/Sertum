package com.sertum.player.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sertum.player.ui.theme.SertumTheme

/**
 * Compose previews for the settings tile language.
 *
 * These exist so the tile geometry can be iterated in Android Studio's preview
 * pane instead of through a build, install and screenshot round trip - which is
 * what the first few revision cycles cost, and several defects (labels clipped,
 * one tile per row) were only visible after deploying.
 *
 * The previews are deliberately standalone: they take literal labels rather than
 * resources so the tile grid renders without an Application, preferences or a
 * database.
 */
@Composable
private fun PreviewSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Text(title, modifier = Modifier.padding(bottom = 6.dp))
        SettingsTileGrid { content() }
    }
}

@Composable
private fun Tile(
    label: String,
    icon: ImageVector,
    emphasis: TileEmphasis,
    selected: Boolean = false,
) {
    SettingsTile(
        label = label,
        icon = icon,
        emphasis = emphasis,
        selected = selected,
        onClick = {},
    )
}

/** Every state at once: selected, unselected and action tiles. */
@Preview(name = "Tiles - states", widthDp = 360, heightDp = 260, showBackground = true)
@Composable
private fun SettingsTilesStatesPreview() {
    SertumTheme {
        Column(Modifier.padding(16.dp)) {
            PreviewSection("扫描") {
                Tile("启动扫描", Icons.Filled.Refresh, TileEmphasis.TOGGLE, selected = true)
                Tile("全盘扫描", Icons.Filled.FolderOpen, TileEmphasis.TOGGLE)
                Tile("添加文件夹", Icons.Filled.CreateNewFolder, TileEmphasis.NAVIGABLE)
                Tile("重新扫描", Icons.Filled.Refresh, TileEmphasis.NAVIGABLE)
            }
            PreviewSection("输出模式") {
                Tile("自动/常规", Icons.Filled.Speaker, TileEmphasis.TOGGLE, selected = true)
                Tile("USB 独占", Icons.Filled.Usb, TileEmphasis.TOGGLE)
            }
        }
    }
}

/**
 * The longest labels in the app, which is the case that decides the tile width.
 * If any of these clip, the width has regressed.
 */
@Preview(name = "Tiles - longest labels", widthDp = 360, heightDp = 200, showBackground = true)
@Composable
private fun SettingsTilesLongestLabelsPreview() {
    SertumTheme {
        Column(Modifier.padding(16.dp)) {
            PreviewSection("最长标签压力测试") {
                Tile("添加文件夹", Icons.Filled.CreateNewFolder, TileEmphasis.NAVIGABLE)
                Tile("应用详情", Icons.Filled.Settings, TileEmphasis.NAVIGABLE)
                Tile("电池优化", Icons.Filled.BatterySaver, TileEmphasis.NAVIGABLE)
                Tile("跟随系统", Icons.Filled.Translate, TileEmphasis.TOGGLE, selected = true)
                Tile("深色主题", Icons.Filled.DarkMode, TileEmphasis.TOGGLE, selected = true)
                Tile("English", Icons.Filled.Translate, TileEmphasis.TOGGLE)
            }
        }
    }
}

/** Narrow width, to show the wrapping behaviour rather than assuming it. */
@Preview(name = "Tiles - narrow 320dp", widthDp = 320, heightDp = 200, showBackground = true)
@Composable
private fun SettingsTilesNarrowPreview() {
    SertumTheme {
        Column(Modifier.padding(16.dp)) {
            PreviewSection("窄屏") {
                Tile("启动扫描", Icons.Filled.Refresh, TileEmphasis.TOGGLE, selected = true)
                Tile("全盘扫描", Icons.Filled.FolderOpen, TileEmphasis.TOGGLE)
                Tile("添加文件夹", Icons.Filled.CreateNewFolder, TileEmphasis.NAVIGABLE)
                Tile("重新扫描", Icons.Filled.Refresh, TileEmphasis.NAVIGABLE)
            }
        }
    }
}
