package com.expressive.keyboard

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// ============================================================================
// Settings redesign: the round search button and the look of every page except Home.
// The sub pages are restyled through the shared components (SettingsSection, GroupItem, IconBadge), so every page
// gets the new look at once and Home keeps its own design (LocalSubpageLook is null there).
// ============================================================================

/** Set on every page except Home. [wavy] = expressive shapes are on (same rule as the Home cards). */
@Immutable
internal data class SubpageLook(val wavy: Boolean)

internal val LocalSubpageLook = compositionLocalOf<SubpageLook?> { null }

/**
 * Search that is a round button until it is tapped, then grows into the search field and opens the keyboard.
 * The width is animated inside the layout phase and the colour inside the draw phase, so while it opens nothing
 * recomposes: the animation stays smooth even while the results list is built.
 * Back, the X button on an empty field, or clearing the search closes it again.
 */
@Composable
internal fun ExpandingSearch(query: String, onQueryChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    var expanded by rememberSaveable { mutableStateOf(query.isNotEmpty()) }
    var wantFocus by remember { mutableStateOf(false) }
    val progress = remember { Animatable(if (expanded) 1f else 0f) }
    // the field exists only while the search is (almost) open, so the closed button costs nothing
    val showField by remember { derivedStateOf { progress.value > 0.02f } }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(expanded) {
        progress.animateTo(if (expanded) 1f else 0f, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow))
    }
    LaunchedEffect(showField, wantFocus) {
        if (wantFocus && showField) {
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
            wantFocus = false
        }
    }
    fun close() {
        onQueryChange("")
        expanded = false
        wantFocus = false
        focusManager.clearFocus()
    }
    BackHandler(enabled = expanded) { close() }

    val closedColor = cs.primaryContainer
    val openColor = glassSurface(cs.surfaceBright)
    val iconTint = if (expanded) cs.primary else cs.onPrimaryContainer
    val shape = RoundedCornerShape(28.dp)

    Box(modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.CenterEnd) {
        Row(
            Modifier
                .layout { measurable, constraints ->
                    val h = 56.dp.roundToPx()
                    val minW = h
                    val maxW = maxOf(minW, constraints.maxWidth)
                    val w = (minW + (maxW - minW) * progress.value).roundToInt().coerceIn(minW, maxW)
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    layout(w, h) { placeable.place(0, 0) }
                }
                .clip(shape)
                .drawBehind { drawRect(lerp(closedColor, openColor, progress.value.coerceIn(0f, 1f))) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(56.dp).clickable(enabled = !expanded) {
                    expanded = true
                    wantFocus = true
                },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Search, tr("Поиск"), tint = iconTint)
            }
            if (showField) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .graphicsLayer { alpha = ((progress.value - 0.35f) / 0.65f).coerceIn(0f, 1f) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                    }),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(
                                    tr(placeholder),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = cs.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            inner()
                        }
                    }
                )
                Box(
                    Modifier
                        .size(48.dp)
                        .graphicsLayer { alpha = ((progress.value - 0.5f) / 0.5f).coerceIn(0f, 1f) }
                        .clickable(enabled = expanded) { if (query.isNotEmpty()) onQueryChange("") else close() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Close, tr("Очистить"), tint = cs.onSurfaceVariant)
                }
            }
        }
    }
}

/** The card at the top of every page except Home: a wavy icon and what the page is for. It settles in once. */
@Composable
internal fun PageHeader(page: SettingsPage, wavy: Boolean) {
    if (page.subtitle.isBlank()) return
    val cs = MaterialTheme.colorScheme
    val spec = remember(page, wavy) { if (wavy) WavySpec.of(page.shape) else WavySpec.Circle }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow)) }
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = enter.value.coerceIn(0f, 1f)
                translationY = (1f - enter.value) * 24.dp.toPx()
            }
            .clip(RoundedCornerShape(28.dp))
            .background(glassSurface(cs.primaryContainer))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            Modifier.size(56.dp).wavyBackground(spec, cs.primary) { (1f - enter.value) * -40f },
            contentAlignment = Alignment.Center
        ) {
            Icon(page.icon, null, tint = cs.onPrimary, modifier = Modifier.size(28.dp))
        }
        Text(
            tr(page.subtitle),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onPrimaryContainer
        )
    }
}
