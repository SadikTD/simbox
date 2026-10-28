package app.simbox

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.TimeZone

sealed interface Route {
    data object Home : Route
    data class Open(val chat: Chat) : Route
    data object Settings : Route
    data object Phones : Route
}

/** Set when a notification is tapped; the inbox opens that conversation. */
val OpenChat = mutableStateOf<Chat?>(null)

private const val PAGE = 300

data class SimRef(val key: String, val dev: String, val slot: Int, val carrier: String, val number: String = "")

/** Colors and names for SIMs and phones, stable across the inbox. */
class Look(val sims: List<SimRef>, private val devices: Map<String, Device>) {
    fun color(key: String): Color {
        val custom = Store.simColor(key)
        return SimColors[if (custom >= 0) custom else sims.indexOfFirst { it.key == key }.coerceAtLeast(0) % SimColors.size]
    }
    fun phone(dev: String) = devices[dev]?.title ?: "Removed phone"
    fun title(s: SimRef) = simTitle(s.key, s.slot, s.carrier)
    fun title(m: Msg) = simTitle(m.sim, m.slot, m.carrier)
}

private data class Data(
    val rows: List<Any> = emptyList(), val stats: Map<String, Pair<Int, Int>> = emptyMap(),
    val hasMore: Boolean = false, val loaded: Boolean = false,
) {
    val total get() = stats.values.sumOf { it.first }
    val unread get() = stats.values.sumOf { it.second }
}

