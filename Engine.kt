package com.rishat.fileshare

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * অ্যাপের মস্তিষ্ক। Wi-Fi Direct, LAN (NSD/QR/IP) — সব সংযোগ-পদ্ধতি এক সেশন-ইঞ্জিনে মিলিত হয়।
 * UI শুধু এই অবজেক্টের অবস্থা দেখায়; তাই স্ক্রিন ঘুরলে/অ্যাপ ব্যাকগ্রাউন্ডে গেলেও ট্রান্সফার চলতে থাকে।
 */
object Engine : Session.Host, DirectTransport.Callback {

    enum class ConnState { IDLE, CONNECTING, CONNECTED }
    enum class UiMode { DIRECT, LAN }

    interface Listener {
        fun onEngineChanged()
        fun onApprovalRequest(request: ApprovalRequest)
        fun onMessage(text: String)
    }

    class ApprovalRequest(val peerName: String) {
        val future = CompletableFuture<Boolean>()
    }

    private lateinit var appCtx: Context
    private val main = Handler(Looper.getMainLooper())
    val io: ExecutorService = Executors.newCachedThreadPool()
    private val uidGen = AtomicLong(1)
    private var started = false

    lateinit var storage: Storage
        private set
    lateinit var deviceId: String
        private set
    lateinit var deviceName: String
        private set

    @Volatile var listener: Listener? = null
    @Volatile var uiVisible = false
    @Volatile var uiMode = UiMode.DIRECT

    @Volatile var connState = ConnState.IDLE
        private set
    @Volatile var status: String = "প্রস্তুত"
        private set
    @Volatile var session: Session? = null
        private set
    @Volatile var peerName: String = ""
        private set
    @Volatile var peerLink: LinkType? = null
        private set

    @Volatile var directPeers: List<WifiP2pDevice> = emptyList()
        private set
    @Volatile var lanPeers: List<LanPeer> = emptyList()
        private set
    @Volatile var directScanning = false
        private set
    @Volatile var ownDirectName: String = ""
        private set

    val transfers = CopyOnWriteArrayList<TransferItem>()
    val pendingShare = CopyOnWriteArrayList<Uri>()

    @Volatile var serverPort = 0
        private set

    private var direct: DirectTransport? = null
    private var lan: LanDiscovery? = null
    private var lanWanted = false
    private var server: ServerSocket? = null

    private val dialGen = AtomicInteger(0)
    @Volatile private var dialTarget: String? = null
    @Volatile private var groupFormed = false
    @Volatile private var pairToken: String? = null
    @Volatile private var pairExpiry = 0L
    private var autoScanBudget = 0

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // ───────────────────────── Init ─────────────────────────

