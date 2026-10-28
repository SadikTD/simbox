package app.simbox

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SubscriptionManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.SetOptions
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        // Main phone keeps its own SQLite copy and relays retry via WorkManager, so Firestore's disk cache is dead weight.
        FirebaseFirestore.getInstance().firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
        getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            NotificationChannel("sms", "Messages", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel("alerts", "Phone alerts", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel("watch", "Background sync", NotificationManager.IMPORTANCE_MIN),
        ))
    }
}

// ---------- local settings ----------

object Store {
    private lateinit var p: SharedPreferences
    fun init(ctx: Context) { if (!::p.isInitialized) p = ctx.applicationContext.getSharedPreferences("simbox", 0) }
    fun get(k: String): String? = p.getString(k, null)
    fun set(k: String, v: String?) = p.edit().putString(k, v).apply()
    private fun long(k: String) = p.getLong(k, 0)
    private fun putLong(k: String, v: Long) = p.edit().putLong(k, v).apply()
    fun flag(k: String) = p.getBoolean(k, false)
    fun setFlag(k: String, v: Boolean) = p.edit().putBoolean(k, v).apply()

    var room: String? get() = get("room"); set(v) = set("room", v)
    var key: String? get() = get("key"); set(v) = set("key", v)
    var role: String? get() = get("role"); set(v) = set("role", v)
    var name: String get() = get("name") ?: Build.MODEL; set(v) = set("name", v.trim().ifBlank { Build.MODEL })
    val deviceId: String get() = get("id") ?: UUID.randomUUID().toString().replace("-", "").take(12).also { set("id", it) }

    var lastNotified: Long get() = long("ln"); set(v) = putLong("ln", v)
    var lastUpload: Long get() = long("lu"); set(v) = putLong("lu", v)
    var lastScan: Long get() = long("ls"); set(v) = putLong("ls", v)
    var syncCursor: Long get() = long("sc"); set(v) = putLong("sc", v)
    var pingAt: Long get() = long("ping"); set(v) = putLong("ping", v)
    var lastCleanup: Long get() = long("lc"); set(v) = putLong("lc", v)
    var lastUpdateCheck: Long get() = long("luc"); set(v) = putLong("luc", v)
    var synced: Int get() = p.getInt("sy", 0); set(v) = p.edit().putInt("sy", v).apply()
    var retentionDays: Int get() = p.getInt("ret", 30); set(v) = p.edit().putInt("ret", v).apply()
    var backfilled: Boolean get() = flag("bf"); set(v) = setFlag("bf", v)
    var appLock: Boolean get() = flag("lock"); set(v) = setFlag("lock", v)
    var hideOnLock: Boolean get() = flag("hol"); set(v) = setFlag("hol", v)
    var timeline: Boolean get() = flag("tl"); set(v) = setFlag("tl", v)

    fun label(sim: String): String? = get("label:$sim")
    fun setLabel(sim: String, v: String) = set("label:$sim", v.trim().ifBlank { null })
    fun deviceLabel(dev: String): String? = get("dlabel:$dev")
    fun setDeviceLabel(dev: String, v: String) = set("dlabel:$dev", v.trim().ifBlank { null })
    fun simColor(sim: String): Int = p.getInt("color:$sim", -1)
    fun setSimColor(sim: String, i: Int) = p.edit().putInt("color:$sim", i).apply()

    fun pairing() = "simbox1|$room|$key"
    fun create() { room = rand(18); key = rand(32); role = "main"; lastNotified = System.currentTimeMillis() }
    fun join(code: String): Boolean {
        val x = code.trim().split("|")
        if (x.size != 3 || x[0] != "simbox1" || x[1].length < 20) return false
        room = x[1]; key = x[2]; role = "relay"; backfilled = false
        return true
    }
    /** Forget the pairing but keep this phone's id, name and SIM labels. */
    fun unlink() {
        val keep = setOf("id", "name")
        p.edit().apply { p.all.keys.filter { it !in keep && !it.startsWith("label:") && !it.startsWith("color:") && !it.startsWith("dlabel:") }.forEach { remove(it) } }.apply()
    }
}

// ---------- end-to-end encryption (AES-256-GCM, key only lives on paired phones) ----------