private val kinds = listOf("all" to "All", "unread" to "Unread", "otp" to "Codes", "money" to "Money", "personal" to "Personal",
    "promo" to "Offers", "starred" to "Starred", "archived" to "Archived", "muted" to "Muted")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Inbox(onUnlink: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val v by Sync.version.collectAsState()
    val devices by Sync.devices.collectAsState()
    var route by remember { mutableStateOf<Route>(Route.Home) }
    var filter by remember { mutableStateOf(Filter()) }
    var timeline by remember { mutableStateOf(Store.timeline) }
    var limit by remember { mutableIntStateOf(PAGE) }
    var labelTick by remember { mutableIntStateOf(0) }
    var msgSheet by remember { mutableStateOf<Msg?>(null) }
    var convSheet by remember { mutableStateOf<Conv?>(null) }
    var details by remember { mutableStateOf<Msg?>(null) }
    var showLink by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    // Hoisted out of the screens so coming back from a conversation keeps the exact scroll position.
    val listState = rememberLazyListState()

    val data by produceState(Data(), v, filter, timeline, limit) {
        value = withContext(Dispatchers.IO) {
            val stats = Sync.db.simStats()
            val rows: List<Any> = if (timeline) {
                val list = Sync.db.timeline(filter, limit)
                buildList { var last = ""; list.forEach { m -> dayLabel(m.t).let { d -> if (d != last) { add(d); last = d } }; add(m) } }
            } else Sync.db.convs(filter, limit)
            Data(rows, stats, rows.count { it !is String } >= limit, true)
        }
    }
    val look = remember(devices, data.stats, labelTick) {
        val refs = devices.values.flatMap { d -> d.sims.map { SimRef("${d.id}:${it.slot}", d.id, it.slot, it.carrier, it.number) } } +
            data.stats.keys.map { k -> k.lastIndexOf(':').let { i -> SimRef(k, k.substring(0, i), k.substring(i + 1).toIntOrNull() ?: -1, "") } }
        Look(refs.distinctBy { it.key }.sortedWith(compareBy({ devices[it.dev]?.title ?: "~" }, { it.slot })), devices)
    }

    LaunchedEffect(OpenChat.value) { OpenChat.value?.let { route = Route.Open(it); OpenChat.value = null } }
    LaunchedEffect(filter, timeline) { limit = PAGE; listState.scrollToItem(0) }

    val archivedView = filter.kind == "archived"
    fun archive(text: String, apply: (Boolean) -> Unit) {
        Sync.later { apply(true) }
        scope.launch {
            snack.currentSnackbarData?.dismiss()
            if (snack.showSnackbar(text, "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) Sync.later { apply(false) }
        }
    }

    BackHandler(route != Route.Home) { route = Route.Home }
    Box(Modifier.fillMaxSize()) {
        AnimatedContent(route, transitionSpec = {
            if (targetState != Route.Home) (slideInHorizontally(tween(360)) { it } + fadeIn()) togetherWith (slideOutHorizontally(tween(360)) { -it / 4 } + fadeOut())
            else (slideInHorizontally(tween(360)) { -it / 4 } + fadeIn()) togetherWith (slideOutHorizontally(tween(360)) { it } + fadeOut())
        }, label = "nav") { r ->
            when (r) {
                Route.Home -> Home(
                    data, look, devices, filter, { filter = it }, timeline, { timeline = it; Store.timeline = it }, listState,
                    loadMore = { if (data.hasMore) limit += PAGE }, grow = { limit = maxOf(limit, it) },
                    onOpen = { route = Route.Open(it) }, onMsgMenu = { msgSheet = it }, onConvMenu = { convSheet = it },
                    onArchiveMsg = { m -> archive(if (archivedView) "Moved back to inbox" else "Message archived") { on -> Sync.db.setHidden(m.id, if (archivedView) !on else on) } },
                    onArchiveConv = { c -> archive(if (archivedView) "Moved back to inbox" else "Conversation archived") { on -> Sync.db.setChatHidden(c.last.chat, if (archivedView) !on else on) } },
                    onLink = { showLink = true }, onSettings = { route = Route.Settings }, onPhones = { route = Route.Phones },
                )
                is Route.Open -> ChatView(r.chat, look, v, { route = Route.Home }, { msgSheet = it }) { convSheet = Conv(it, 0, 0) }
                Route.Settings -> SettingsScreen({ route = Route.Home }, { route = Route.Phones }, { showLink = true }, onUnlink)
                Route.Phones -> PhonesScreen(look, { route = Route.Home }, { showLink = true }) { labelTick++ }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
    }

    if (showLink) LinkDialog { showLink = false }

    msgSheet?.let { m ->
        val who = Sync.display(m.dev, m.src)
        val muted = remember(m) { Sync.db.isMuted(m.src) }
        val code = remember(m) { otp(m.body) }
        Sheet({ msgSheet = null }, who, m.body) {
            if (code != null) SheetRow("Copy code $code") { copyText(ctx, code, "Code copied"); msgSheet = null }
            SheetRow("Copy text") { copyText(ctx, m.body); msgSheet = null }
            SheetRow("Share") { shareText(ctx, m.body); msgSheet = null }
            SheetRow(if (m.star) "Remove star" else "Star") { Sync.later { Sync.db.setStar(m.id, !m.star) }; msgSheet = null }
            SheetRow(if (m.read) "Mark as unread" else "Mark as read") { Sync.later { Sync.db.setRead(m.id, !m.read) }; msgSheet = null }
            SheetRow(if (m.hidden) "Move back to inbox" else "Archive") { Sync.later { Sync.db.setHidden(m.id, !m.hidden) }; msgSheet = null }
            SheetRow(if (muted) "Unmute $who" else "Mute $who") { Sync.later { Sync.db.setMuted(m.src, !muted) }; msgSheet = null }
            SheetRow("Details") { details = m; msgSheet = null }
        }
    }
    convSheet?.let { c ->
        val m = c.last
        val muted = remember(c) { Sync.db.isMuted(m.src) }
        Sheet({ convSheet = null }, Sync.display(m.dev, m.src), "${look.title(m)} · ${look.phone(m.dev)}") {
            SheetRow("Mark all as read") { Sync.later { Sync.db.setChatRead(m.chat, true) }; convSheet = null }
            SheetRow("Mark as unread") { Sync.later { Sync.db.setRead(m.id, false) }; convSheet = null; if (route is Route.Open) route = Route.Home }
            SheetRow(if (m.hidden) "Move back to inbox" else "Archive conversation") {
                Sync.later { Sync.db.setChatHidden(m.chat, !m.hidden) }; convSheet = null
                if (route is Route.Open) route = Route.Home
            }
            SheetRow(if (muted) "Unmute sender" else "Mute sender") { Sync.later { Sync.db.setMuted(m.src, !muted) }; convSheet = null }
            SheetRow("Copy number") { copyText(ctx, m.src); convSheet = null }
        }
    }
    details?.let { m -> DetailsSheet(m, look, devices) { details = null } }
}

// ---------- home list ----------

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun Home(
    data: Data, look: Look, devices: Map<String, Device>, filter: Filter, onFilter: (Filter) -> Unit,
    timeline: Boolean, onTimeline: (Boolean) -> Unit, state: LazyListState, loadMore: () -> Unit, grow: (Int) -> Unit,
    onOpen: (Chat) -> Unit, onMsgMenu: (Msg) -> Unit, onConvMenu: (Conv) -> Unit,
    onArchiveMsg: (Msg) -> Unit, onArchiveConv: (Conv) -> Unit,
    onLink: () -> Unit, onSettings: () -> Unit, onPhones: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val catching by Sync.catchingUp.collectAsState()
    val fetched by Sync.fetched.collectAsState()
    val error by Sync.error.collectAsState()
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    val update = remember(now) { updateAvailable() }
    var picking by remember { mutableStateOf(false) }
    var jumpTo by remember { mutableStateOf<Long?>(null) }
    val pick = { f: Filter -> haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onFilter(f) }
    val archivedView = filter.kind == "archived"

    val nearEnd by remember { derivedStateOf { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= state.layoutInfo.totalItemsCount - 8 } }
    LaunchedEffect(nearEnd, data) { if (nearEnd && data.hasMore) loadMore() }

    var top = 0
    LazyColumn(Modifier.fillMaxSize(), state) {
        val pad = Modifier.padding(horizontal = 18.dp)
        top++; item("head") {
            Row(pad.fillMaxWidth().statusBarsPadding().padding(top = 18.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Inbox", color = Ink, fontSize = 38.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp)
                    Text(buildString {
                        append(plural(data.total, "message"))
                        if (data.unread > 0) append(" · ${data.unread} unread")
                    }, color = Muted, fontSize = 13.sp)
                }
                RoundIcon(Icons.Default.Add, "Link a phone", onLink)
                Spacer(Modifier.width(10.dp))
                RoundIcon(Icons.Default.Settings, "Settings", onSettings)
            }
        }
        if (update != null) { top++; item("update") { UpdateBanner(update, pad) } }
        if (catching && (fetched > 0 || data.total == 0) && devices.isNotEmpty()) { top++; item("sync") {
            Column(pad.fillMaxWidth().padding(bottom = 14.dp).glass(18.dp).padding(14.dp)) {
                Text("Syncing your messages", color = Ink, fontWeight = FontWeight.SemiBold)
                Text(if (fetched > 0) "${"%,d".format(fetched)} downloaded so far. You can keep using the app." else "Getting things ready…", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = Violet, trackColor = Glass)
            }
        } }
        if (error != null) { top++; item("err") {
            Column(pad.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(20.dp)).background(Coral.copy(alpha = .14f))
                .border(1.dp, Coral.copy(alpha = .4f), RoundedCornerShape(20.dp)).padding(16.dp)) {
                Text("Can't reach the cloud right now", color = Ink, fontWeight = FontWeight.SemiBold)
                Text("Messages will catch up once the connection is back. ($error)", color = Muted, fontSize = 13.sp)
            }
        } }
        if (devices.isNotEmpty()) { top++; item("devs") {
            LazyRow(Modifier.padding(bottom = 16.dp), contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(devices.values.sortedBy { it.title }, key = { it.id }) { d ->
                    val unread = data.stats.filterKeys { it.startsWith(d.id + ":") }.values.sumOf { it.second }
                    DeviceCard(d, unread, filter.dev == d.id, now, { pick(filter.copy(dev = if (filter.dev == d.id) null else d.id, sim = null)) }, onPhones)
                }
            }
        } }
        top++; item("search") {
            Column(pad) {
                SearchBar(filter.query) { onFilter(filter.copy(query = it)) }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Segmented(listOf("Conversations", "All messages"), if (timeline) 1 else 0, Modifier.weight(1f)) { onTimeline(it == 1) }
                    if (timeline) { Spacer(Modifier.width(10.dp)); RoundIcon(Icons.Default.DateRange, "Jump to date") { picking = true } }
                    if (data.unread > 0) { Spacer(Modifier.width(10.dp)); RoundIcon(Icons.Default.Check, "Mark all as read") { Sync.later { Sync.db.markAllRead() } } }
                }
            }
        }
        if (devices.isNotEmpty() || data.total > 0) { top++; item("sims") {
            LazyRow(Modifier.padding(top = 14.dp), contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Chip("All SIMs", null, Ink, filter.sim == null && filter.dev == null, 0, { pick(filter.copy(sim = null, dev = null)) }) }
                items(look.sims, key = { it.key }) { s ->
                    Chip(look.title(s), look.phone(s.dev), look.color(s.key), filter.sim == s.key, data.stats[s.key]?.second ?: 0,
                        { pick(filter.copy(sim = if (filter.sim == s.key) null else s.key, dev = null)) }, onPhones)
                }
            }
        }
        top++; item("kinds") {
            LazyRow(Modifier.padding(top = 8.dp, bottom = 6.dp), contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(kinds, key = { it.first }) { (k, label) -> KindChip(label, filter.kind == k) { pick(filter.copy(kind = if (filter.kind == k && k != "all") "all" else k)) } }
            }
        } }
        if (!data.loaded || (data.rows.isEmpty() && catching && devices.isNotEmpty() && filter == Filter())) items(6) { Box(pad) { ShimmerCard() } }
        else if (data.rows.isEmpty()) item("empty") { Box(pad) { Empty(devices.isEmpty(), filter, onLink) } }

        if (timeline) {
            for (r in data.rows) when (r) {
                is String -> stickyHeader("h$r") { DayHeader(r) }
                is Msg -> item(r.id) {
                    Box(Modifier.animateItem().then(pad)) {
                        Swipeable(r.read, archivedView, { Sync.later { Sync.db.setRead(r.id, !r.read) } }, { onArchiveMsg(r) }) {
                            MsgCard(r, look, { onOpen(r.chat) }, { onMsgMenu(r) })
                        }
                    }
                }
            }
        } else items(data.rows.filterIsInstance<Conv>(), key = { "${it.last.dev}|${it.last.slot}|${it.last.src}" }) { c ->
            Box(Modifier.animateItem().then(pad)) {
                Swipeable(c.unread == 0, archivedView,
                    { Sync.later { if (c.unread > 0) Sync.db.setChatRead(c.last.chat, true) else Sync.db.setRead(c.last.id, false) } },
                    { onArchiveConv(c) }) {
                    ConvCard(c, look, { onOpen(c.last.chat) }, { onConvMenu(c) })
                }
            }
        }
        item("end") {
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 12.dp, bottom = 88.dp), contentAlignment = Alignment.Center) {
                if (data.hasMore) CircularProgressIndicator(Modifier.size(22.dp), color = Violet, strokeWidth = 2.dp)
                else if (data.rows.size > 8) Text("That's everything", color = Muted, fontSize = 12.sp)
            }
        }
    }

    // jump to a date: make sure enough rows are loaded, then scroll to that day's header
    LaunchedEffect(jumpTo, data) {
        val end = jumpTo ?: return@LaunchedEffect
        val idx = data.rows.indexOfFirst { it is Msg && it.t <= end }
        if (idx >= 0) { state.scrollToItem(top + maxOf(idx - 1, 0)); jumpTo = null }
        else if (!data.hasMore) jumpTo = null
    }
    if (picking) {
        val dp = rememberDatePickerState(selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= System.currentTimeMillis()
        })
        DatePickerDialog(onDismissRequest = { picking = false }, confirmButton = {
            TextButton({
                picking = false
                val sel = dp.selectedDateMillis ?: return@TextButton
                val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = sel }
                val end = Calendar.getInstance().apply {
                    set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 23, 59, 59); set(Calendar.MILLISECOND, 999)
                }.timeInMillis
                Sync.io.execute {
                    val n = Sync.db.countNewer(filter, end + 1)
                    android.os.Handler(android.os.Looper.getMainLooper()).post { grow(n + PAGE); jumpTo = end }
                }
            }) { Text("Go", color = Violet) }
        }, dismissButton = { TextButton({ picking = false }) { Text("Cancel", color = Muted) } },
            colors = DatePickerDefaults.colors(containerColor = Panel)) {
            DatePicker(dp, colors = DatePickerDefaults.colors(containerColor = Panel, selectedDayContainerColor = Violet, todayDateBorderColor = Violet))
        }
    }
}

