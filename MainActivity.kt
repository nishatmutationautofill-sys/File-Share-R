package com.rishat.fileshare

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : AppCompatActivity(), Engine.Listener {

    private lateinit var ui: Ui
    private lateinit var scroll: ScrollView
    private lateinit var root: LinearLayout

    private lateinit var ownNameText: TextView
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var disconnectBtn: Button

    private lateinit var connectBox: LinearLayout
    private lateinit var tabDirect: Button
    private lateinit var tabLan: Button
    private lateinit var directPanel: LinearLayout
    private lateinit var lanPanel: LinearLayout
    private lateinit var directHint: TextView
    private lateinit var scanBtn: Button
    private lateinit var directList: LinearLayout
    private lateinit var lanList: LinearLayout

    private lateinit var connectedBox: LinearLayout
    private lateinit var peerTitle: TextView
    private lateinit var peerSub: TextView
    private lateinit var stagedBox: LinearLayout
    private lateinit var stagedText: TextView

    private lateinit var transfersBox: LinearLayout
    private lateinit var emptyText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val rows = LinkedHashMap<Long, Row>()
    private var lastRowKey = ""

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) Engine.sendUris(uris)
    }
    private val scanQr = registerForActivityResult(ScanContract()) { result ->
        val c = result.contents
        if (c != null) handleQr(c)
    }
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onPermissionsDone()
    }
    private val ticker = object : Runnable {
        override fun run() {
            refreshTransfers()
            handler.postDelayed(this, 250)
        }
    }

    // ───────────────────────── Lifecycle ─────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.init(this)
        ui = Ui(this)
        buildUi()
        Engine.start()
        handleIntent(intent)
        requestPerms()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        Engine.listener = this
        Engine.uiVisible = true
        Engine.setLanWanted(Engine.uiMode == Engine.UiMode.LAN)
        render()
        handler.post(ticker)
        if (hasDirectPermission()) {
            Engine.directStart()
            if (Engine.uiMode == Engine.UiMode.DIRECT) Engine.directAutoDiscover()
        }
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        Engine.uiVisible = false
        Engine.listener = null
        Engine.setLanWanted(false)
        super.onStop()
    }

    // ───────────────────────── UI নির্মাণ ─────────────────────────

    private fun buildUi() {
        val p = ui.p
        scroll = ScrollView(this)
        scroll.setBackgroundColor(p.bg)
        scroll.clipToPadding = false
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(28))
        scroll.addView(root)
        setContentView(scroll)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val controller = WindowCompat.getInsetsController(window, scroll)
        controller.isAppearanceLightStatusBars = !p.dark
        controller.isAppearanceLightNavigationBars = !p.dark

        // হেডার
        root.addView(ui.text("Rishat File Share", 24f, p.text, true), ui.lpMW(bottom = 2))
        ownNameText = ui.text("", 13f, p.sub)
        root.addView(ownNameText, ui.lpMW(bottom = 14))

        // স্ট্যাটাস কার্ড
        val statusCard = ui.card()
        val statusRow = LinearLayout(this)
        statusRow.orientation = LinearLayout.HORIZONTAL
        statusRow.gravity = Gravity.CENTER_VERTICAL
        statusDot = View(this)
        val dotLp = LinearLayout.LayoutParams(ui.dp(10), ui.dp(10))
        dotLp.rightMargin = ui.dp(10)
        statusRow.addView(statusDot, dotLp)
        statusText = ui.text("", 14f, p.text)
        statusRow.addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statusCard.addView(statusRow, ui.lpMW())
        disconnectBtn = ui.button("বাতিল করুন", Ui.DANGER) { Engine.disconnect() }
        statusCard.addView(disconnectBtn, ui.lpMW(top = 10))
        root.addView(statusCard, ui.lpMW(bottom = 12))

        // ── সংযোগের অংশ (সংযুক্ত না থাকলে) ──
        connectBox = LinearLayout(this)
        connectBox.orientation = LinearLayout.VERTICAL

        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabDirect = ui.button("Wi-Fi Direct", Ui.PRIMARY) { selectMode(Engine.UiMode.DIRECT) }
        tabLan = ui.button("একই Wi-Fi / হটস্পট", Ui.SECONDARY) { selectMode(Engine.UiMode.LAN) }
        val tabLpA = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        tabLpA.rightMargin = ui.dp(4)
        val tabLpB = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        tabLpB.leftMargin = ui.dp(4)
        tabs.addView(tabDirect, tabLpA)
        tabs.addView(tabLan, tabLpB)
        connectBox.addView(tabs, ui.lpMW(bottom = 12))

        // Wi-Fi Direct প্যানেল
        directPanel = LinearLayout(this)
        directPanel.orientation = LinearLayout.VERTICAL
        directHint = ui.text(
            "দুই ফোনেই অ্যাপ খুলে এই ট্যাবে থাকুন। অন্য ফোনের নাম এলে সেটি চাপুন, তারপর ওই ফোনে ‘গ্রহণ/Accept’ চাপুন। রাউটার বা ইন্টারনেট লাগে না।",
            13f, p.sub
        )
        directPanel.addView(directHint, ui.lpMW(bottom = 10))
        scanBtn = ui.button("আশেপাশের ফোন খুঁজুন", Ui.SECONDARY) { onScanClicked() }
        directPanel.addView(scanBtn, ui.lpMW(bottom = 10))
        directList = LinearLayout(this)
        directList.orientation = LinearLayout.VERTICAL
        directPanel.addView(directList, ui.lpMW())
        connectBox.addView(directPanel, ui.lpMW())

        // LAN প্যানেল
        lanPanel = LinearLayout(this)
        lanPanel.orientation = LinearLayout.VERTICAL
        lanPanel.addView(
            ui.text(
                "দুই ফোন একই Wi-Fi বা একই হটস্পটে থাকলে এই পদ্ধতি ব্যবহার করুন। নিচে অন্য ফোন নিজে নিজেই দেখা যাবে। না এলে QR বা IP ব্যবহার করুন।",
                13f, p.sub
            ), ui.lpMW(bottom = 10)
        )
        lanList = LinearLayout(this)
        lanList.orientation = LinearLayout.VERTICAL
        lanPanel.addView(lanList, ui.lpMW(bottom = 6))

        val qrRow = LinearLayout(this)
        qrRow.orientation = LinearLayout.HORIZONTAL
        val showLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        showLp.rightMargin = ui.dp(4)
        val scanLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        scanLp.leftMargin = ui.dp(4)
        qrRow.addView(ui.button("আমার QR দেখান", Ui.SECONDARY) { showQr() }, showLp)
        qrRow.addView(ui.button("QR স্ক্যান করুন", Ui.SECONDARY) { startScan() }, scanLp)
        lanPanel.addView(qrRow, ui.lpMW(bottom = 8))
        lanPanel.addView(ui.button("IP ঠিকানা দিয়ে যুক্ত হন", Ui.TEXT) { showIpDialog() }, ui.lpMW())
        connectBox.addView(lanPanel, ui.lpMW())

        root.addView(connectBox, ui.lpMW(bottom = 12))

        // ── সংযুক্ত অবস্থা ──
        connectedBox = LinearLayout(this)
        connectedBox.orientation = LinearLayout.VERTICAL
        val peerCard = ui.card()
        peerCard.addView(ui.text("সংযুক্ত ফোন", 12f, p.sub), ui.lpMW())
        peerTitle = ui.text("", 20f, p.text, true)
        peerCard.addView(peerTitle, ui.lpMW(top = 2))
        peerSub = ui.text("", 12f, p.sub)
        peerCard.addView(peerSub, ui.lpMW(top = 2))
        peerCard.addView(ui.button("ফাইল পাঠান", Ui.PRIMARY) { pickFiles.launch(arrayOf("*/*")) }, ui.lpMW(top = 12))
        connectedBox.addView(peerCard, ui.lpMW(bottom = 8))
        root.addView(connectedBox, ui.lpMW(bottom = 4))

        // অপেক্ষমাণ শেয়ার (অন্য অ্যাপ থেকে)
        stagedBox = ui.card()
        stagedText = ui.text("", 13f, p.text)
        stagedBox.addView(stagedText, ui.lpMW())
        stagedBox.addView(ui.button("তালিকা বাতিল", Ui.TEXT) { Engine.clearStaged() }, ui.lpMW(top = 4))
        root.addView(stagedBox, ui.lpMW(bottom = 12))

        // ── ট্রান্সফার ──
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(ui.text("ট্রান্সফার", 18f, p.text, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val clearBtn = ui.button("পরিষ্কার", Ui.TEXT) { Engine.clearFinished() }
        head.addView(clearBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(head, ui.lpMW(bottom = 6))

        emptyText = ui.text("এখনো কোনো ফাইল পাঠানো বা গ্রহণ করা হয়নি।", 13f, p.sub)
        root.addView(emptyText, ui.lpMW(bottom = 8))
        transfersBox = LinearLayout(this)
        transfersBox.orientation = LinearLayout.VERTICAL
        root.addView(transfersBox, ui.lpMW())

        root.addView(
            ui.text("প্রাপ্ত ফাইল সেভ হয়: Download › Rishat File Share (প্রতিটি ফাইল SHA-256 দিয়ে যাচাই করা হয়)", 12f, p.sub),
            ui.lpMW(top = 16)
        )
    }

    // ───────────────────────── রেন্ডার ─────────────────────────

    private fun render() {
        val p = ui.p
        val state = Engine.connState
        val connected = state == Engine.ConnState.CONNECTED
        val connecting = state == Engine.ConnState.CONNECTING

        var own = "আপনার ফোন: " + Engine.deviceName
        if (Engine.ownDirectName.isNotBlank() && Engine.ownDirectName != Engine.deviceName) {
            own += " • Wi-Fi Direct নাম: " + Engine.ownDirectName
        }
        ownNameText.text = own

        statusText.text = Engine.status
        val dotColor = when (state) {
            Engine.ConnState.CONNECTED -> p.ok
            Engine.ConnState.CONNECTING -> p.warn
            else -> p.sub
        }
        statusDot.background = ui.dot(dotColor)
        disconnectBtn.visibility = if (connected || connecting) View.VISIBLE else View.GONE
        disconnectBtn.text = if (connected) "সংযোগ বিচ্ছিন্ন করুন" else "বাতিল করুন"

        connectBox.visibility = if (connected) View.GONE else View.VISIBLE
        connectedBox.visibility = if (connected) View.VISIBLE else View.GONE

        val directMode = Engine.uiMode == Engine.UiMode.DIRECT
        ui.applyStyle(tabDirect, if (directMode) Ui.PRIMARY else Ui.SECONDARY)
        ui.applyStyle(tabLan, if (directMode) Ui.SECONDARY else Ui.PRIMARY)
        directPanel.visibility = if (directMode) View.VISIBLE else View.GONE
        lanPanel.visibility = if (directMode) View.GONE else View.VISIBLE

        if (connected) {
            peerTitle.text = Engine.peerName
            peerSub.text = if (Engine.peerLink == LinkType.DIRECT) "Wi-Fi Direct • সরাসরি P2P লিংক"
            else "একই নেটওয়ার্ক • সরাসরি LAN P2P লিংক"
        }

        val staged = Engine.pendingShare.size
        stagedBox.visibility = if (staged > 0) View.VISIBLE else View.GONE
        stagedText.text = "$staged টি ফাইল পাঠানোর জন্য অপেক্ষায়। অন্য ফোনের সঙ্গে সংযুক্ত হলেই স্বয়ংক্রিয়ভাবে পাঠানো হবে।"

        scanBtn.text = if (Engine.directScanning) "খোঁজা হচ্ছে… আবার খুঁজুন" else "আশেপাশের ফোন খুঁজুন"
        if (!Engine.directSupported()) directHint.text = "এই ফোনে Wi-Fi Direct সমর্থিত নয়। ‘একই Wi-Fi / হটস্পট’ ট্যাব ব্যবহার করুন।"

        renderDirectList()
        renderLanList()
    }

    private fun renderDirectList() {
        directList.removeAllViews()
        val peers = Engine.directPeers
        if (peers.isEmpty()) {
            val msg = if (Engine.directScanning) "খোঁজা হচ্ছে…" else "এখনো কোনো ফোন পাওয়া যায়নি। অন্য ফোনেও অ্যাপ খুলে ‘খুঁজুন’ চাপুন।"
            directList.addView(ui.text(msg, 13f, ui.p.sub), ui.lpMW())
            return
        }
        for (d in peers) {
            val name = if (d.deviceName.isNullOrBlank()) "নামহীন ডিভাইস" else d.deviceName
            directList.addView(peerRow(name, deviceStatus(d.status)) { Engine.directConnect(d) }, ui.lpMW(bottom = 8))
        }
    }

    private fun renderLanList() {
        lanList.removeAllViews()
        val peers = Engine.lanPeers
        if (peers.isEmpty()) {
            lanList.addView(ui.text("নেটওয়ার্কে খোঁজা হচ্ছে… (অন্য ফোনে অ্যাপ খোলা থাকতে হবে)", 13f, ui.p.sub), ui.lpMW())
            return
        }
        for (peer in peers) {
            lanList.addView(peerRow(peer.name, peer.host) { Engine.connectLanPeer(peer) }, ui.lpMW(bottom = 8))
        }
    }

    private fun peerRow(title: String, sub: String, onClick: () -> Unit): View {
        val c = ui.card()
        c.isClickable = true
        c.background = ui.ripple(ui.rounded(ui.p.card, 14, ui.p.stroke))
        c.addView(ui.text(title, 15f, ui.p.text, true), ui.lpMW())
        c.addView(ui.text(sub, 12f, ui.p.sub), ui.lpMW(top = 2))
        c.setOnClickListener { onClick() }
        return c
    }

    private fun deviceStatus(value: Int): String = when (value) {
        WifiP2pDevice.AVAILABLE -> "সংযোগের জন্য প্রস্তুত — চাপুন"
        WifiP2pDevice.INVITED -> "আমন্ত্রণ পাঠানো হয়েছে"
        WifiP2pDevice.CONNECTED -> "সংযুক্ত"
        WifiP2pDevice.FAILED -> "সংযোগ ব্যর্থ — আবার চাপুন"
        WifiP2pDevice.UNAVAILABLE -> "এখন অনুপলব্ধ"
        else -> "অবস্থা অজানা"
    }

    // ───────────────────────── ট্রান্সফার তালিকা ─────────────────────────

    private fun refreshTransfers() {
        val list = Engine.transfers.toList().asReversed()
        val key = list.joinToString(",") { it.uid.toString() }
        if (key != lastRowKey) {
            lastRowKey = key
            transfersBox.removeAllViews()
            val alive = HashSet<Long>()
            for (item in list) {
                alive.add(item.uid)
                val row = rows.getOrPut(item.uid) { Row(item) }
                transfersBox.addView(row.view, ui.lpMW(bottom = 8))
            }
            rows.keys.retainAll(alive)
            emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
        for (item in list) rows[item.uid]?.update()
    }

    private inner class Row(val item: TransferItem) {
        val view: LinearLayout = ui.card()
        private val title = ui.text("", 14f, ui.p.text, true)
        private val stateTv = ui.text("", 12f, ui.p.sub)
        private val bar = ui.progressBar()
        private val detail = ui.text("", 12f, ui.p.sub)
        private var mode = 0 // 0 = কিছু নয়, 1 = বাতিল, 2 = খুলুন
        private val action = ui.button("", Ui.SECONDARY) { onAction() }

        init {
            val top = LinearLayout(this@MainActivity)
            top.orientation = LinearLayout.HORIZONTAL
            top.gravity = Gravity.CENTER_VERTICAL
            title.maxLines = 2
            title.ellipsize = TextUtils.TruncateAt.MIDDLE
            top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val stLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            stLp.leftMargin = ui.dp(8)
            top.addView(stateTv, stLp)
            view.addView(top, ui.lpMW())
            val barLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(8))
            barLp.topMargin = ui.dp(8)
            view.addView(bar, barLp)
            view.addView(detail, ui.lpMW(top = 6))
            view.addView(action, ui.lpMW(top = 8))
        }

        fun update() {
            val p = ui.p
            item.sampleSpeed()
            val st = item.state
            title.text = (if (item.outgoing) "↑ " else "↓ ") + item.name

            stateTv.text = when (st) {
                TState.QUEUED -> "সারিতে"
                TState.ACTIVE -> item.percent().toString() + "%"
                TState.VERIFYING -> "যাচাই হচ্ছে…"
                TState.DONE -> if (item.outgoing) "পাঠানো হয়েছে ✓" else "গ্রহণ সম্পন্ন ✓"
                TState.FAILED -> "ব্যর্থ"
                TState.CANCELED -> "বাতিল"
            }
            stateTv.setTextColor(
                when (st) {
                    TState.DONE -> p.ok
                    TState.FAILED -> p.danger
                    TState.CANCELED -> p.sub
                    else -> p.accent
                }
            )

            val live = st == TState.ACTIVE || st == TState.QUEUED || st == TState.VERIFYING
            bar.visibility = if (live) View.VISIBLE else View.GONE
            if (live) {
                bar.progress = if (item.size > 0) ((item.bytes * 1000) / item.size).toInt().coerceIn(0, 1000) else 0
            }

            detail.text = when (st) {
                TState.QUEUED -> fmtSize(item.size) + " • সারিতে অপেক্ষা"
                TState.ACTIVE -> {
                    var t = fmtSize(item.bytes) + " / " + fmtSize(item.size) + " • " + fmtSpeed(item.speed)
                    if (item.speed > 1024) {
                        val remain = item.size - item.bytes
                        t += " • বাকি " + fmtEta((remain / item.speed).toLong())
                    }
                    t
                }
                TState.VERIFYING -> fmtSize(item.size) + " • অন্য ফোনে যাচাই হচ্ছে"
                TState.DONE ->
                    if (item.outgoing) fmtSize(item.size) + " • সফলভাবে পাঠানো হয়েছে"
                    else fmtSize(item.size) + " • সংরক্ষিত: " + item.savedLabel
                TState.FAILED -> item.message.ifBlank { "ফাইল স্থানান্তর ব্যর্থ" }
                TState.CANCELED -> item.message.ifBlank { "বাতিল করা হয়েছে" }
            }

            when {
                st == TState.ACTIVE || st == TState.QUEUED -> {
                    mode = 1
                    action.text = "বাতিল"
                    action.visibility = View.VISIBLE
                }
                st == TState.DONE && !item.outgoing && item.savedUri != null -> {
                    mode = 2
                    action.text = "খুলুন"
                    action.visibility = View.VISIBLE
                }
                else -> {
                    mode = 0
                    action.visibility = View.GONE
                }
            }
        }

        private fun onAction() {
            when (mode) {
                1 -> Engine.cancelTransfer(item)
                2 -> openFile(item)
            }
        }
    }

    private fun openFile(item: TransferItem) {
        val uri = item.savedUri ?: return
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(uri, guessMime(item.name))
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(i)
        } catch (_: Exception) {
            toast("এই ফাইল খোলার মতো কোনো অ্যাপ নেই")
        }
    }

    // ───────────────────────── অ্যাকশন ─────────────────────────

    private fun selectMode(m: Engine.UiMode) {
        Engine.uiMode = m
        Engine.setLanWanted(m == Engine.UiMode.LAN)
        if (m == Engine.UiMode.DIRECT && hasDirectPermission()) {
            Engine.directStart()
            Engine.directAutoDiscover()
        }
        render()
    }

    private fun onScanClicked() {
        if (!hasDirectPermission()) {
            requestPerms()
            return
        }
        Engine.directAutoDiscover()
        Engine.directDiscover()
    }

    private fun startScan() {
        val o = ScanOptions()
        o.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        o.setPrompt("অন্য ফোনের QR ফ্রেমে আনুন")
        o.setBeepEnabled(false)
        o.setOrientationLocked(false)
        scanQr.launch(o)
    }

    private fun handleQr(contents: String) {
        val u: Uri? = try { Uri.parse(contents) } catch (_: Exception) { null }
        if (u == null || u.scheme != "rfs") {
            toast("এটি Rishat File Share-এর QR নয়")
            return
        }
        val host = u.getQueryParameter("h")
        val port = u.getQueryParameter("p")?.toIntOrNull()
        val tok = u.getQueryParameter("t") ?: ""
        val name = u.getQueryParameter("n") ?: ""
        if (host.isNullOrBlank() || port == null) {
            toast("QR অবৈধ")
            return
        }
        Engine.connectFromQr(host, port, tok, name)
    }

    private fun showQr() {
        val payload = Engine.createPairing()
        if (payload == null) {
            toast("আগে Wi-Fi বা হটস্পটে যুক্ত হন")
            return
        }
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(4))
        val img = ImageView(this)
        img.setImageBitmap(qrBitmap(payload, 720))
        img.setBackgroundColor(Color.WHITE)
        img.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
        box.addView(img, LinearLayout.LayoutParams(ui.dp(260), ui.dp(260)))
        val note = ui.text(
            "অন্য ফোনে ‘QR স্ক্যান করুন’ চাপুন। QR ৫ মিনিট বৈধ।\nম্যানুয়াল IP: " + Engine.localAddressText(),
            13f, ui.p.sub
        )
        note.gravity = Gravity.CENTER
        box.addView(note, ui.lpMW(top = 10))
        AlertDialog.Builder(this)
            .setTitle("এই ফোনের QR")
            .setView(box)
            .setPositiveButton("বন্ধ করুন", null)
            .setOnDismissListener { Engine.clearPairing() }
            .show()
    }

    private fun showIpDialog() {
        val input = EditText(this)
        input.hint = "যেমন 192.168.0.12 অথবা 192.168.0.12:39471"
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val box = LinearLayout(this)
        box.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
        box.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(this)
            .setTitle("অন্য ফোনের IP ঠিকানা")
            .setView(box)
            .setPositiveButton("যুক্ত হন") { _, _ ->
                val raw = input.text.toString().trim()
                if (raw.isNotEmpty()) {
                    val host = raw.substringBefore(':')
                    val port = if (raw.contains(':')) raw.substringAfter(':').toIntOrNull() else Proto.DEFAULT_PORT
                    if (host.isBlank() || port == null) toast("ঠিকানা ঠিক নয়")
                    else Engine.connectManual(host, port)
                }
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    private fun qrBitmap(text: String, size: Int): Bitmap {
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
        )
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val w = m.width
        val h = m.height
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val o = y * w
            for (x in 0 until w) px[o + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    // ───────────────────────── Engine.Listener ─────────────────────────

    override fun onEngineChanged() {
        render()
    }

    override fun onMessage(text: String) {
        toast(text)
    }

    override fun onApprovalRequest(request: Engine.ApprovalRequest) {
        AlertDialog.Builder(this)
            .setTitle("সংযোগের অনুরোধ")
            .setMessage("“" + request.peerName + "” আপনার ফোনের সঙ্গে সংযুক্ত হতে চায়। অনুমতি দেবেন?")
            .setCancelable(false)
            .setPositiveButton("অনুমতি দিন") { _, _ -> request.future.complete(true) }
            .setNegativeButton("প্রত্যাখ্যান") { _, _ -> request.future.complete(false) }
            .show()
    }

    // ───────────────────────── পারমিশন ও ইনটেন্ট ─────────────────────────

    private fun directPerm(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION

    private fun hasDirectPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, directPerm()) == PackageManager.PERMISSION_GRANTED

    private fun requestPerms() {
        val list = ArrayList<String>()
        list.add(directPerm())
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 36) list.add("android.permission.ACCESS_LOCAL_NETWORK")
        val missing = list.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) onPermissionsDone() else permLauncher.launch(missing.toTypedArray())
    }

    private fun onPermissionsDone() {
        if (hasDirectPermission()) {
            Engine.directStart()
            if (Engine.uiMode == Engine.UiMode.DIRECT) Engine.directAutoDiscover()
        } else {
            toast("Wi-Fi Direct চালাতে ‘Nearby devices’ (বা Location) অনুমতি দিন")
        }
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val action = i.action
        if (action == Intent.ACTION_SEND) {
            val u = IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
            if (u != null) Engine.stage(listOf(u))
        } else if (action == Intent.ACTION_SEND_MULTIPLE) {
            val l = IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
            if (l != null && l.isNotEmpty()) Engine.stage(l.toList())
        }
        if (action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE) i.action = null
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