private val b64e = Base64.getUrlEncoder().withoutPadding()
private val b64d = Base64.getUrlDecoder()
fun rand(n: Int): String = b64e.encodeToString(ByteArray(n).also(SecureRandom()::nextBytes))
fun sha(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

object Crypto {
    private fun cipher(mode: Int, key: String, iv: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding")
        .apply { init(mode, SecretKeySpec(b64d.decode(key), "AES"), GCMParameterSpec(128, iv)) }

    fun enc(plain: String, key: String = Store.key!!): String {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        return b64e.encodeToString(iv + cipher(Cipher.ENCRYPT_MODE, key, iv).doFinal(plain.toByteArray()))
    }

    fun dec(data: String, key: String = Store.key!!): String? = runCatching {
        val b = b64d.decode(data)
        String(cipher(Cipher.DECRYPT_MODE, key, b.copyOf(12)).doFinal(b, 12, b.size - 12))
    }.getOrNull()
}

// ---------- message understanding ----------

private val otpHint = Regex("\\b(otp|code|pin|password|passcode)\\b|verif|কোড|পিন", RegexOption.IGNORE_CASE)
private val otpNum = Regex("(?<!\\d)\\d{4,8}(?!\\d)")

/** The one-time code in an SMS, if it looks like one: the first 4-8 digit number after the hint word, else the first in the text. */
fun otp(body: String): String? {
    val hint = otpHint.find(body) ?: return null
    return (otpNum.find(body, hint.range.first) ?: otpNum.find(body))?.value
}

private const val NUM = "[\\d০-৯][\\d০-৯,]*(?:\\.[\\d০-৯]+)?"
private val moneyAmt = Regex("(?:tk\\.?|bdt|৳|taka)\\s?($NUM)|($NUM)\\s?(?:tk|taka|টাকা|৳)", RegexOption.IGNORE_CASE)
private val moneyWord = Regex("balance|a/c|account|bkash|nagad|rocket|upay|bank|transaction|trx|txn|লেনদেন|ব্যালেন্স|ব্যাল্যান্স", RegexOption.IGNORE_CASE)
private val debitWord = Regex("debit|\\bsent\\b|send money|payment|\\bpaid\\b|withdr|cash ?out|purchase|deduct|কাটা|উত্তোলন|পাঠিয়েছেন|পরিশোধ|খরচ", RegexOption.IGNORE_CASE)
private val creditWord = Regex("credit|received|cash ?in|deposit|refund|পেয়েছেন|জমা|গ্রহণ", RegexOption.IGNORE_CASE)
private val promoWord = Regex("offer|discount|cashback|\\d+\\s?%|\\d+\\s?(gb|mb)\\b|\\bgb\\b|minute|\\bpack\\b|recharge|bonus|internet|subscri|\\bfree\\b|জিবি|মিনিট|অফার|ফ্রি|ছাড়|বোনাস|রিচার্জ|এমবি", RegexOption.IGNORE_CASE)
private val phoneSrc = Regex("^\\+?[\\d\\s-]{7,}$")

data class Kind(val cat: String, val amount: String? = null)

/** Category used for the smart filters: otp, money, personal, promo or other. Money also gets a signed amount like "+৳500". */
fun classify(from: String, body: String): Kind {
    if (otp(body) != null) return Kind("otp")
    val m = moneyAmt.find(body)
    val debit = debitWord.find(body)?.range?.first
    val credit = creditWord.find(body)?.range?.first
    if (m != null && (debit != null || credit != null || moneyWord.containsMatchIn(body + " " + from))) {
        val sign = when {
            debit != null && (credit == null || debit < credit) -> "-"
            credit != null -> "+"
            else -> ""
        }
        return Kind("money", sign + "৳" + m.groupValues[1].ifEmpty { m.groupValues[2] })
    }
    if (phoneSrc.matches(from.trim())) return Kind("personal")
    if (promoWord.containsMatchIn(body)) return Kind("promo")
    return Kind("other")
}

/** Last 10 digits of a phone number, used to match contacts regardless of +880/0 prefixes. */
fun digits10(s: String): String? = s.filter(Char::isDigit).takeLast(10).takeIf { it.length >= 7 }

// ---------- SIM info ----------

data class Sim(val slot: Int, val carrier: String, val number: String = "")

fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
fun activeSims(ctx: Context): List<Sim> = runCatching {
    val sm = ctx.getSystemService(SubscriptionManager::class.java)
    sm.activeSubscriptionInfoList.orEmpty().map { info ->
        val num = runCatching {
            if (Build.VERSION.SDK_INT >= 33) sm.getPhoneNumber(info.subscriptionId) else @Suppress("DEPRECATION") info.number
        }.getOrNull().orEmpty()
        Sim(info.simSlotIndex, info.carrierName?.toString().orEmpty(), num)
    }
}.getOrDefault(emptyList())

@SuppressLint("MissingPermission")
fun simFor(ctx: Context, subId: Int): Sim {
    val info = runCatching { ctx.getSystemService(SubscriptionManager::class.java).getActiveSubscriptionInfo(subId) }.getOrNull()
    if (info != null) return Sim(info.simSlotIndex, info.carrierName?.toString().orEmpty())
    return activeSims(ctx).singleOrNull() ?: Sim(-1, "")
}

fun simTitle(key: String, slot: Int, carrier: String) =
    Store.label(key) ?: "${carrier.ifBlank { "SIM" }} ${if (slot < 0) "?" else slot + 1}"

// ---------- Firestore ----------

private fun roomRef() = FirebaseFirestore.getInstance().collection("rooms").document(Store.room!!)
fun msgsRef() = roomRef().collection("msgs")
fun devsRef() = roomRef().collection("devices")
fun contactsRef() = roomRef().collection("contacts")

/** Doc id is a hash of the message, so the live receiver, catch-up scan and history backfill never create duplicates. */
fun msgDoc(from: String, body: String, t: Long, sim: Sim, backfill: Boolean = false): Pair<String, Map<String, Any>> {
    val id = sha("${Store.deviceId}|$from|$t|$body").take(24)
    val c = Crypto.enc(JSONObject().put("f", from).put("b", body).put("s", sim.slot).put("k", sim.carrier).toString())
    val m = mutableMapOf<String, Any>("d" to Store.deviceId, "t" to t, "c" to c, "u" to FieldValue.serverTimestamp())
    if (backfill) m["bf"] = true
    return id to m
}

fun heartbeat(ctx: Context) = run {
    val bm = ctx.getSystemService(BatteryManager::class.java)
    val sims = JSONArray(activeSims(ctx).map { JSONObject().put("s", it.slot).put("k", it.carrier).put("p", it.number) })
    val info = JSONObject().put("n", Store.name).put("bat", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
        .put("chg", bm.isCharging).put("sims", sims).put("v", BuildConfig.VERSION_NAME)
    // merge: keeps the main phone's ack/removed fields intact
    devsRef().document(Store.deviceId).set(mapOf("t" to System.currentTimeMillis(), "c" to Crypto.enc(info.toString())), SetOptions.merge())
}

/** Uploads inbox SMS with date > [since] (all of them when 0). Ids dedupe, so overlaps are harmless. */
private fun uploadInbox(ctx: Context, since: Long, backfill: Boolean): Int {
    var n = 0
    var batch = FirebaseFirestore.getInstance().batch()
    var inBatch = 0
    ctx.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, null, "date > ?", arrayOf(since.toString()), "date DESC")?.use { c ->
        val iAddr = c.getColumnIndex("address"); val iBody = c.getColumnIndex("body")
        val iSent = c.getColumnIndex("date_sent"); val iDate = c.getColumnIndex("date"); val iSub = c.getColumnIndex("sub_id")
        while (c.moveToNext()) {
            val sent = if (iSent >= 0) c.getLong(iSent) else 0
            val sim = if (iSub >= 0) simFor(ctx, c.getInt(iSub)) else Sim(-1, "")
            val (id, m) = msgDoc(c.getString(iAddr) ?: "Unknown", c.getString(iBody) ?: "", if (sent > 0) sent else c.getLong(iDate), sim, backfill)
            batch.set(msgsRef().document(id), m)
            n++; inBatch++
            if (inBatch == 400) {
                Tasks.await(batch.commit(), 60, TimeUnit.SECONDS)
                if (backfill) Store.synced = n
                batch = FirebaseFirestore.getInstance().batch(); inBatch = 0
            }
        }
    }
    if (inBatch > 0) Tasks.await(batch.commit(), 60, TimeUnit.SECONDS)
    if (backfill) Store.synced = n
    return n
}

/** Every SMS already on this phone, once, so old messages show on the main phone too. */
fun backfill(ctx: Context): Int {
    Store.lastScan = System.currentTimeMillis()
    return uploadInbox(ctx, 0, true)
}

/** Safety net: anything that arrived while Android had Simbox asleep gets sent now. */
fun catchUp(ctx: Context) {
    if (!Store.backfilled) return
    val now = System.currentTimeMillis()
    if (Store.lastScan == 0L) { Store.lastScan = now; return }
    uploadInbox(ctx, Store.lastScan - 120_000, false)
    Store.lastScan = now
}

/** Phone contacts (number -> name), encrypted, so the main phone can show names instead of numbers. */
fun uploadContacts(ctx: Context) {
    if (!granted(ctx, Manifest.permission.READ_CONTACTS)) return
    val map = sortedMapOf<String, String>()
    ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME), null, null, null)?.use { c ->
        while (c.moveToNext() && map.size < 8000) {
            val k = digits10(c.getString(0) ?: continue) ?: continue
            map[k] = c.getString(1) ?: continue
        }
    }
    val json = JSONObject(map as Map<*, *>).toString()
    val h = sha(json)
    if (h == Store.get("ch")) return
    Tasks.await(contactsRef().document(Store.deviceId).set(mapOf("t" to System.currentTimeMillis(), "c" to Crypto.enc(json))), 60, TimeUnit.SECONDS)
    Store.set("ch", h)
}