@Composable
private fun DayHeader(text: String) = Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
    Text(text, Modifier.clip(CircleShape).background(Panel.copy(alpha = .92f)).border(1.dp, Line, CircleShape).padding(horizontal = 14.dp, vertical = 6.dp),
        color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun UpdateBanner(u: Pair<String, String>, modifier: Modifier) {
    val ctx = LocalContext.current
    Row(modifier.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(18.dp))
        .background(Brush.linearGradient(listOf(Violet.copy(alpha = .3f), Pink.copy(alpha = .2f))))
        .border(1.dp, Violet.copy(alpha = .5f), RoundedCornerShape(18.dp)).clickable { openUrl(ctx, u.second) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Simbox ${u.first} is available", color = Ink, fontWeight = FontWeight.SemiBold)
            Text("Tap to download, then open the file to update.", color = Muted, fontSize = 13.sp)
        }
        Text("Update", color = Ink, fontWeight = FontWeight.Bold)
    }
}

// ---------- swipe actions ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Swipeable(read: Boolean, archivedView: Boolean, onToggleRead: () -> Unit, onArchive: () -> Unit, content: @Composable () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val state = rememberSwipeToDismissBoxState()
    LaunchedEffect(state.currentValue) {
        when (state.currentValue) {
            SwipeToDismissBoxValue.StartToEnd -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onToggleRead(); state.snapTo(SwipeToDismissBoxValue.Settled) }
            SwipeToDismissBoxValue.EndToStart -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onArchive(); state.snapTo(SwipeToDismissBoxValue.Settled) }
            else -> Unit
        }
    }
    SwipeToDismissBox(state, backgroundContent = {
        val (c, text, align) = when (state.dismissDirection) {
            SwipeToDismissBoxValue.StartToEnd -> Triple(Violet, if (read) "Mark unread" else "Mark read", Alignment.CenterStart)
            SwipeToDismissBoxValue.EndToStart -> Triple(if (archivedView) Emerald else Coral, if (archivedView) "Unarchive" else "Archive", Alignment.CenterEnd)
            else -> Triple(Color.Transparent, "", Alignment.Center)
        }
        Box(Modifier.fillMaxSize().padding(vertical = 5.dp).clip(RoundedCornerShape(22.dp)).background(c.copy(alpha = .35f)).padding(horizontal = 24.dp), contentAlignment = align) {
            Text(text, color = Ink, fontWeight = FontWeight.Bold)
        }
    }) { content() }
}

