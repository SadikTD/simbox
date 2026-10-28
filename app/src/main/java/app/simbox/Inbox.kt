package app.simbox

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MenuDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.Calendar
import java.util.TimeZone

sealed interface Route {
    data object Home : Route
    data object Search : Route
    data class Open(val chat: Chat) : Route
    data object Settings : Route
    data object Phones : Route
}

/** Set when a notification is tapped; the inbox opens that conversation. */
val OpenChat = mutableStateOf<Chat?>(null)

private const val PAGE = 300
private val Gutter = 20.dp

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
    var stack by remember { mutableStateOf(listOf<Route>(Route.Home)) }
    val route = stack.last()
    fun go(r: Route) { stack = stack + r }
    fun back() { if (stack.size > 1) stack = stack.dropLast(1) }
    var filter by remember { mutableStateOf(Filter()) }
    var timeline by remember { mutableStateOf(Store.timeline) }
    var limit by remember { mutableIntStateOf(PAGE) }
    var labelTick by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var msgSheet by remember { mutableStateOf<Msg?>(null) }
    var convSheet by remember { mutableStateOf<Conv?>(null) }
    var details by remember { mutableStateOf<Msg?>(null) }
    var showLink by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    // Hoisted out of the screens so coming back from a conversation keeps the exact scroll position.
    val listState = rememberLazyListState()
    val searchState = rememberLazyListState()

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

    LaunchedEffect(OpenChat.value) { OpenChat.value?.let { stack = listOf(Route.Home, Route.Open(it)); OpenChat.value = null } }
    LaunchedEffect(filter, timeline) { limit = PAGE; listState.scrollToItem(0) }

    val archivedView = filter.kind == "archived"
    fun archive(text: String, apply: (Boolean) -> Unit) {
        Sync.later { apply(true) }
        scope.launch {
            snack.currentSnackbarData?.dismiss()
            if (snack.showSnackbar(text, "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) Sync.later { apply(false) }
        }
    }

    BackHandler(stack.size > 1) { back() }
    Box(Modifier.fillMaxSize()) {
        AnimatedContent(stack, transitionSpec = {
            val forward = targetState.size >= initialState.size
            if (forward) (slideInHorizontally(tween(320)) { it / 3 } + fadeIn(tween(220))) togetherWith fadeOut(tween(160))
            else fadeIn(tween(220)) togetherWith (slideOutHorizontally(tween(320)) { it / 3 } + fadeOut(tween(200)))
        }, contentKey = { it.last() }, label = "nav") { s ->
            when (val r = s.last()) {
                Route.Home -> Home(
                    data, look, devices, filter, { filter = it }, timeline, { timeline = it; Store.timeline = it }, listState,
                    loadMore = { if (data.hasMore) limit += PAGE }, grow = { limit = maxOf(limit, it) },
                    onOpen = { go(Route.Open(it)) }, onMsgMenu = { msgSheet = it }, onConvMenu = { convSheet = it },
                    onArchiveMsg = { m -> archive(if (archivedView) "Moved back to inbox" else "Message archived") { on -> Sync.db.setHidden(m.id, if (archivedView) !on else on) } },
                    onArchiveConv = { c -> archive(if (archivedView) "Moved back to inbox" else "Conversation archived") { on -> Sync.db.setChatHidden(c.last.chat, if (archivedView) !on else on) } },
                    onSearch = { go(Route.Search) }, onLink = { showLink = true }, onSettings = { go(Route.Settings) }, onPhones = { go(Route.Phones) },
                )
                Route.Search -> SearchScreen(query, { query = it }, look, searchState, ::back) { go(Route.Open(it)) }
                is Route.Open -> ChatView(r.chat, look, v, ::back, { msgSheet = it }) { convSheet = Conv(it, 0, 0) }
                Route.Settings -> SettingsScreen(::back, { go(Route.Phones) }, { showLink = true }, onUnlink)
                Route.Phones -> PhonesScreen(look, ::back, { showLink = true }) { labelTick++ }
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
            SheetRow("Mark as unread") { Sync.later { Sync.db.setRead(m.id, false) }; convSheet = null; if (route is Route.Open) back() }
            SheetRow(if (m.hidden) "Move back to inbox" else "Archive conversation") {
                Sync.later { Sync.db.setChatHidden(m.chat, !m.hidden) }; convSheet = null
                if (route is Route.Open) back()
            }
            SheetRow(if (muted) "Unmute sender" else "Mute sender") { Sync.later { Sync.db.setMuted(m.src, !muted) }; convSheet = null }
            SheetRow("Copy number") { copyText(ctx, m.src); convSheet = null }
        }
    }
    details?.let { m -> DetailsSheet(m, look, devices) { details = null } }
}

// ---------- home ----------

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun Home(
    data: Data, look: Look, devices: Map<String, Device>, filter: Filter, onFilter: (Filter) -> Unit,
    timeline: Boolean, onTimeline: (Boolean) -> Unit, state: LazyListState, loadMore: () -> Unit, grow: (Int) -> Unit,
    onOpen: (Chat) -> Unit, onMsgMenu: (Msg) -> Unit, onConvMenu: (Conv) -> Unit,
    onArchiveMsg: (Msg) -> Unit, onArchiveConv: (Conv) -> Unit,
    onSearch: () -> Unit, onLink: () -> Unit, onSettings: () -> Unit, onPhones: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val catching by Sync.catchingUp.collectAsState()
    val fetched by Sync.fetched.collectAsState()
    val error by Sync.error.collectAsState()
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    val update = remember(now) { updateAvailable() }
    var picking by remember { mutableStateOf(false) }
    var jumpTo by remember { mutableStateOf<Long?>(null) }
    var simSheet by remember { mutableStateOf(false) }
    val pick = { f: Filter -> haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onFilter(f) }
    val archivedView = filter.kind == "archived"

    val nearEnd by remember { derivedStateOf { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= state.layoutInfo.totalItemsCount - 8 } }
    LaunchedEffect(nearEnd, data) { if (nearEnd && data.hasMore) loadMore() }

    val menu: @Composable () -> Unit = {
        OverflowMenu(timeline, data.unread > 0, onTimeline, { picking = true }, { Sync.later { Sync.db.markAllRead() } }, onLink, onSettings)
    }

    // the phone strip arrives after the first frame; if we're at the top, keep it in view
    LaunchedEffect(devices.isNotEmpty()) { if (state.firstVisibleItemIndex <= 1) state.scrollToItem(0) }
    var top = 0
    Column(Modifier.fillMaxSize()) {
        // fixed title bar: the list scrolls under it, the filter row pins right below it
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = Gutter, end = 4.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Inbox", color = Ink, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, lineHeight = 38.sp)
                Text(if (data.unread > 0) "${"%,d".format(data.unread)} unread of ${"%,d".format(data.total)}" else "${"%,d".format(data.total)} messages, all read",
                    color = Muted, fontSize = 14.sp)
            }
            RoundIcon(Icons.Default.Search, "Search", onClick = onSearch)
            menu()
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state) {
            if (devices.isNotEmpty()) { top++; item("phones") { PhoneStrip(devices, data.stats, now, onPhones) } }
            top++; item("search") { SearchField(onSearch) }
            if (update != null) { top++; item("update") { Notice(Violet, "Simbox ${update.first} is available", "Tap to download the update.", update) } }
            if (catching && (fetched > 0 || data.total == 0) && devices.isNotEmpty()) { top++; item("sync") {
                Column(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 6.dp)) {
                    Text(if (fetched > 0) "Syncing history · ${"%,d".format(fetched)} so far" else "Syncing history", color = Soft, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape), color = Violet, trackColor = Glass)
                }
            } }
            if (error != null) { top++; item("err") { Notice(Coral, "Offline", "New messages will arrive when the connection is back.", null) } }
            if (devices.isNotEmpty() || data.total > 0) { top++; stickyHeader("filters") {
                FilterBar(filter, look, data, true, { simSheet = true }) { k -> pick(filter.copy(kind = if (filter.kind == k && k != "all") "all" else k)) }
            } }

            if (!data.loaded || (data.rows.isEmpty() && catching && devices.isNotEmpty() && filter == Filter())) items(8) { SkeletonRow() }
            else if (data.rows.isEmpty()) item("empty") { Empty(devices.isEmpty(), filter, onLink) }

            if (timeline) {
                for (r in data.rows) when (r) {
                    is String -> item("h$r") { DayLabel(r) }
                    is Msg -> item(r.id) {
                        Box(Modifier.animateItem()) {
                            Swipeable(r.read, archivedView, { Sync.later { Sync.db.setRead(r.id, !r.read) } }, { onArchiveMsg(r) }) {
                                MsgRow(r, look, { onOpen(r.chat) }, { onMsgMenu(r) })
                            }
                        }
                    }
                }
            } else items(data.rows.filterIsInstance<Conv>(), key = { "${it.last.dev}|${it.last.slot}|${it.last.src}" }) { c ->
                Box(Modifier.animateItem()) {
                    Swipeable(c.unread == 0, archivedView,
                        { Sync.later { if (c.unread > 0) Sync.db.setChatRead(c.last.chat, true) else Sync.db.setRead(c.last.id, false) } },
                        { onArchiveConv(c) }) {
                        ConvRow(c, look, { onOpen(c.last.chat) }, { onConvMenu(c) })
                    }
                }
            }
            item("end") {
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 16.dp, bottom = 96.dp), contentAlignment = Alignment.Center) {
                    if (data.hasMore) CircularProgressIndicator(Modifier.size(20.dp), color = Violet, strokeWidth = 2.dp)
                    else if (data.rows.size > 8) Text("You're all caught up", color = Muted, fontSize = 12.sp)
                }
            }
        }
    }

    if (simSheet) SimSheet(filter, look, data.stats, { simSheet = false }) { f -> simSheet = false; pick(f) }

    // jump to a date: load enough rows, then scroll to that day
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
private fun OverflowMenu(
    timeline: Boolean, hasUnread: Boolean, onTimeline: (Boolean) -> Unit, onJump: () -> Unit,
    onMarkAll: () -> Unit, onLink: () -> Unit, onSettings: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        RoundIcon(Icons.Default.MoreVert, "More options") { open = true }
        DropdownMenu(open, { open = false }, containerColor = Raised, shape = RoundedCornerShape(14.dp)) {
            @Composable fun entry(text: String, on: () -> Unit) = DropdownMenuItem({ Text(text, color = Ink, fontSize = 15.sp) },
                { open = false; on() }, colors = MenuDefaults.itemColors(textColor = Ink))
            entry(if (timeline) "Group by conversation" else "Show every message") { onTimeline(!timeline) }
            if (timeline) entry("Jump to date", onJump)
            if (hasUnread) entry("Mark all as read", onMarkAll)
            entry("Link a phone", onLink)
            entry("Settings", onSettings)
        }
    }
}

