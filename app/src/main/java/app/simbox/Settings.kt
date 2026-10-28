package app.simbox

import android.Manifest
import android.app.KeyguardManager
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.firestore.SetOptions
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Compact time for lists: "4:12 PM", "Yesterday", "12 Oct", "12 Oct 24". */
fun shortTime(t: Long): String {
    val now = Calendar.getInstance(); val c = Calendar.getInstance().apply { timeInMillis = t }
    return when {
        DateUtils.isToday(t) -> clock(t)
        DateUtils.isToday(t + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
        c.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(t))
        else -> SimpleDateFormat("d MMM yy", Locale.getDefault()).format(Date(t))
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) =
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
        Spacer(Modifier.width(14.dp))
        Text(title, color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Black)
    }

@Composable
fun SettingsScreen(onBack: () -> Unit, onPhones: () -> Unit, onLink: () -> Unit, onUnlink: () -> Unit) {
    val ctx = LocalContext.current
    val devices by Sync.devices.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    val contactsOk = remember(tick, ResumeTick.intValue) { granted(ctx, Manifest.permission.READ_CONTACTS) }
    val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++; Sync.io.execute { Sync.loadOwnContacts() } }
    var checking by remember { mutableStateOf(false) }
    var resetConfirm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        TopBar("Settings", onBack)

        SectionTitle("Phones")
        Card {
            NavRow("Manage phones", "${plural(devices.size, "phone")} linked · rename, recolor SIMs, remove", onClick = onPhones)
            Divider()
            NavRow("Link a phone", "Show the pairing code", onClick = onLink)
        }

        SectionTitle("Privacy & security")
        Card {
            ToggleRow("App lock", "Ask for your fingerprint or screen lock when opening Simbox", remember(tick) { Store.appLock }) { on ->
                if (on && !ctx.getSystemService(KeyguardManager::class.java).isDeviceSecure)
                    Toast.makeText(ctx, "Set a screen lock on this phone first", Toast.LENGTH_LONG).show()
                else { Store.appLock = on; tick++ }
            }
            Divider()
            ToggleRow("Hide messages on lock screen", "Notifications only say which SIM got a message until you unlock", remember(tick) { Store.hideOnLock }) {
                Store.hideOnLock = it; tick++
            }
            if (!contactsOk) {
                Divider()
                NavRow("Show names from this phone's contacts", "Also matches senders against contacts saved here") { askContacts.launch(Manifest.permission.READ_CONTACTS) }
            }
        }

        SectionTitle("Cloud copy")
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("Keep messages in the cloud for", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text("Every message stays on this phone forever. The encrypted cloud copy is only needed to move messages here.",
                    color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(12.dp))
                val opts = listOf(7, 30, 90, 0)
                Segmented(listOf("7 days", "30 days", "90 days", "Forever"), opts.indexOf(remember(tick) { Store.retentionDays }).coerceAtLeast(1)) {
                    Store.retentionDays = opts[it]; Store.lastCleanup = 0; tick++
                }
            }
        }

        SectionTitle("Keep it running")
        KeepAliveCard()

        SectionTitle("About")
        Card {
            val update = remember(tick) { updateAvailable() }
            NavRow(if (update != null) "Update to ${update.first}" else if (checking) "Checking…" else "Check for updates",
                "You have version ${BuildConfig.VERSION_NAME}", if (update != null) Violet else Ink) {
                if (update != null) openUrl(ctx, update.second)
                else { checking = true; Sync.io.execute { checkUpdate(force = true); android.os.Handler(android.os.Looper.getMainLooper()).post { checking = false; tick++
                    if (updateAvailable() == null) Toast.makeText(ctx, "You're on the latest version", Toast.LENGTH_SHORT).show() } } }
            }
            Divider()
            NavRow("Source code", "github.com/$REPO") { openUrl(ctx, "https://github.com/$REPO") }
        }

        SectionTitle("Danger zone")
        Card {
            NavRow(if (resetConfirm) "Tap again to reset pairing" else "Reset pairing",
                "Makes a new pairing code. Every other phone stops sending until you link it again.", Coral) {
                if (!resetConfirm) { resetConfirm = true; return@NavRow }
                Sync.io.execute { devices.keys.forEach { runCatching { devsRef().document(it).set(mapOf("removed" to true), SetOptions.merge()) } } }
                Sync.stop(); Store.create(); Store.syncCursor = System.currentTimeMillis(); Store.set("known", null)
                Sync.start(ctx); resetConfirm = false; onLink()
            }
        }
        Spacer(Modifier.height(14.dp))
        UnlinkButton(onUnlink)
        Spacer(Modifier.navigationBarsPadding().height(32.dp))
    }
}