    @Synchronized
    fun init(context: Context) {
        if (::appCtx.isInitialized) return
        appCtx = context.applicationContext
        storage = Storage(appCtx)
        val prefs = appCtx.getSharedPreferences("rfs", Context.MODE_PRIVATE)
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString("device_id", id).apply()
        }
        deviceId = id
        deviceName = readDeviceName()
    }

    private fun readDeviceName(): String {
        val n: String? = try {
            Settings.Global.getString(appCtx.contentResolver, "device_name")
        } catch (_: Exception) {
            null
        }
        if (n != null && n.isNotBlank()) return n
        return Build.MODEL.ifBlank { "Android" }
    }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        startServer()
    }

    fun directSupported(): Boolean =
        appCtx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

    // ───────────────────────── Server (দুই মোডেরই ইনকামিং) ─────────────────────────

    private fun startServer() {
        io.execute {
            val s: ServerSocket? = try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.receiveBufferSize = Proto.SOCKET_BUFFER
                ss.bind(InetSocketAddress(Proto.DEFAULT_PORT))
                ss
            } catch (_: Exception) {
                try { ServerSocket(0) } catch (_: Exception) { null }
            }
            if (s == null) {
                setStatusIdle("লোকাল সার্ভার চালু করা যায়নি")
                return@execute
            }
            server = s
            serverPort = s.localPort
            updateLan()
            while (!s.isClosed) {
                try {
                    val c = s.accept()
                    io.execute { handleIncoming(c) }
                } catch (_: Exception) {
                    if (s.isClosed) break
                    try { Thread.sleep(200) } catch (_: InterruptedException) { break }
                }
            }
        }
    }

    private fun handleIncoming(socket: Socket) {
        try {
            tune(socket)
            val link = if (isP2pAddress(socket.localAddress)) LinkType.DIRECT else LinkType.LAN
            val sess = Session(socket, link, this)
            val err = sess.handshakeServer(Session.Hello(deviceId, deviceName, "")) { hello ->
                decideIncoming(hello, link)
            }
            if (err != null) {
                try { socket.close() } catch (_: Exception) {}
                return
            }
            if (!installSession(sess)) sess.close("অন্য ফোনের সঙ্গে ব্যস্ত", true)
        } catch (_: Exception) {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /** null = গ্রহণ; নইলে প্রত্যাখ্যানের কারণ। */
    private fun decideIncoming(hello: Session.Hello, link: LinkType): String? {
        if (session != null) return "অন্য ফোন ইতিমধ্যে সংযুক্ত আছে"
        if (hello.deviceId == deviceId) return "নিজের সঙ্গে সংযোগ সম্ভব নয়"
        val tok = pairToken
        if (tok != null && hello.token == tok && System.currentTimeMillis() < pairExpiry) {
            pairToken = null
            return null // QR-এ বিশ্বস্ত
        }
        if (link == LinkType.DIRECT) return null // Wi-Fi Direct-এ সিস্টেম নিজেই অনুমতি নিয়েছে
        val l = listener
        if (l == null || !uiVisible) return "অন্য ফোনে অ্যাপ খোলা নেই; অনুমতি নেওয়া যায়নি"
        val req = ApprovalRequest(hello.deviceName)
        main.post { listener?.onApprovalRequest(req) }
        val ok = try {
            req.future.get(Proto.APPROVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            false
        }
        return if (ok) null else "অন্য ফোন সংযোগ গ্রহণ করেনি"
    }

    private fun isP2pAddress(local: InetAddress): Boolean {
        val name = try { NetworkInterface.getByInetAddress(local)?.name } catch (_: Exception) { null }
        if (name != null && name.startsWith("p2p")) return true
        return (local.hostAddress ?: "").startsWith("192.168.49.")
    }

    private fun tune(s: Socket) {
        try { s.tcpNoDelay = true } catch (_: Exception) {}
        try { s.keepAlive = true } catch (_: Exception) {}
        try { s.sendBufferSize = Proto.SOCKET_BUFFER } catch (_: Exception) {}
        try { s.receiveBufferSize = Proto.SOCKET_BUFFER } catch (_: Exception) {}
    }

    // ───────────────────────── Dial (ক্লায়েন্ট দিক) ─────────────────────────

    fun dial(host: String, port: Int, link: LinkType, token: String, label: String, attempts: Int) {
        if (session != null) return
        val target = "$host:$port"
        if (dialTarget == target) return
        dialTarget = target
        val gen = dialGen.incrementAndGet()
        connState = ConnState.CONNECTING
        status = "$label এর সঙ্গে সংযোগ হচ্ছে…"
        publish()
        io.execute {
            var lastErr = "সংযোগ হয়নি"
            var attempt = 0
            while (attempt < attempts && gen == dialGen.get() && session == null) {
                attempt++
                var socket: Socket? = null
                try {
                    val s = Socket()
                    socket = s
                    tune(s)
                    s.connect(InetSocketAddress(host, port), 3000)
                    val sess = Session(s, link, this)
                    val err = sess.handshakeClient(Session.Hello(deviceId, deviceName, token))
                    if (err != null) {
                        try { s.close() } catch (_: Exception) {}
                        finishDial(gen, target, err)
                        return@execute
                    }
                    if (gen != dialGen.get()) {
                        try { s.close() } catch (_: Exception) {}
                        return@execute
                    }
                    dialTarget = null
                    if (!installSession(sess)) sess.close("ব্যস্ত", true)
                    return@execute
                } catch (e: Exception) {
                    lastErr = e.message ?: lastErr
                    try { socket?.close() } catch (_: Exception) {}
                    try { Thread.sleep(600) } catch (_: InterruptedException) { return@execute }
                }
            }
            finishDial(gen, target, "সংযোগ হয়নি: $lastErr")
        }
    }

    private fun finishDial(gen: Int, target: String, msg: String) {
        if (dialTarget == target) dialTarget = null
        if (gen == dialGen.get() && session == null) {
            connState = ConnState.IDLE
            status = msg
            publish()
            message(msg)
        }
    }

    // ───────────────────────── Session ─────────────────────────

    @Synchronized
    private fun installSession(sess: Session): Boolean {
        if (session != null) return false
        main.removeCallbacks(directTimeout)
        session = sess
        peerName = sess.peerName
        peerLink = sess.link
        connState = ConnState.CONNECTED
        status = "সংযুক্ত: ${sess.peerName}"
        acquireLocks()
        sess.start()
        startForegroundService()
        updateLan()
        publish()
        val staged = pendingShare.toList()
        pendingShare.clear()
        if (staged.isNotEmpty()) sendUris(staged)
        return true
    }

    override fun onSessionClosed(sess: Session, reason: String) {
        val wasCurrent = synchronized(this) {
            val current = session === sess
            if (current) {
                session = null
                connState = ConnState.IDLE
                status = "সংযোগ বিচ্ছিন্ন: $reason"
                peerName = ""
                peerLink = null
            }
            current
        }
        if (!wasCurrent) return
        releaseLocks()
        stopForegroundService()
        if (sess.link == LinkType.DIRECT) direct?.disconnect()
        dialTarget = null
        updateLan()
        publish()
        message(status)
    }

    fun disconnect() {
        io.execute {
            dialGen.incrementAndGet()
            dialTarget = null
            main.removeCallbacks(directTimeout)
            val s = session
            if (s != null) {
                s.close("আপনি সংযোগ বিচ্ছিন্ন করেছেন", true)
            } else {
                direct?.disconnect()
                connState = ConnState.IDLE
                status = "বাতিল করা হয়েছে"
                publish()
            }
        }
    }

    override fun nextUid(): Long = uidGen.getAndIncrement()

    override fun createSink(name: String, mime: String, size: Long): FileSink =
        storage.createSink(name, mime, size)

    override fun onItemAdded(item: TransferItem) {
        transfers.add(item)
        if (transfers.size > 80) {
            val old = transfers.firstOrNull { it.isFinished }
            if (old != null) transfers.remove(old)
        }
        publish()
    }

    // ───────────────────────── ফাইল পাঠানো ─────────────────────────

    fun sendUris(uris: List<Uri>) {
        val sess = session
        if (sess == null) {
            pendingShare.addAll(uris)
            message("সংযুক্ত হলে ${pendingShare.size}টি ফাইল স্বয়ংক্রিয়ভাবে পাঠানো হবে")
            publish()
            return
        }
        io.execute {
            for (uri in uris) {
                val src = UriSource.from(appCtx, uri)
                if (src == null || src.size < 0) {
                    val bad = TransferItem(nextUid(), true, src?.name ?: "ফাইল", 0, "")
                    bad.message = "ফাইলের আকার/তথ্য পড়া যায়নি"
                    bad.state = TState.FAILED
                    onItemAdded(bad)
                } else {
                    sess.enqueue(src)
                }
            }
        }
    }

    fun stage(uris: List<Uri>) = sendUris(uris)

    fun clearStaged() {
        pendingShare.clear()
        publish()
    }

    fun cancelTransfer(item: TransferItem) {
        io.execute {
            val s = session
            if (s != null) s.cancel(item)
            else if (!item.isFinished) {
                item.message = "বাতিল"
                item.state = TState.CANCELED
            }
            publish()
        }
    }

    fun clearFinished() {
        val done = transfers.filter { it.isFinished }
        transfers.removeAll(done.toSet())
        publish()
    }

    // ───────────────────────── Wi-Fi Direct ─────────────────────────

    fun directStart() {
        if (direct == null) direct = DirectTransport(appCtx, this)
        direct?.start()
    }

    /** সমস্যা থাকলে বার্তা, নইলে null। */
    fun preflightDirect(): String? {
        val wm = appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm != null && !wm.isWifiEnabled) return "Wi-Fi বন্ধ আছে। Wi-Fi চালু করুন।"
        if (Build.VERSION.SDK_INT in 28..32) {
            val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm != null && !lm.isLocationEnabled) return "ফোনের লোকেশন বন্ধ। Wi-Fi Direct খুঁজতে লোকেশন চালু করুন।"
        }
        return null
    }

    fun directAutoDiscover() {
        autoScanBudget = 20
        if (connState == ConnState.IDLE && !directScanning) directDiscover()
    }

    fun directDiscover() {
        if (!directSupported()) {
            setStatusIdle("এই ফোনে Wi-Fi Direct সমর্থিত নয়। LAN মোড ব্যবহার করুন।")
            return
        }
        val problem = preflightDirect()
        if (problem != null) {
            setStatusIdle(problem)
            return
        }
        directStart()
        if (connState == ConnState.IDLE) status = "আশেপাশের ফোন খোঁজা হচ্ছে… অন্য ফোনেও অ্যাপ খুলে রাখুন"
        publish()
        direct?.discover()
    }

    fun directConnect(device: WifiP2pDevice) {
        if (session != null) return
        connState = ConnState.CONNECTING
        status = "${device.deviceName} ফোনে আমন্ত্রণ পাঠানো হচ্ছে — ওই ফোনে ‘Accept/গ্রহণ’ চাপুন"
        publish()
        direct?.connect(device)
        main.removeCallbacks(directTimeout)
        main.postDelayed(directTimeout, 45_000)
    }

    private val directTimeout = Runnable {
        if (session == null && connState == ConnState.CONNECTING && dialTarget == null && !groupFormed) {
            direct?.cancelConnect()
            connState = ConnState.IDLE
            status = "সময় শেষ। অন্য ফোনে আমন্ত্রণ গ্রহণ করেছেন কি? আবার চেষ্টা করুন।"
            publish()
        }
    }

    override fun hasSession(): Boolean = session != null

    override fun onDirectState(enabled: Boolean) {
        if (!enabled && connState != ConnState.CONNECTED) setStatusIdle("Wi-Fi Direct বন্ধ। Wi-Fi চালু করুন।")
    }

    override fun onDirectPeers(peers: List<WifiP2pDevice>) {
        directPeers = peers.sortedWith(
            compareBy<WifiP2pDevice>({ it.status != WifiP2pDevice.AVAILABLE }, { it.deviceName ?: "" })
        )
        publish()
    }

    override fun onDirectSelf(name: String) {
        ownDirectName = name
        publish()
    }

    override fun onDirectScanning(active: Boolean) {
        directScanning = active
        publish()
        if (!active && autoScanBudget > 0 && uiVisible && uiMode == UiMode.DIRECT && connState == ConnState.IDLE) {
            autoScanBudget--
            main.postDelayed({
                if (uiVisible && uiMode == UiMode.DIRECT && connState == ConnState.IDLE && !directScanning) directDiscover()
            }, 1500)
        }
    }

    override fun onDirectError(msg: String) {
        if (connState == ConnState.CONNECTING && session == null && dialTarget == null) connState = ConnState.IDLE
        if (connState != ConnState.CONNECTED) status = msg
        publish()
        message(msg)
    }

    override fun onDirectGroup(info: WifiP2pInfo) {
        if (info.groupFormed) {
            groupFormed = true
            main.removeCallbacks(directTimeout)
            if (session != null) return
            if (info.isGroupOwner) {
                connState = ConnState.CONNECTING
                status = "Wi-Fi Direct গ্রুপ তৈরি। অন্য ফোনের সংযোগের অপেক্ষা…"
                publish()
            } else {
                val addr = info.groupOwnerAddress?.hostAddress
                if (addr != null) dial(addr, Proto.DEFAULT_PORT, LinkType.DIRECT, "", "Wi-Fi Direct", 25)
            }
        } else {
            groupFormed = false
            val s = session
            if (s != null && s.link == LinkType.DIRECT) {
                io.execute { s.close("Wi-Fi Direct গ্রুপ বন্ধ হয়েছে", false) }
            } else if (connState == ConnState.CONNECTING && dialTarget == null && uiMode == UiMode.DIRECT) {
                // গ্রুপ ভেঙে গেছে, কিন্তু আমন্ত্রণ-অপেক্ষা চলছে হতে পারে — directTimeout সামলাবে
                publish()
            }
        }
    }

    // ───────────────────────── LAN (একই Wi-Fi / হটস্পট) ─────────────────────────

    fun setLanWanted(wanted: Boolean) {
        lanWanted = wanted
        updateLan()
    }

    private fun updateLan() {
        io.execute {
            synchronized(this) {
                val want = lanWanted && session == null && serverPort > 0 && ::appCtx.isInitialized
                if (want) {
                    if (lan == null) lan = LanDiscovery(appCtx) { list -> lanPeers = list; publish() }
                    lan?.start(deviceId, deviceName, serverPort)
                } else {
                    lan?.stop()
                    lanPeers = emptyList()
                }
            }
        }
    }

    fun connectLanPeer(peer: LanPeer) =
        dial(peer.host, peer.port, LinkType.LAN, "", peer.name, 3)

    fun connectManual(host: String, port: Int) =
        dial(host, port, LinkType.LAN, "", host, 3)

    fun connectFromQr(host: String, port: Int, token: String, name: String) =
        dial(host, port, LinkType.LAN, token, name.ifBlank { host }, 4)

    /** QR-এর টেক্সট, অথবা Wi-Fi/হটস্পটে যুক্ত না থাকলে null। */
    fun createPairing(): String? {
        val ip = Net.localIpv4() ?: return null
        val port = serverPort
        if (port <= 0) return null
        val b = ByteArray(8)
        SecureRandom().nextBytes(b)
        val tok = b.joinToString("") { "%02x".format(it) }
        pairToken = tok
        pairExpiry = System.currentTimeMillis() + 5 * 60_000
        return "rfs://connect?h=${Uri.encode(ip)}&p=$port&t=$tok&n=${Uri.encode(deviceName)}"
    }

    fun clearPairing() {
        pairToken = null
    }

    fun localAddressText(): String {
        val ip = Net.localIpv4() ?: return ""
        return "$ip:$serverPort"
    }

    // ───────────────────────── সিস্টেম রিসোর্স ─────────────────────────

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            val wm = appCtx.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            val wl = wm.createWifiLock(mode, "rfs:wifi")
            wl.setReferenceCounted(false)
            wl.acquire()
            wifiLock = wl
        } catch (_: Exception) {
        }
        try {
            val pm = appCtx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val cl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rfs:cpu")
            cl.setReferenceCounted(false)
            cl.acquire(4 * 60 * 60 * 1000L)
            wakeLock = cl
        } catch (_: Exception) {
        }
    }

    private fun releaseLocks() {
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wifiLock = null
        wakeLock = null
    }

    private fun startForegroundService() {
        try {
            ContextCompat.startForegroundService(appCtx, Intent(appCtx, TransferService::class.java))
        } catch (_: Exception) {
            // ব্যাকগ্রাউন্ড থেকে শুরু হলে সিস্টেম আটকাতে পারে — ট্রান্সফার তবুও চলে
        }
    }

    private fun stopForegroundService() {
        try { appCtx.stopService(Intent(appCtx, TransferService::class.java)) } catch (_: Exception) {}
    }

    // ───────────────────────── UI-তে খবর ─────────────────────────

    private fun setStatusIdle(msg: String) {
        if (connState != ConnState.CONNECTED) status = msg
        publish()
    }

    private fun publish() {
        main.post { listener?.onEngineChanged() }
    }

    private fun message(text: String) {
        main.post { listener?.onMessage(text) }
    }
}