/** One line per phone: online dot, name, battery. Tapping opens the phone list. */
@Composable
private fun PhoneStrip(devices: Map<String, Device>, stats: Map<String, Pair<Int, Int>>, now: Long, onClick: () -> Unit) =
    LazyRow(Modifier.padding(top = 14.dp), contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(devices.values.sortedBy { it.title }, key = { it.id }) { d ->
            val online = d.online(now)
            Row(Modifier.clip(CircleShape).background(Glass).clickable(onClick = onClick).padding(start = 10.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Pulse(Emerald, online)
                Spacer(Modifier.width(6.dp))
                Text(d.title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Spacer(Modifier.width(8.dp))
                Text(if (online) "${d.bat}%" else "offline", color = when { !online -> Amber; d.bat < 20 && !d.chg -> Coral; else -> Muted }, fontSize = 13.sp)
            }
        }
    }

/** Looks like a field, behaves like a button: search lives on its own page. */
@Composable
private fun SearchField(onClick: () -> Unit) =
    Row(Modifier.padding(horizontal = Gutter, vertical = 14.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).background(Panel)
        .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Search", onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = Muted)
        Spacer(Modifier.width(10.dp))
        Text("Search messages, names, numbers", color = Muted, fontSize = 15.sp, maxLines = 1)
    }

@Composable
private fun Notice(c: Color, title: String, sub: String, update: Pair<String, String>?) {
    val ctx = LocalContext.current
    Row(Modifier.padding(horizontal = Gutter, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.copy(alpha = .12f))
        .then(if (update != null) Modifier.clickable { openUrl(ctx, update.second) } else Modifier).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(c, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Soft, fontSize = 13.sp)
        }
        if (update != null) Text("Update", color = c, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** SIM selector + message kinds, one row, pinned under the title while scrolling. */
@Composable
private fun FilterBar(filter: Filter, look: Look, data: Data, pinned: Boolean, onSims: () -> Unit, onKind: (String) -> Unit) =
    Column(Modifier.fillMaxWidth().background(Bg)) {
        LazyRow(Modifier.padding(vertical = 8.dp), contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            item("sim") {
                val sel = filter.sim?.let { k -> look.sims.firstOrNull { it.key == k } }
                val label = when {
                    sel != null -> look.title(sel)
                    filter.dev != null -> look.phone(filter.dev)
                    else -> "All SIMs"
                }
                val active = sel != null || filter.dev != null
                Row(Modifier.height(34.dp).clip(CircleShape).background(if (active) Violet.copy(alpha = .18f) else Panel)
                    .border(1.dp, if (active) Violet.copy(alpha = .5f) else Line, CircleShape).clickable(onClickLabel = "Choose SIM", onClick = onSims)
                    .padding(start = 12.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (sel != null) { Box(Modifier.size(8.dp).background(look.color(sel.key), CircleShape)); Spacer(Modifier.width(8.dp)) }
                    Text(label, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Icon(Icons.Default.ArrowDropDown, null, tint = Muted)
                }
            }
            item("div") { Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(20.dp).background(Line)) }
            items(kinds, key = { it.first }) { (k, label) ->
                val selected = filter.kind == k
                val bg by animateColorAsState(if (selected) Ink else Color.Transparent, label = "kbg")
                val fg by animateColorAsState(if (selected) Bg else Soft, label = "kfg")
                Row(Modifier.height(34.dp).clip(CircleShape).background(bg).clickable { onKind(k) }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = fg, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
                    if (k == "unread" && data.unread > 0) { Spacer(Modifier.width(6.dp)); Text("${data.unread}", color = if (selected) Bg.copy(alpha = .7f) else Violet, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimSheet(filter: Filter, look: Look, stats: Map<String, Pair<Int, Int>>, onDismiss: () -> Unit, onPick: (Filter) -> Unit) =
    Sheet(onDismiss, "Show messages from", null) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            PickRow("All SIMs", null, null, stats.values.sumOf { it.second }, filter.sim == null && filter.dev == null) { onPick(filter.copy(sim = null, dev = null)) }
            look.sims.groupBy { it.dev }.forEach { (dev, sims) ->
                Text(look.phone(dev).uppercase(), Modifier.padding(start = 24.dp, top = 18.dp, bottom = 4.dp), color = Muted, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                if (sims.size > 1) PickRow("Every SIM on ${look.phone(dev)}", null, null, sims.sumOf { stats[it.key]?.second ?: 0 }, filter.dev == dev) {
                    onPick(filter.copy(dev = dev, sim = null))
                }
                sims.forEach { s ->
                    PickRow(look.title(s), listOf(s.carrier, s.number).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null }, look.color(s.key),
                        stats[s.key]?.second ?: 0, filter.sim == s.key) { onPick(filter.copy(sim = s.key, dev = null)) }
                }
            }
        }
    }

@Composable
private fun PickRow(title: String, sub: String?, dot: Color?, unread: Int, selected: Boolean, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (dot != null) { Box(Modifier.size(10.dp).background(dot, CircleShape)); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 16.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            if (sub != null) Text(sub, color = Muted, fontSize = 13.sp)
        }
        if (unread > 0) { Text("$unread unread", color = Muted, fontSize = 13.sp); Spacer(Modifier.width(12.dp)) }
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { if (selected) Icon(Icons.Default.Check, "Selected", tint = Violet) }
    }

@Composable
private fun DayLabel(text: String) =
    Text(text, Modifier.fillMaxWidth().padding(start = Gutter, top = 18.dp, bottom = 6.dp), color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

// ---------- rows ----------

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.longClick(onClick: () -> Unit, onLong: (() -> Unit)?) = combinedClickable(onClick = onClick, onLongClick = onLong)

@Composable
private fun RowShell(onClick: () -> Unit, onLong: () -> Unit, content: @Composable () -> Unit) =
    Column(Modifier.fillMaxWidth().background(Bg).longClick(onClick, onLong)) {
        Box(Modifier.padding(horizontal = Gutter, vertical = 11.dp)) { content() }
        Box(Modifier.padding(start = Gutter + 58.dp).fillMaxWidth().height(1.dp).background(Line))
    }

@Composable
private fun Meta(text: String, c: Color, modifier: Modifier = Modifier) = Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(6.dp).background(c, CircleShape))
    Spacer(Modifier.width(6.dp))
    Text(text, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Trailing(m: Msg, c: Color, recentOnly: Boolean) {
    val code = remember(m.body) { otp(m.body) }
    when {
        code != null && (!recentOnly || System.currentTimeMillis() - m.t < 3_600_000) -> OtpPill(code, c)
        m.amt != null -> Text(m.amt, color = when { m.amt.startsWith("+") -> Emerald; m.amt.startsWith("-") -> Coral; else -> Soft },
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ConvRow(c: Conv, look: Look, onClick: () -> Unit, onLong: () -> Unit) {
    val m = c.last
    val color = look.color(m.sim)
    val unread = c.unread > 0
    val who = Sync.display(m.dev, m.src)
    RowShell(onClick, onLong) {
        Row {
            Avatar(who, color)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(who, Modifier.weight(1f), color = Ink, fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    Text(shortTime(m.t), color = if (unread) Violet else Muted, fontSize = 12.sp, fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal)
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Text(m.body.replace('\n', ' '), Modifier.weight(1f), color = if (unread) Soft else Muted, fontSize = 14.sp, lineHeight = 20.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (unread) { Spacer(Modifier.width(10.dp)); CountBadge(c.unread) }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Meta("${look.title(m)} · ${look.phone(m.dev)}", color, Modifier.weight(1f))
                    Trailing(m, color, recentOnly = true)
                }
            }
        }
    }
}

@Composable
private fun MsgRow(m: Msg, look: Look, onClick: () -> Unit, onLong: () -> Unit) {
    val c = look.color(m.sim)
    val who = Sync.display(m.dev, m.src)
    RowShell(onClick, onLong) {
        Row {
            Avatar(who, c)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!m.read) { Box(Modifier.size(7.dp).background(Violet, CircleShape)); Spacer(Modifier.width(6.dp)) }
                    Text(who, Modifier.weight(1f), color = Ink, fontWeight = if (!m.read) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (m.star) { Icon(Icons.Default.Star, "Starred", Modifier.size(14.dp), tint = Amber); Spacer(Modifier.width(6.dp)) }
                    Text(clock(m.t), color = Muted, fontSize = 12.sp)
                }
                Spacer(Modifier.height(2.dp))
                Text(m.body, color = if (!m.read) Soft else Muted, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Meta("${look.title(m)} · ${look.phone(m.dev)}", c, Modifier.weight(1f))
                    Trailing(m, c, recentOnly = false)
                }
            }
        }
    }
}

@Composable
private fun SkeletonRow() = Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 14.dp)) {
    Shimmer(Modifier.size(44.dp).clip(CircleShape))
    Spacer(Modifier.width(14.dp))
    Column(Modifier.weight(1f)) {
        Shimmer(Modifier.fillMaxWidth(.4f).height(13.dp).clip(CircleShape))
        Spacer(Modifier.height(10.dp))
        Shimmer(Modifier.fillMaxWidth().height(11.dp).clip(CircleShape))
        Spacer(Modifier.height(6.dp))
        Shimmer(Modifier.fillMaxWidth(.65f).height(11.dp).clip(CircleShape))
    }
}

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
        Box(Modifier.fillMaxSize().background(c.copy(alpha = .22f)).padding(horizontal = 28.dp), contentAlignment = align) {
            Text(text, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }) { content() }
}

@Composable
private fun Empty(noPhones: Boolean, f: Filter, onLink: () -> Unit) =
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val (title, sub) = when {
            noPhones -> "Link your first phone" to "Install Simbox on a phone with SIM cards, choose Send this phone's messages, and scan the code."
            f.kind == "unread" -> "All caught up" to "Nothing unread right now."
            f.kind == "archived" -> "Nothing archived" to "Swipe a message left to archive it."
            f.kind == "starred" -> "No starred messages" to "Press and hold a message, then choose Star."
            f.kind == "muted" -> "No muted senders" to "Mute noisy senders from the message menu."
            f.kind == "otp" -> "No codes here" to "One-time codes from every SIM will collect here."
            f.kind == "money" -> "No money messages" to "Bank, bKash and Nagad messages will collect here."
            else -> "Nothing here yet" to "Try another SIM or filter."
        }
        Text(title, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(sub, color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
        if (noPhones) { Spacer(Modifier.height(20.dp)); GradButton("Link a phone", onClick = onLink) }
    }

// ---------- search (its own page, results as you type) ----------

private fun recents(): List<String> = runCatching { JSONArray(Store.get("recent") ?: "[]").let { a -> List(a.length()) { a.getString(it) } } }.getOrDefault(emptyList())
private fun saveRecent(q: String) { val l = (listOf(q.trim()) + recents().filter { it != q.trim() }).take(8); Store.set("recent", JSONArray(l).toString()) }

@Composable
private fun SearchScreen(query: String, onQuery: (String) -> Unit, look: Look, state: LazyListState, onBack: () -> Unit, onOpen: (Chat) -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var recent by remember { mutableStateOf(recents()) }
    var results by remember { mutableStateOf<List<Msg>?>(null) }
    LaunchedEffect(Unit) { if (query.isEmpty()) focus.requestFocus() }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) { results = null; return@LaunchedEffect }
        delay(120)
        results = withContext(Dispatchers.IO) { Sync.db.timeline(Filter(query = q), 200) }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = onBack)
            Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                if (query.isEmpty()) Text("Search messages, names, numbers", color = Muted, fontSize = 17.sp, maxLines = 1)
                BasicTextField(query, onQuery, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                    textStyle = TextStyle(color = Ink, fontSize = 17.sp), cursorBrush = SolidColor(Violet),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) { saveRecent(query); recent = recents() }; keyboard?.hide() }))
            }
            if (query.isNotEmpty()) RoundIcon(Icons.Default.Close, "Clear", Muted) { onQuery(""); focus.requestFocus() }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))

        val list = results
        when {
            query.isBlank() -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                if (recent.isEmpty()) {
                    Text("Search every message from all your phones", Modifier.padding(Gutter).padding(top = 12.dp), color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("Try a name, a number, a bank, or a word like \"code\".", Modifier.padding(horizontal = Gutter), color = Muted, fontSize = 14.sp)
                } else {
                    Row(Modifier.fillMaxWidth().padding(start = Gutter, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Recent", Modifier.weight(1f), color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        TextButton({ Store.set("recent", null); recent = emptyList() }) { Text("Clear", color = Violet) }
                    }
                    recent.forEach { r ->
                        Row(Modifier.fillMaxWidth().clickable { onQuery(r) }.padding(horizontal = Gutter, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Search, null, Modifier.size(18.dp), tint = Muted)
                            Spacer(Modifier.width(16.dp))
                            Text(r, color = Ink, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            list == null -> Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Violet, strokeWidth = 2.dp)
            }
            list.isEmpty() -> Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No results for \"${query.trim()}\"", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text("Check the spelling, or try part of a number or name.", color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
            else -> LazyColumn(Modifier.fillMaxSize(), state) {
                item { Text(if (list.size >= 200) "200+ results" else plural(list.size, "result"), Modifier.padding(start = Gutter, top = 14.dp, bottom = 4.dp),
                    color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                items(list, key = { it.id }) { m ->
                    SearchRow(m, query.trim(), look) { saveRecent(query); recent = recents(); keyboard?.hide(); onOpen(m.chat) }
                }
                item { Spacer(Modifier.navigationBarsPadding().height(24.dp)) }
            }
        }
    }
}

/** The matched words in a message, bold and in the accent; long texts are trimmed to start near the first match. */
private fun highlight(text: String, q: String): AnnotatedString {
    val i = text.indexOf(q, ignoreCase = true)
    val start = if (i > 48) text.lastIndexOf(' ', i - 24).let { if (it < 0) i - 24 else it + 1 } else 0
    val s = (if (start > 0) "…" else "") + text.substring(start)
    return buildAnnotatedString {
        append(s)
        if (q.isEmpty()) return@buildAnnotatedString
        var at = s.indexOf(q, ignoreCase = true)
        while (at >= 0) {
            addStyle(SpanStyle(color = Ink, fontWeight = FontWeight.SemiBold, background = Violet.copy(alpha = .22f)), at, at + q.length)
            at = s.indexOf(q, at + q.length, ignoreCase = true)
        }
    }
}

@Composable
private fun SearchRow(m: Msg, q: String, look: Look, onClick: () -> Unit) {
    val c = look.color(m.sim)
    val who = Sync.display(m.dev, m.src)
    RowShell(onClick, onClick) {
        Row {
            Avatar(who, c, 40.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(remember(who, q) { highlight(who, q) }, Modifier.weight(1f), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(shortTime(m.t), color = Muted, fontSize = 12.sp)
                }
                Spacer(Modifier.height(2.dp))
                Text(remember(m.body, q) { highlight(m.body, q) }, color = Muted, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Meta("${look.title(m)} · ${look.phone(m.dev)}", c)
            }
        }
    }
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
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = onBack)
            Avatar(name ?: chat.src, c, 38.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name ?: chat.src, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Meta(listOfNotNull(name?.let { chat.src }, simTitle(chat.sim, chat.slot, list.firstOrNull()?.carrier.orEmpty()), look.phone(chat.dev)).joinToString(" · "), c)
            }
            list.firstOrNull()?.let { m -> RoundIcon(Icons.Default.MoreVert, "Conversation options") { onMenu(m) } }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        LazyColumn(Modifier.weight(1f), reverseLayout = true, contentPadding = PaddingValues(horizontal = 16.dp)) {
            item { Spacer(Modifier.navigationBarsPadding().height(12.dp)) }
            items(rows, key = { if (it is Msg) it.id else "d$it" }) { r ->
                if (r is Msg) Bubble(r, c, Modifier.animateItem()) { onMsgMenu(r) }
                else Text(r as String, Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), color = Muted, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun Bubble(m: Msg, c: Color, modifier: Modifier, onLong: () -> Unit) {
    val shape = RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
    val code = remember(m.body) { otp(m.body) }
    Column(modifier.padding(vertical = 4.dp).fillMaxWidth(.86f).clip(shape).background(Panel).longClick({}, onLong).padding(horizontal = 14.dp, vertical = 12.dp)) {
        SelectionContainer { Text(remember(m.body) { linkified(m.body, Violet) }, color = Ink, fontSize = 15.sp, lineHeight = 22.sp) }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (m.star) { Icon(Icons.Default.Star, "Starred", Modifier.size(13.dp), tint = Amber); Spacer(Modifier.width(4.dp)) }
            Text(clock(m.t), Modifier.weight(1f), color = Muted, fontSize = 11.sp)
            Trailing(m, c, recentOnly = false)
        }
    }
}

// ---------- sheets & dialogs ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(onDismiss: () -> Unit, title: String, sub: String?, content: @Composable () -> Unit) =
    ModalBottomSheet(onDismiss, containerColor = Panel, scrimColor = Color(0x99000000)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(title, Modifier.padding(horizontal = 24.dp), color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    Text("Link a phone", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("On the other phone, open Simbox, choose Send this phone's messages, and point it at this code.", color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(18.dp))
                    Qr(Store.pairing(), Modifier.fillMaxWidth())
                    Spacer(Modifier.height(14.dp))
                    Text("This code unlocks your messages. Only show it to your own phones.", color = Coral, fontSize = 12.sp)
                } else Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    var shown by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { shown = true }
                    AnimatedVisibility(shown, enter = scaleIn(spring(Spring.DampingRatioMediumBouncy)) + fadeIn()) {
                        Box(Modifier.size(72.dp).background(Emerald, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Check, null, Modifier.size(38.dp), tint = Bg)
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Text("$name is linked", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Text("Its messages are on the way.", color = Muted, fontSize = 14.sp)
                }
            }
        }
    }
}
