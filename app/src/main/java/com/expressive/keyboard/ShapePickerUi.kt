package com.expressive.keyboard

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// ============================================================================
// Button shapes in groups (11.2.36). Only one group is open at a time, so 100+ shapes do not bury the page.
// Favourites and the last shapes you used sit on top; "Surprise me" picks a random one.
// ============================================================================

private const val FAVS_KEY = "shape_favs"
private const val RECENT_KEY = "shape_recent"

private fun readIds(c: Context, key: String): List<String> {
    val raw = runCatching { KeyboardPrefs.prefs(c).getString(key, "") }.getOrNull().orEmpty()
    return raw.split(',').filter { id -> id.isNotEmpty() && ExpressiveShapePreset.entries.any { it.id == id } }
}

private fun writeIds(c: Context, key: String, ids: List<String>) {
    KeyboardPrefs.edit(c) { putString(key, ids.joinToString(",")) }
}

private fun presetOf(id: String): ExpressiveShapePreset? = ExpressiveShapePreset.entries.firstOrNull { it.id == id }

/** The shape picker of the "Button shape" section (menu, mic and letter-popup bubbles). */
@Composable
internal fun ShapePicker(settings: KeyboardSettings) {
    val context = LocalContext.current
    ShapeGroupsPicker(settings.shapePreset, settings.accentSpec()) { KeyboardPrefs.applyPreset(context, it) }
}

/** The same picker for the letter popup's own shape. */
@Composable
internal fun PopupShapePicker(settings: KeyboardSettings) {
    val context = LocalContext.current
    val id = settings.popupShapePreset.ifEmpty { settings.shapePreset }
    ShapeGroupsPicker(id, WavySpec.of(ExpressiveShapePreset.fromId(id))) { p ->
        KeyboardPrefs.edit(context) { putString("popup_shape_preset", p.id) }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ShapeGroupsPicker(selectedId: String, activeSpec: WavySpec, onPick: (ExpressiveShapePreset) -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var favs by remember { mutableStateOf(readIds(context, FAVS_KEY)) }
    var recent by remember { mutableStateOf(readIds(context, RECENT_KEY)) }
    var open by remember { mutableStateOf<String?>(null) }
    val current = ExpressiveShapePreset.fromId(selectedId)
    val isFav = current.id in favs

    fun pick(p: ExpressiveShapePreset) {
        onPick(p)
        val next = (listOf(p.id) + recent.filter { it != p.id }).take(8)
        recent = next
        writeIds(context, RECENT_KEY, next)
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // the shape you wear now, with a star (favourite) and a dice (random)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.size(60.dp).wavyBackground(activeSpec, cs.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, tint = cs.onPrimary)
            }
            Column(Modifier.weight(1f)) {
                Text(tr(current.title), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tr("Your shape now"), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = {
                val next = if (isFav) favs - current.id else (listOf(current.id) + favs).take(12)
                favs = next
                writeIds(context, FAVS_KEY, next)
            }) {
                Icon(
                    if (isFav) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    tr(if (isFav) "Remove from favourites" else "Add to favourites"),
                    tint = if (isFav) cs.primary else cs.onSurfaceVariant
                )
            }
        }

        // "Surprise me" has its own full-width row, so the shape name keeps room to breathe (11.2.37)
        FilledTonalButton(
            onClick = {
                val choices = ExpressiveShapePreset.entries.filter { it.id != current.id }
                if (choices.isNotEmpty()) pick(choices.random())
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Rounded.Shuffle, null, modifier = Modifier.size(18.dp))
            Text(tr("Surprise me"), modifier = Modifier.padding(start = 6.dp), maxLines = 1)
        }

        if (favs.isNotEmpty()) MiniShapeRow("Favourites", favs, selectedId, activeSpec) { pick(it) }
        if (recent.isNotEmpty()) MiniShapeRow("Recently used", recent, selectedId, activeSpec) { pick(it) }

        for ((title, shapes) in ShapeGroups.all) {
            val isOpen = open == title
            GroupHeader(title, shapes, selectedId, isOpen) { open = if (isOpen) null else title }
            AnimatedVisibility(
                visible = isOpen,
                enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
                ) {
                    shapes.forEach { p -> ShapeCell(p, selectedId == p.id, activeSpec) { pick(p) } }
                }
            }
        }
    }
}

@Composable
private fun MiniShapeRow(title: String, ids: List<String>, selectedId: String, activeSpec: WavySpec, onPick: (ExpressiveShapePreset) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(tr(title), style = MaterialTheme.typography.labelLarge, color = cs.primary, modifier = Modifier.padding(horizontal = 4.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ids.mapNotNull { presetOf(it) }.forEach { p -> ShapeCell(p, selectedId == p.id, activeSpec) { onPick(p) } }
        }
    }
}

@Composable
private fun GroupHeader(title: String, shapes: List<ExpressiveShapePreset>, selectedId: String, open: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val turn by animateFloatAsState(if (open) 180f else 0f, spring(dampingRatio = 0.8f, stiffness = 400f), label = "groupArrow")
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (open) cs.secondaryContainer.copy(alpha = 0.55f) else cs.surfaceVariant.copy(alpha = 0.35f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            shapes.take(4).forEach { p ->
                Box(Modifier.size(26.dp).wavyBackground(WavySpec.of(p), if (p.id == selectedId) cs.primary else cs.secondary.copy(alpha = 0.55f)))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(tr(title), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tr("{0} shapes", shapes.size), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
        Icon(Icons.Rounded.ExpandMore, null, Modifier.graphicsLayer { rotationZ = turn })
    }
}

@Composable
private fun ShapeCell(preset: ExpressiveShapePreset, selected: Boolean, activeSpec: WavySpec, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val spec = if (selected) activeSpec else WavySpec.of(preset)
    val scale by animateFloatAsState(if (selected) 1f else 0.82f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "shapeScale")
    val rot by animateFloatAsState(if (selected && preset.tunable) 45f else 0f, spring(dampingRatio = 0.6f, stiffness = 200f), label = "shapeRot")
    Column(
        Modifier.width(72.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            Modifier.size(56.dp).graphicsLayer {
                scaleX = scale
                scaleY = scale
            }.wavyBackground(spec, if (selected) cs.primary else cs.secondaryContainer) { rot },
            contentAlignment = Alignment.Center
        ) {
            if (selected) Icon(Icons.Rounded.Check, null, tint = cs.onPrimary)
        }
        Text(
            tr(preset.title), style = MaterialTheme.typography.labelMedium,
            color = if (selected) cs.primary else cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}