// ---------- cards ----------

@Composable
private fun DeviceCard(d: Device, unread: Int, selected: Boolean, now: Long, onClick: () -> Unit, onLong: () -> Unit) {
    val online = d.online(now)
    val bat by animateFloatAsState(d.bat / 100f, tween(900), label = "bat")
    val border by animateColorAsState(if (selected) Violet else Line, label = "border")
    val batColor = if (d.bat < 20) Coral else Emerald
    Column(Modifier.width(176.dp).clip(RoundedCornerShape(22.dp)).background(Glass)
        .border(if (selected) 2.dp else 1.dp, border, RoundedCornerShape(22.dp)).longClick(onClick, onLong).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pulse(Emerald, online)
            Spacer(Modifier.width(8.dp))
            Text(d.title, Modifier.weight(1f), color = Ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            CountBadge(unread)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${d.bat}%", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Black)
            if (d.chg) Text("  charging", color = Emerald, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Glass)) {
            Box(Modifier.fillMaxWidth(bat).fillMaxHeight().background(Brush.horizontalGradient(listOf(batColor.copy(alpha = .6f), batColor)), CircleShape))
        }
        Spacer(Modifier.height(8.dp))
        Text(if (online) "Seen ${ago(d.seen, now)}" else "Offline · ${ago(d.seen, now)}", color = if (online) Muted else Amber, fontSize = 12.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.longClick(onClick: () -> Unit, onLong: (() -> Unit)?) = combinedClickable(onClick = onClick, onLongClick = onLong)

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit) =
    Row(Modifier.fillMaxWidth().glass(18.dp).padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Search, null, tint = Muted)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search messages, numbers, names", color = Muted, fontSize = 16.sp, maxLines = 1)
            BasicTextField(query, onQuery, Modifier.fillMaxWidth(), singleLine = true,
                textStyle = TextStyle(color = Ink, fontSize = 16.sp), cursorBrush = SolidColor(Violet))
        }
        if (query.isNotEmpty()) Icon(Icons.Default.Close, "Clear search", Modifier.clickable { onQuery("") }, tint = Muted)
    }