@Composable
fun PhonesScreen(look: Look, onBack: () -> Unit, onLink: () -> Unit, onChanged: () -> Unit) {
    val devices by Sync.devices.collectAsState()
    var renaming by remember { mutableStateOf<Pair<String, String>?>(null) } // (kind:key, current)
    var removing by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val now = System.currentTimeMillis()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        TopBar("Phones", onBack)
        if (devices.isEmpty()) Text("No phones linked yet.", Modifier.padding(vertical = 20.dp), color = Muted)
        devices.values.sortedBy { it.title }.forEach { d ->
            Spacer(Modifier.height(12.dp))
            Card {
                Row(Modifier.fillMaxWidth().clickable { renaming = "dev:${d.id}" to d.title }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pulse(Emerald, d.online(now))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.title, color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("${d.bat}%${if (d.chg) " charging" else ""} · seen ${ago(d.seen, now)}${if (d.version.isNotBlank()) " · v${d.version}" else ""}",
                            color = Muted, fontSize = 13.sp)
                    }
                    Text("Rename", color = Violet, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                d.sims.sortedBy { it.slot }.forEach { s ->
                    val key = "${d.id}:${s.slot}"
                    Divider()
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(Modifier.fillMaxWidth().clickable { renaming = "sim:$key" to (Store.label(key) ?: "") }, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(remember(tick) { simTitle(key, s.slot, s.carrier) }, color = Ink, fontWeight = FontWeight.SemiBold)
                                Text(listOf("Slot ${s.slot + 1}", s.carrier, s.number).filter { it.isNotBlank() }.joinToString(" · "), color = Muted, fontSize = 13.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("Rename", color = Violet, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(12.dp))
                        val current = remember(tick, look) { look.color(key) }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SimColors.forEachIndexed { i, c ->
                                Box(Modifier.size(30.dp).clip(CircleShape).background(c).then(if (c == current) Modifier.border(3.dp, Ink, CircleShape) else Modifier)
                                    .clickable { Store.setSimColor(key, i); tick++; onChanged() }, contentAlignment = Alignment.Center) {
                                    if (c == current) Icon(Icons.Default.Check, "Selected", Modifier.size(16.dp), tint = Bg)
                                }
                            }
                        }
                    }
                }
                Divider()
                NavRow(if (removing == d.id) "Tap again to remove ${d.title}" else "Remove this phone",
                    "It stops sending messages. Messages already here stay.", Coral) {
                    if (removing != d.id) { removing = d.id; return@NavRow }
                    Sync.io.execute { runCatching { devsRef().document(d.id).set(mapOf("removed" to true), SetOptions.merge()) } }
                    removing = null
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        GradButton("Link another phone", Modifier.fillMaxWidth(), onLink)
        Spacer(Modifier.navigationBarsPadding().height(32.dp))
    }

    renaming?.let { (k, cur) ->
        var text by remember(k) { mutableStateOf(cur) }
        androidx.compose.ui.window.Dialog({ renaming = null }) {
            Column(Modifier.fillMaxWidth().panel().padding(22.dp)) {
                Text(if (k.startsWith("dev:")) "Name this phone" else "Name this SIM", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(14.dp))
                Field(text, { text = it }, placeholder = if (k.startsWith("dev:")) "e.g. Office phone" else "e.g. Bank SIM")
                Spacer(Modifier.height(16.dp))
                Row {
                    GlassButton("Cancel", Modifier.weight(1f)) { renaming = null }
                    Spacer(Modifier.width(10.dp))
                    GradButton("Save", Modifier.weight(1f)) {
                        if (k.startsWith("dev:")) Store.setDeviceLabel(k.removePrefix("dev:"), text) else Store.setLabel(k.removePrefix("sim:"), text)
                        Sync.devices.value = Sync.devices.value.toMap()
                        tick++; onChanged(); Sync.bump(); renaming = null
                    }
                }
            }
        }
    }
}