/** The main phone can remove a relay: it sets removed=true, and the relay unlinks itself on its next check-in. */
private fun removedRemotely(): Boolean =
    Tasks.await(devsRef().document(Store.deviceId).get(), 30, TimeUnit.SECONDS).getBoolean("removed") == true

private fun selfRemove(ctx: Context) {
    stopBackground(ctx)
    Store.unlink()
    val n = NotificationCompat.Builder(ctx, "alerts").setSmallIcon(R.drawable.ic_stat)
        .setContentTitle("This phone was removed from Simbox")
        .setContentText("It no longer sends messages. Open Simbox to link it again.").setAutoCancel(true).build()
    ctx.getSystemService(NotificationManager::class.java).notify(7, n)
}

// ---------- relay: catch SMS -> upload ----------

private val netConstraint get() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        Store.init(ctx)
        if (Store.room == null || Store.role != "relay") return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        if (parts.isEmpty()) return
        val subId = intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", intent.getIntExtra("subscription", -1))
        val sim = simFor(ctx, subId)
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf(
                "f" to (parts[0].displayOriginatingAddress ?: "Unknown"),
                "b" to parts.joinToString("") { it.displayMessageBody.orEmpty() },
                "t" to parts[0].timestampMillis, "s" to sim.slot, "k" to sim.carrier,
            ))
            .setConstraints(netConstraint)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 20, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueue(req)
    }
}

class UploadWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        Store.init(applicationContext)
        if (Store.room == null || Store.role != "relay") return Result.success()
        val d = inputData
        val (id, m) = msgDoc(d.getString("f")!!, d.getString("b")!!, d.getLong("t", 0), Sim(d.getInt("s", -1), d.getString("k").orEmpty()))
        return try {
            Tasks.await(msgsRef().document(id).set(m), 30, TimeUnit.SECONDS)
            Store.lastUpload = System.currentTimeMillis()
            heartbeat(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

class BackfillWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        Store.init(applicationContext)
        if (Store.room == null || Store.role != "relay" || Store.backfilled) return Result.success()
        return runCatching { backfill(applicationContext); Store.backfilled = true }
            .fold({ Result.success() }, { Result.retry() })
    }
}

/** Every 15 minutes on relays: removed check, missed-SMS catch-up, contacts, battery/SIM status. */
class HeartbeatWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        Store.init(ctx)
        if (Store.room == null || Store.role != "relay") return Result.success()
        return runCatching {
            if (removedRemotely()) { selfRemove(ctx); return Result.success() }
            catchUp(ctx)
            runCatching { uploadContacts(ctx) }
            Tasks.await(heartbeat(ctx), 30, TimeUnit.SECONDS)
            checkUpdate()
        }.fold({ Result.success() }, { Result.retry() })
    }
}

fun startBackground(ctx: Context) {
    val wm = WorkManager.getInstance(ctx)
    if (Store.role == "relay") {
        if (!Store.backfilled) wm.enqueueUniqueWork("backfill", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BackfillWorker>().setConstraints(netConstraint)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS).build())
        wm.enqueueUniquePeriodicWork("hb", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES).setConstraints(netConstraint).build())
        wm.enqueue(OneTimeWorkRequestBuilder<HeartbeatWorker>().setConstraints(netConstraint).build())
    } else if (Store.role == "main") {
        wm.cancelUniqueWork("hb")
        ContextCompat.startForegroundService(ctx, Intent(ctx, WatchService::class.java))
    }
}

fun stopBackground(ctx: Context) {
    WorkManager.getInstance(ctx).cancelAllWork()
    ctx.stopService(Intent(ctx, WatchService::class.java))
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        if (Store.room != null) startBackground(ctx)
    }
}

// ---------- updates from GitHub releases ----------

const val REPO = "SadikTD/simbox"

/** Checks the latest GitHub release at most every 12 hours (blocking; call off the main thread). */
fun checkUpdate(force: Boolean = false) {
    val now = System.currentTimeMillis()
    if (!force && now - Store.lastUpdateCheck < 12 * 3_600_000) return
    runCatching {
        val con = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        con.connectTimeout = 10_000; con.readTimeout = 10_000
        val j = JSONObject(con.inputStream.bufferedReader().readText())
        val assets = j.optJSONArray("assets") ?: JSONArray()
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") }
        Store.set("ltag", j.optString("tag_name").removePrefix("v"))
        Store.set("lurl", apk?.optString("browser_download_url") ?: j.optString("html_url"))
        Store.lastUpdateCheck = now
    }
}

/** Newer version name and download link, if GitHub has one. */
fun updateAvailable(): Pair<String, String>? {
    val tag = Store.get("ltag") ?: return null
    val url = Store.get("lurl") ?: return null
    return if (newer(tag, BuildConfig.VERSION_NAME)) tag to url else null
}

fun newer(a: String, b: String): Boolean {
    val x = a.split(".").map { it.toIntOrNull() ?: 0 }; val y = b.split(".").map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d > 0 }
    return false
}