@Composable
private fun Chip(text: String, sub: String?, color: Color, selected: Boolean, unread: Int, onClick: () -> Unit, onLong: (() -> Unit)? = null) {
    val bg by animateColorAsState(if (selected) color else Glass, spring(stiffness = Spring.StiffnessMediumLow), label = "bg")
    val fg by animateColorAsState(if (selected) Bg else Ink, label = "fg")
    val s by animateFloatAsState(if (selected) 1.04f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "s")
    Row(Modifier.graphicsLayer { scaleX = s; scaleY = s }.clip(CircleShape).background(bg)
        .border(1.dp, if (selected) Color.Transparent else Line, CircleShape)
        .longClick(onClick, onLong).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(if (selected) fg else color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (sub != null) Text("  $sub", color = fg.copy(alpha = .65f), fontSize = 12.sp)
        if (unread > 0) { Spacer(Modifier.width(8.dp)); CountBadge(unread, if (selected) Bg else color) }
    }
}

@Composable
private fun KindChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Violet.copy(alpha = .25f) else Color.Transparent, label = "kbg")
    val fg by animateColorAsState(if (selected) Ink else Muted, label = "kfg")
    Text(text, Modifier.clip(CircleShape).background(bg).border(1.dp, if (selected) Violet.copy(alpha = .6f) else Line, CircleShape)
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp), color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ConvCard(c: Conv, look: Look, onClick: () -> Unit, onLong: () -> Unit) {
    val m = c.last
    val color = look.color(m.sim)
    val unread = c.unread > 0
    val code = remember(m.body) { otp(m.body) }
    val who = Sync.display(m.dev, m.src)
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(22.dp)).background(if (unread) Color(0x1FFFFFFF) else Glass)
        .border(1.dp, if (unread) color.copy(alpha = .45f) else Line, RoundedCornerShape(22.dp)).longClick(onClick, onLong).padding(14.dp)) {
        Avatar(who, color)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(who, Modifier.weight(1f), color = Ink, fontWeight = if (unread) FontWeight.Black else FontWeight.SemiBold,
                    fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(shortTime(m.t), color = if (unread) Ink else Muted, fontSize = 12.sp, fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal)
            }
            Spacer(Modifier.height(3.dp))
            Text(m.body, color = Ink.copy(alpha = if (unread) .9f else .65f), fontSize = 14.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SimTag("${look.title(m)} · ${look.phone(m.dev)}", color, Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f).width(8.dp))
                when {
                    code != null && System.currentTimeMillis() - m.t < 3_600_000 -> OtpPill(code, color)
                    m.amt != null -> AmountPill(m.amt)
                }
                if (unread) { Spacer(Modifier.width(8.dp)); CountBadge(c.unread) }
            }
        }
    }
}

