package com.expressive.keyboard

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "YOU ARE THE SECURITY": shown the first time the user opens a plugin panel (Settings → Plugins and the
 * Plugins panel on the keyboard) until they confirm 5 times that they understand. The answer is saved
 * once, so the screen never comes back. This is a notice, not a sandbox: it does not stop plugins from running.
 */
internal object PluginTrust {
    const val PREF_KEY = "plugin_trust_ack"
    const val TAPS_NEEDED = 5

    /** Where "No" sends the user: the original project, whose releases hold the stable official build. */
    const val OFFICIAL_URL = "https://github.com/kotlalipopus/Expressive-Keyboard/releases/latest"
}

private val TrustBg = Color(0xFF140404)
private val TrustRed = Color(0xFFFF2D2D)
private val TrustRedDark = Color(0xFF8E0000)
private val TrustText = Color(0xFFF2E6E6)
private val TrustMuted = Color(0xFFBFA5A5)

/**
 * Shows [content] once the warning is accepted, the warning itself before that.
 * [scrollable] = true when the warning has its own bounded area (the keyboard panel);
 * false inside a page that already scrolls (Settings).
 */
@Composable
internal fun PluginTrustGate(settings: KeyboardSettings, scrollable: Boolean, content: @Composable () -> Unit) {
    if (settings.pluginTrustAck) content() else PluginTrustWarning(scrollable)
}

@Composable
private fun PluginTrustWarning(scrollable: Boolean) {
    val context = LocalContext.current
    var taps by remember { mutableIntStateOf(0) }
    val compact = scrollable
    val base = if (scrollable) Modifier.fillMaxSize().verticalScroll(rememberScrollState()) else Modifier.fillMaxWidth()

    Column(
        base.clip(RoundedCornerShape(if (compact) 0.dp else 28.dp)).background(TrustBg).padding(if (compact) 14.dp else 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)
    ) {
        Icon(Icons.Rounded.Warning, null, tint = TrustRed, modifier = Modifier.size(if (compact) 32.dp else 56.dp))
        Text(
            "YOU ARE THE SECURITY",
            color = TrustRed,
            fontSize = if (compact) 26.sp else 38.sp,
            lineHeight = if (compact) 30.sp else 44.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center
        )
        Text(
            tr("Nothing stands between you and your plugins."),
            color = TrustText, fontSize = if (compact) 14.sp else 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
        )
        Text(
            tr("This fork was made for freedom. No one is stopping you: not the keyboard, and not us. That also means no one is protecting you."),
            color = TrustText, fontSize = if (compact) 12.sp else 14.sp, textAlign = TextAlign.Center
        )
        Text(
            tr("A plugin you enable can read what you type, your clipboard, microphone level, motion sensors, the current app and what music is playing, and some can go online. The keyboard shows permission prompts, a red dot and reminders, but those are warnings, not a shield. It cannot stop a plugin you chose to install."),
            color = TrustText, fontSize = if (compact) 12.sp else 14.sp, textAlign = TextAlign.Center
        )
        Text(
            tr("Real protection would mean limiting what plugins may do, and this fork exists so that nothing limits you. It works like Linux: we expect you to read what you run and to install only what you trust. It is built for developers and users who know what they are doing."),
            color = TrustText, fontSize = if (compact) 12.sp else 14.sp, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(2.dp))

        // YES: five taps, then it is saved and the warning never appears again.
        Box(
            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(50)).background(TrustRed)
                .clickable {
                    taps++
                    if (taps >= PluginTrust.TAPS_NEEDED) {
                        KeyboardPrefs.edit(context) { putBoolean(PluginTrust.PREF_KEY, true) }
                    }
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (taps == 0) tr("Yes, I understand") else tr("Yes, I understand") + "  ($taps/${PluginTrust.TAPS_NEEDED})",
                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(PluginTrust.TAPS_NEEDED) { i ->
                Box(Modifier.size(9.dp).clip(CircleShape).background(if (i < taps) TrustRed else TrustRedDark))
            }
        }
        Text(
            tr("Tap 5 times to confirm. This screen will not appear again."),
            color = TrustMuted, fontSize = 11.sp, textAlign = TextAlign.Center
        )

        // NO: sends the user to the original, official stable build.
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                .clickable {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(PluginTrust.OFFICIAL_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(50))
                    .border(1.5.dp, TrustRedDark, RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    tr("No, I want the official stable version"),
                    color = TrustText, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
                )
            }
            Text(
                tr("Opens the original Expressive Keyboard download page. This fork is not for everyone, and that is fine."),
                color = TrustMuted, fontSize = 11.sp, textAlign = TextAlign.Center
            )
        }
    }
}

private fun Modifier.height(48.dp): Modifier = this.then(Modifier.height(48.dp))
