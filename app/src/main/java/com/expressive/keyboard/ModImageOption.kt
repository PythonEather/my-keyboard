package com.expressive.keyboard

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The settings row of a plugin option with `"type": "image"`: a preview of the chosen picture, a button that opens the
 * gallery and a button that removes the picture. The setting's value is `user:<id>:<stamp>` while a picture is chosen
 * and "" while there is none (see GlTextures.userValue); [onValue] stores it like the value of any other option.
 */
@Composable
internal fun ModImageRow(p: KeyboardPlugin, o: ModOption, stored: Any?, onValue: (Any?) -> Unit, showTitle: Boolean = true) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val chosen = (stored as? String).orEmpty()
    var failed by remember(chosen) { mutableStateOf(false) }
    // a small copy is decoded off the main thread; a new picture has a new stamp, so it is decoded again
    val thumb by produceState<ImageBitmap?>(null, p.id, o.id, chosen) {
        this.value = if (chosen.isEmpty()) null else withContext(Dispatchers.IO) {
            UserPictures.thumbnail(context, p.id, o.id, 256)?.asImageBitmap()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val v = withContext(Dispatchers.IO) { UserPictures.save(context, p.id, o.id, uri) }
                if (v == null) failed = true else { failed = false; onValue(v) }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showTitle && o.title.isNotEmpty()) Text(tr(o.title), style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                val img = thumb
                if (img != null) Image(bitmap = img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Rounded.Image, null, tint = cs.onSurfaceVariant)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { picker.launch(arrayOf("image/*")) }) {
                    Icon(Icons.Rounded.AddPhotoAlternate, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr(if (chosen.isEmpty()) "Выбрать картинку мода" else "Сменить картинку мода"))
                }
                if (chosen.isNotEmpty()) {
                    TextButton(onClick = { UserPictures.clear(context, p.id, o.id); failed = false; onValue("") }) {
                        Text(tr("Убрать картинку мода"))
                    }
                }
            }
        }
        when {
            failed -> Text(tr("Не удалось прочитать картинку"), style = MaterialTheme.typography.bodySmall, color = cs.error)
            chosen.isEmpty() -> Text(tr("Картинка мода не выбрана"), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            else -> Text(
                tr("Картинка лежит только на этом телефоне, видит её только этот мод"),
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
            )
        }
    }
}