@Composable
private fun MsgCard(m: Msg, look: Look, onClick: () -> Unit, onLong: () -> Unit) {
    val c = look.color(m.sim)
    val code = remember(m.body) { otp(m.body) }
    val who = Sync.display(m.dev, m.src)
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(22.dp)).background(if (!m.read) Color(0x1FFFFFFF) else Glass)
        .border(1.dp, if (!m.read) c.copy(alpha = .45f) else Line, RoundedCornerShape(22.dp)).longClick(onClick, onLong).padding(14.dp)) {
        Avatar(who, c)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!m.read) { Box(Modifier.size(8.dp).background(c, CircleShape)); Spacer(Modifier.width(6.dp)) }
                Text(who, Modifier.weight(1f), color = Ink, fontWeight = if (!m.read) FontWeight.Black else FontWeight.SemiBold,
                    fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (m.star) { Icon(Icons.Default.Star, "Starred", Modifier.size(16.dp), tint = Amber); Spacer(Modifier.width(6.dp)) }
                Text(clock(m.t), color = Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(3.dp))
            Text(m.body, color = Ink.copy(alpha = .72f), fontSize = 14.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SimTag(if (code != null || m.amt != null) look.title(m) else "${look.title(m)} · ${look.phone(m.dev)}", c, Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f).width(8.dp))
                when { code != null -> OtpPill(code, c); m.amt != null -> AmountPill(m.amt) }
            }
        }
    }
}

