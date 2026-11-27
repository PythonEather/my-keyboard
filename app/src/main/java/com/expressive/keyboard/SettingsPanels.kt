package com.expressive.keyboard

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// ============================================================================
// Tabbed settings pages. A long page is split into a few tabs. Only the sections of the open tab are composed,
// so a page opens and scrolls faster, and the sections of a tab settle in one after another.
// The pages themselves are not changed: SettingsSection asks LocalPageTabs whether its title belongs to the open tab.
// ============================================================================

/**
 * One tab. [sections] are section titles in the source language (a title that was already passed through tr() matches too).
 * [prefixes] are titles with a {0} in them. The tab with [others] also takes sections nobody claims and sections without a title.
 */
internal data class PageTab(
    val label: String,
    val icon: ImageVector,
    val sections: List<String>,
    val others: Boolean = false,
    val prefixes: List<String> = emptyList()
)

/** The tabs of the open page and which one is open. A new one is made on every tab change. */
internal class PageTabsInfo(val tabs: List<PageTab>, val selected: Int, private val pinned: List<String>) {
    val createdAt: Long = SystemClock.uptimeMillis()
    /** Numbers the sections of this tab in the order they appear, for the staggered entrance. */
    val counter = IntArray(1)

    private fun claims(tab: PageTab, title: String): Boolean =
        tab.sections.any { it == title || tr(it) == title } ||
            tab.prefixes.any { title.startsWith(tr(it).substringBefore("{0}")) }

    private fun ownerOf(title: String?): Int {
        if (title != null) {
            for (i in tabs.indices) if (claims(tabs[i], title)) return i
        }
        val others = tabs.indexOfFirst { it.others }
        return if (others >= 0) others else 0
    }

    fun shows(title: String?): Boolean {
        if (title != null && pinned.any { it == title || tr(it) == title }) return true
        return ownerOf(title) == selected
    }
}

internal val LocalPageTabs = compositionLocalOf<PageTabsInfo?> { null }

internal object PageTabs {
    fun of(page: SettingsPage): List<PageTab>? = when (page) {
        SettingsPage.COLORS -> listOf(
            PageTab("Цвета", Icons.Rounded.Palette, listOf("Акцентный цвет", "Формы", "Палитра", "Цвета клавиш"), others = true),
            PageTab("Фон", Icons.Rounded.Wallpaper, listOf("Размытие", "Живые обои под клавишами", "Фоновая картинка", "Shadows"))
        )
        SettingsPage.SIZE -> listOf(
            PageTab(
                "Размер", Icons.Rounded.Height,
                listOf("Клавиши: размер и форма", "Кнопка эмодзи: размер и место", "Рамка и отступы клавиатуры", "Панель над клавишами"), others = true
            ),
            PageTab("Положение", Icons.Rounded.SwapHoriz, listOf("Режим одной руки", "Плавающая клавиатура", "Масштаб клавиатуры (DPI)"))
        )
        SettingsPage.KEYS -> listOf(
            PageTab("Клавиши", Icons.Rounded.Keyboard, listOf("Клавиши и кнопки"), others = true),
            PageTab("Шрифт", Icons.Rounded.Style, listOf("Шрифт", "Форма кнопок"))
        )
        SettingsPage.THEMES -> listOf(
            PageTab(
                "Темы", Icons.Rounded.Palette,
                listOf("Темы", "Жидкое стекло", "Общие настройки темы"), others = true,
                prefixes = listOf("Настройки темы «{0}»")
            ),
            PageTab("Свои", Icons.Rounded.Brush, listOf("Свои темы"))
        )
        SettingsPage.ANIMATIONS -> listOf(
            PageTab("Нажатие", Icons.Rounded.TouchApp, listOf("Нажатие клавиши", "Подсказка над клавишей"), others = true),
            PageTab("Панели", Icons.Rounded.Dashboard, listOf("Панели", "Открытие клавиатуры")),
            PageTab("Свои", Icons.Rounded.Brush, listOf("Свои анимации"))
        )
        SettingsPage.EXTRAS -> listOf(
            PageTab("Touch", Icons.Rounded.TouchApp, listOf("Press shockwave", "Space bar cursor ticks", "Edge swipe for one hand"), others = true),
            PageTab("Light", Icons.Rounded.LightMode, listOf("Tilt parallax", "Typing glow"))
        )
        SettingsPage.AUTO_THEMES -> listOf(
            PageTab("Looks", Icons.Rounded.Save, listOf("Saved looks"), others = true),
            PageTab("Apps", Icons.Rounded.Apps, listOf("Theme for each app")),
            PageTab("Day and night", Icons.Rounded.DarkMode, listOf("Day and night"))
        )
        SettingsPage.LAYOUT_PROFILES -> listOf(
            PageTab("Profiles", Icons.Rounded.Straighten, listOf("Your profiles"), others = true),
            PageTab("Share", Icons.Rounded.Share, listOf("Share a profile"))
        )
        SettingsPage.PLUGINS -> listOf(
            PageTab("Плагины", Icons.Rounded.Extension, listOf("Мои плагины", "Не загрузились", "Встроенные")),
            PageTab("Установка", Icons.Rounded.Link, listOf("Установка"), others = true),
            PageTab("Защита", Icons.Rounded.Lock, listOf("Plugin security"))
        )
        else -> null
    }

    /** Sections that stay on screen whichever tab is open. */
    fun pinned(page: SettingsPage): List<String> = when (page) {
        SettingsPage.COLORS -> listOf("Предпросмотр")
        else -> emptyList()
    }
}

/**
 * The tab bar. The pill under the open tab slides and settles with a small spring.
 * Its position is read only while drawing, so a tap does not recompose the bar on every frame.
 */
@Composable
internal fun PageTabBar(tabs: List<PageTab>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val pos = remember { Animatable(selected.toFloat()) }
    LaunchedEffect(selected) { pos.animateTo(selected.toFloat(), spring(dampingRatio = 0.72f, stiffness = 520f)) }
    val pill = cs.primaryContainer
    val count = tabs.size
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(glassSurface(cs.surfaceBright))
            .padding(4.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .drawBehind {
                    val w = size.width / count
                    drawRoundRect(
                        color = pill,
                        topLeft = Offset(w * pos.value, 0f),
                        size = Size(w, size.height),
                        cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
                    )
                }
        ) {
            tabs.forEachIndexed { i, t ->
                val fg by animateColorAsState(
                    if (i == selected) cs.onPrimaryContainer else cs.onSurfaceVariant, label = "tabFg"
                )
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(22.dp))
                        .semantics { role = Role.Tab }
                        .clickable { onSelect(i) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(t.icon, null, tint = fg, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr(t.label), style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A row of ready accent colours: one tap instead of opening the colour picker. */
@Composable
internal fun QuickAccentRow(current: Int, onPick: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SWATCHES.forEach { c -> ColorDot(Color(c), current == c, 40.dp) { onPick(c) } }
    }
}