@Composable
fun SimTag(text: String, c: Color, modifier: Modifier = Modifier) =
    Row(modifier.clip(CircleShape).background(c.copy(alpha = .14f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(c, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, color = c, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

@Composable
private fun Empty(noPhones: Boolean, f: Filter, onLink: () -> Unit) = Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Orbit()
    Spacer(Modifier.height(14.dp))
    val (title, sub) = when {
        noPhones -> "Link your first phone" to "Install Simbox on a phone with SIM cards, tap Link to my main phone, and scan the code."
        f.query.isNotBlank() -> "No results for \"${f.query}\"" to "Try a different word, number or contact name."
        f.kind == "unread" -> "All caught up" to "No unread messages."
        f.kind == "archived" -> "Nothing archived" to "Swipe a message left to archive it."
        f.kind == "starred" -> "No starred messages" to "Press and hold a message, then tap Star."
        f.kind == "muted" -> "No muted senders" to "Mute noisy senders from the message menu."
        else -> "Nothing here yet" to "Try another SIM or filter."
    }
    Text(title, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    Spacer(Modifier.height(4.dp))
    Text(sub, color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
    if (noPhones) { Spacer(Modifier.height(18.dp)); GradButton("Link a phone", onClick = onLink) }
}

// ---------- one conversation ----------

@Composable
private fun ChatView(chat: Chat, look: Look, v: Int, onBack: () -> Unit, onMsgMenu: (Msg) -> Unit, onMenu: (Msg) -> Unit) {
    val ctx = LocalContext.current
    val list by produceState(emptyList<Msg>(), v, chat) { value = withContext(Dispatchers.IO) { Sync.db.chat(chat) } }
    LaunchedEffect(chat) { Sync.later { Sync.db.setChatRead(chat, true) } }
    LaunchedEffect(list.size) { Notify.cancel(ctx, list.take(50).map { it.id }) }
    val c = look.color(chat.sim)
    val name = Sync.nameOf(chat.dev, chat.src)
    val rows = remember(list) {
        buildList<Any> { list.forEachIndexed { i, m -> add(m); val d = dayLabel(m.t); if (i == list.lastIndex || dayLabel(list[i + 1].t) != d) add(d) } }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
            Spacer(Modifier.width(12.dp))
            Avatar(name ?: chat.src, c, 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name ?: chat.src, color = Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(name?.let { chat.src }, "${simTitle(chat.sim, chat.slot, list.firstOrNull()?.carrier.orEmpty())} · ${look.phone(chat.dev)}").joinToString(" · "),
                    color = c, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            list.firstOrNull()?.let { m -> RoundIcon(Icons.Default.MoreVert, "Conversation options") { onMenu(m) } }
        }
        LazyColumn(Modifier.weight(1f), reverseLayout = true, contentPadding = PaddingValues(horizontal = 16.dp)) {
            item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
            items(rows, key = { if (it is Msg) it.id else "d$it" }) { r ->
                if (r is Msg) Bubble(r, c, Modifier.animateItem()) { onMsgMenu(r) } else DayHeader(r as String)
            }
        }
    }
}

@Composable
private fun Bubble(m: Msg, c: Color, modifier: Modifier, onLong: () -> Unit) {
    val shape = RoundedCornerShape(topStart = 6.dp, topEnd = 22.dp, bottomEnd = 22.dp, bottomStart = 22.dp)
    val code = remember(m.body) { otp(m.body) }
    Column(modifier.padding(vertical = 5.dp).fillMaxWidth(.9f).clip(shape).background(Glass)
        .border(1.dp, Brush.linearGradient(listOf(c.copy(alpha = .6f), Line)), shape).longClick({}, onLong).padding(14.dp)) {
        SelectionContainer { Text(remember(m.body) { linkified(m.body, Cyan) }, color = Ink, fontSize = 15.sp, lineHeight = 21.sp) }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (m.star) { Icon(Icons.Default.Star, "Starred", Modifier.size(14.dp), tint = Amber); Spacer(Modifier.width(4.dp)) }
            Text(clock(m.t), Modifier.weight(1f), color = Muted, fontSize = 11.sp)
            if (m.amt != null) { AmountPill(m.amt); Spacer(Modifier.width(6.dp)) }
            if (code != null) OtpPill(code, c)
        }
    }
}

// ---------- sheets & dialogs ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(onDismiss: () -> Unit, title: String, sub: String?, content: @Composable () -> Unit) =
    ModalBottomSheet(onDismiss, containerColor = Panel, scrimColor = Color(0x99000000)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(title, Modifier.padding(horizontal = 24.dp), color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, Modifier.padding(horizontal = 24.dp, vertical = 4.dp), color = Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }

@Composable
private fun DetailsSheet(m: Msg, look: Look, devices: Map<String, Device>, onDismiss: () -> Unit) {
    val sim = devices[m.dev]?.sims?.firstOrNull { it.slot == m.slot }
    Sheet(onDismiss, "Message details", null) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            listOfNotNull(
                "From" to (Sync.nameOf(m.dev, m.src)?.let { "$it (${m.src})" } ?: m.src),
                "Phone" to look.phone(m.dev),
                "SIM" to "${look.title(m)} · slot ${m.slot + 1}${if (m.carrier.isNotBlank()) " · ${m.carrier}" else ""}",
                sim?.number?.takeIf { it.isNotBlank() }?.let { "SIM number" to it },
                "Sent" to fullTime(m.t),
                "Reached Simbox" to (if (m.u > 0) fullTime(m.u) else "Unknown"),
                "Type" to (kinds.firstOrNull { it.first == m.cat }?.second ?: "Other"),
            ).forEach { (k, value) ->
                Row(Modifier.padding(vertical = 8.dp)) {
                    Text(k, Modifier.width(120.dp), color = Muted, fontSize = 14.sp)
                    Text(value, color = Ink, fontSize = 14.sp)
                }
            }
        }
    }
}

/** QR for linking a phone; flips to a success state when the new phone shows up. */
@Composable
fun LinkDialog(onDismiss: () -> Unit) {
    var linked by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { Sync.linked.collect { linked = it; delay(2200); onDismiss() } }
    Dialog(onDismiss) {
        Column(Modifier.fillMaxWidth().panel().verticalScroll(rememberScrollState()).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedContent(linked, label = "link") { name ->
                if (name == null) Column {
                    Text("Link a phone", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(6.dp))
                    Text("On the other phone, open Simbox, tap Link to my main phone, and point it at this code.", color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(18.dp))
                    Qr(Store.pairing(), Modifier.fillMaxWidth())
                    Spacer(Modifier.height(14.dp))
                    Text("This code unlocks your messages. Only show it to your own phones.", color = Coral, fontSize = 12.sp)
                } else Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    var shown by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { shown = true }
                    AnimatedVisibility(shown, enter = scaleIn(spring(Spring.DampingRatioMediumBouncy)) + fadeIn()) {
                        Box(Modifier.size(84.dp).background(Brush.linearGradient(listOf(Emerald, Cyan)), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Check, null, Modifier.size(44.dp), tint = Bg)
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Text("$name is linked", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                    Text("Its messages are on the way.", color = Muted, fontSize = 14.sp)
                }
            }
        }
    }
}
