package com.rishat.fileshare

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.database.Cursor
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Rishat File Share — offline Wi-Fi Direct transfer.
 * No internet, QR, cloud upload, signaling server, STUN, or TURN is used.
 * Wi-Fi Direct creates a local peer-to-peer link between Android devices.
 */
class MainActivity : AppCompatActivity() {
    companion object {
        private const val REQUEST_PERMISSIONS = 410
        private const val REQUEST_PICK_FILE = 411
        private const val REQUEST_SAVE_FILE = 412
        private const val REQUEST_BROWSER_PICK_FILE = 414
        private const val SOCKET_PORT = 39471
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_NAME_BYTES = 4096
        private const val CMD_FILE = 1
        private const val HISTORY_KEY = "transfer_history"
    }

    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var status: TextView
    private lateinit var devicesBox: LinearLayout
    private lateinit var receivedBox: LinearLayout
    private lateinit var scanButton: Button
    private lateinit var sendButton: Button

    private val ioExecutor = Executors.newCachedThreadPool()
    private val socketLock = Any()
    @Volatile private var socket: Socket? = null
    @Volatile private var input: DataInputStream? = null
    @Volatile private var output: DataOutputStream? = null
    @Volatile private var isGroupOwner = false
    @Volatile private var isConnected = false
    private var serverSocket: ServerSocket? = null
    private val acceptStarted = AtomicBoolean(false)
    private val connectAttempting = AtomicBoolean(false)
    private val receiverStarted = AtomicBoolean(false)
    private var pendingSaveFile: File? = null
    private val discoveredDevices = linkedMapOf<String, WifiP2pDevice>()
    private data class BrowserFile(val name: String, val uri: Uri, val size: Long, val mime: String)

    private val stateListener = WifiP2pManager.PeerListListener { peers: WifiP2pDeviceList ->
        discoveredDevices.clear()
        peers.deviceList.forEach { discoveredDevices[it.deviceAddress] = it }
        renderDevices()
        if (peers.deviceList.isEmpty()) {
            setStatus("কোনো ডিভাইস পাওয়া যায়নি। দুই ফোনে Wi-Fi চালু করে আবার স্ক্যান করুন।")
        } else {
            setStatus("${peers.deviceList.size}টি ডিভাইস পাওয়া গেছে। অন্য ফোনটি নির্বাচন করুন।")
        }
    }

    private val connectionListener = WifiP2pManager.ConnectionInfoListener { info: WifiP2pInfo ->
        if (info.groupFormed) {
            isGroupOwner = info.isGroupOwner
            setStatus(
                if (info.isGroupOwner) "Wi-Fi Direct সংযুক্ত। এই ফোন Group Owner; অন্য ফোনের সংযোগের অপেক্ষায়…" 
                else "Wi-Fi Direct সংযুক্ত। সরাসরি P2P লিংক তৈরি হচ্ছে…"
            )
            if (info.isGroupOwner) startAccepting()
            else connectToOwner(info.groupOwnerAddress.hostAddress ?: return@ConnectionInfoListener)
        } else {
            setStatus("Wi-Fi Direct গ্রুপ তৈরি হয়নি। আবার ডিভাইস নির্বাচন করে সংযোগ দিন।")
        }
    }

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) setStatus("Wi-Fi Direct চালু আছে। স্ক্যান করুন।")
                    else setStatus("ফোনের Wi-Fi Direct বন্ধ। Wi-Fi চালু করুন।")
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (hasRequiredPermissions()) {
                        try { manager.requestPeers(channel, stateListener) }
                        catch (_: SecurityException) { setStatus("Wi-Fi permission দিন।") }
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    try { manager.requestConnectionInfo(channel, connectionListener) }
                    catch (_: SecurityException) { setStatus("Wi-Fi permission দিন।") }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> Unit
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper, null)
        buildUi()
        restoreReceivedFiles()
        requestRequiredPermissions()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(24))
            setBackgroundColor(0xFFF3F7FC.toInt())
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "Rishat File Share"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(0xFF17365D.toInt())
        }, matchWrap(bottom = 6))

        root.addView(TextView(this).apply {
            text = "Wi-Fi Direct • ইন্টারনেট বা রাউটার ছাড়াই মোবাইল-টু-মোবাইল ফাইল শেয়ার"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFF404A57.toInt())
        }, matchWrap(bottom = 18))

        root.addView(Button(this).apply {
            text = "📁  বিল্ট-ইন ফাইল ব্রাউজার"
            isAllCaps = false
            setOnClickListener { showFileBrowserCategories() }
        }, matchWrap(bottom = 8))

        root.addView(Button(this).apply {
            text = "⚡  Smart Share — ফাইল বেছে পাঠান"
            isAllCaps = false
            setOnClickListener { chooseFileForSmartShare() }
        }, matchWrap(bottom = 8))

        root.addView(Button(this).apply {
            text = "🕘  ট্রান্সফার ইতিহাস"
            isAllCaps = false
            setOnClickListener { showTransferHistory() }
        }, matchWrap(bottom = 8))

        root.addView(Button(this).apply {
            text = "📊  স্টোরেজ বিশ্লেষণ"
            isAllCaps = false
            setOnClickListener { showStorageAnalyzer() }
        }, matchWrap(bottom = 8))

        scanButton = Button(this).apply {
            text = "📡  আশেপাশের ফোন খুঁজুন"
            isAllCaps = false
            setOnClickListener { discoverPeers() }
        }
        root.addView(scanButton, matchWrap(bottom = 8))

        root.addView(TextView(this).apply {
            text = "পাওয়া ডিভাইস"
            textSize = 18f
            setTextColor(0xFF17365D.toInt())
        }, matchWrap(bottom = 4))
        devicesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(devicesBox, matchWrap(bottom = 12))

        sendButton = Button(this).apply {
            text = "📤  ফাইল নির্বাচন করে পাঠান"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { chooseFile() }
        }
        root.addView(sendButton, matchWrap(bottom = 8))

        root.addView(Button(this).apply {
            text = "📦  এই অ্যাপটি শেয়ার করুন (Bluetooth / Wi-Fi)"
            isAllCaps = false
            setOnClickListener { shareInstalledApk() }
        }, matchWrap(bottom = 8))

        root.addView(Button(this).apply {
            text = "🧩  ইনস্টল করা যেকোনো অ্যাপ শেয়ার করুন"
            isAllCaps = false
            setOnClickListener { showInstalledAppsForSharing() }
        }, matchWrap(bottom = 8))

        root.addView(Button(this).apply {
            text = "🔗  একই Wi-Fi-তে WebRTC দিয়ে ফাইল পাঠান"
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent(this@MainActivity, WebRtcActivity::class.java))
            }
        }, matchWrap(bottom = 8))

        root.addView(Button(this).apply {
            text = "⛔  সংযোগ বিচ্ছিন্ন করুন"
            isAllCaps = false
            setOnClickListener { disconnect() }
        }, matchWrap(bottom = 8))

        status = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF243447.toInt())
            setPadding(0, dp(12), 0, dp(12))
            text = "অনুমতি দেওয়ার পর দুই ফোনে স্ক্যান করুন।"
        }
        root.addView(status, matchWrap(bottom = 8))

        root.addView(TextView(this).apply {
            text = "গ্রহণ করা ফাইল"
            textSize = 18f
            setTextColor(0xFF17365D.toInt())
        }, matchWrap(bottom = 4))
        receivedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(receivedBox, matchWrap())

        root.addView(TextView(this).apply {
            text = "ব্যবহার: দুই ফোনে অ্যাপ খুলে Wi-Fi চালু রাখুন (রাউটার/ইন্টারনেট লাগবে না)। একটি ফোনে স্ক্যান করুন, অন্য ফোনের নাম এলে সেটি চাপুন। সংযোগ হলে ফাইল পাঠান। Wi-Fi Direct সমর্থন ও Android নির্মাতাভেদে আচরণ ভিন্ন হতে পারে।"
            textSize = 13f
            setTextColor(0xFF5A6573.toInt())
            setPadding(0, dp(16), 0, 0)
        }, matchWrap())
        styleUi(root)
    }

    /** Shares this installed app's APK using Android's standard share sheet.
     * The recipient may need to allow installs from the app used to receive it.
     */
    private fun shareInstalledApk() {
        try {
            val sourceApk = File(applicationInfo.sourceDir)
            if (!sourceApk.isFile || sourceApk.length() <= 0L) {
                Toast.makeText(this, "অ্যাপের APK ফাইল পাওয়া যায়নি।", Toast.LENGTH_LONG).show()
                return
            }
            val shareDir = File(cacheDir, "app-share").apply { mkdirs() }
            val sharedApk = File(shareDir, "Rishat-File-Share.apk")
            sourceApk.inputStream().use { input ->
                sharedApk.outputStream().use { output -> input.copyTo(output) }
            }
            val apkUri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                sharedApk
            )
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, apkUri)
                clipData = android.content.ClipData.newUri(contentResolver, "Rishat File Share APK", apkUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(sendIntent, "Bluetooth বা Wi-Fi দিয়ে অ্যাপ শেয়ার করুন"))
        } catch (e: Exception) {
            Toast.makeText(this, "অ্যাপ শেয়ার করা যায়নি: ${e.localizedMessage ?: "অজানা সমস্যা"}", Toast.LENGTH_LONG).show()
        }
    }

    /** Lists installed apps and shares the selected app's APK through Android's share sheet. */
    @Suppress("DEPRECATION")
    private fun showInstalledAppsForSharing() {
        try {
            val pm = packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != packageName && it.enabled }
                .mapNotNull { info ->
                    try {
                        val apk = File(info.sourceDir)
                        if (apk.isFile && apk.length() > 0L) Triple(pm.getApplicationLabel(info).toString(), info, apk) else null
                    } catch (_: Exception) { null }
                }
                .sortedBy { it.first.lowercase() }

            if (apps.isEmpty()) {
                Toast.makeText(this, "শেয়ার করার মতো ইনস্টল করা অ্যাপ পাওয়া যায়নি।", Toast.LENGTH_LONG).show()
                return
            }

            val labels = apps.map { "${it.first}  •  ${formatSize(it.third.length())}" }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("কোন অ্যাপটি শেয়ার করবেন?")
                .setItems(labels) { _, which -> shareSelectedAppApk(apps[which].first, apps[which].second.packageName, apps[which].third) }
                .setNegativeButton("বাতিল", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "অ্যাপের তালিকা খোলা যায়নি: ${e.localizedMessage ?: "অজানা সমস্যা"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareSelectedAppApk(appLabel: String, packageNameToShare: String, sourceApk: File) {
        try {
            val shareDir = File(cacheDir, "app-share").apply { mkdirs() }
            val safeName = appLabel.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifBlank { "Android-App" }
            val sharedApk = File(shareDir, "$safeName.apk")
            sourceApk.inputStream().use { input -> sharedApk.outputStream().use { output -> input.copyTo(output) } }
            val apkUri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", sharedApk)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, apkUri)
                putExtra(Intent.EXTRA_SUBJECT, "$appLabel APK")
                clipData = android.content.ClipData.newUri(contentResolver, "$appLabel APK", apkUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(sendIntent, "$appLabel — Bluetooth / Wi-Fi দিয়ে শেয়ার করুন"))
            val splitCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try { packageManager.getApplicationInfo(packageNameToShare, 0).splitSourceDirs?.size ?: 0 } catch (_: Exception) { 0 }
            } else 0
            if (splitCount > 0) Toast.makeText(this, "সতর্কতা: এই অ্যাপে অতিরিক্ত split APK আছে; শুধু base APK দিয়ে অন্য ফোনে ইনস্টল নাও হতে পারে।", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "APK শেয়ার করা যায়নি: ${e.localizedMessage ?: "অজানা সমস্যা"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun styleUi(view: android.view.View) {
        when (view) {
            is Button -> {
                view.background = GradientDrawable().apply {
                    setColor(0xFFE5F7FF.toInt())
                    setStroke(dp(1), 0xFF91DFF5.toInt())
                    cornerRadius = dp(2).toFloat()
                }
                view.setTextColor(0xFF12304A.toInt())
                view.textSize = 15f
                view.setPadding(dp(16), dp(12), dp(16), dp(12))
                view.minHeight = dp(58)
                view.elevation = dp(1).toFloat()
            }
            is TextView -> {
                if (view.text.toString() == "Rishat File Share") {
                    view.textSize = 28f
                    view.setTextColor(0xFF102A43.toInt())
                    view.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                }
            }
            is LinearLayout -> for (i in 0 until view.childCount) styleUi(view.getChildAt(i))
        }
    }

    private fun renderDevices() {
        runOnUiThread {
            devicesBox.removeAllViews()
            if (discoveredDevices.isEmpty()) {
                devicesBox.addView(TextView(this).apply {
                    text = "এখনো কোনো ডিভাইস নেই।"
                    setTextColor(0xFF5A6573.toInt())
                }, matchWrap())
                return@runOnUiThread
            }
            discoveredDevices.values.forEach { device ->
                val button = Button(this).apply {
                    text = "${device.deviceName.ifBlank { "নামহীন ডিভাইস" }}\n${device.deviceAddress}\n${deviceStatus(device.status)}"
                    isAllCaps = false
                    setOnClickListener { connectToDevice(device) }
                }
                devicesBox.addView(button, matchWrap(bottom = 6))
            }
        }
    }

    private fun deviceStatus(value: Int): String = when (value) {
        WifiP2pDevice.AVAILABLE -> "পাওয়া গেছে"
        WifiP2pDevice.INVITED -> "আমন্ত্রণ পাঠানো হয়েছে"
        WifiP2pDevice.CONNECTED -> "সংযুক্ত"
        WifiP2pDevice.FAILED -> "সংযোগ ব্যর্থ"
        WifiP2pDevice.UNAVAILABLE -> "এখন অনুপলব্ধ"
        else -> "অবস্থা অজানা"
    }

    private fun requestRequiredPermissions() {
        val needed = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_PERMISSIONS)
        else registerP2pReceiver()
    }

    private fun requiredPermissions(): List<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        else list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        return list
    }

    private fun hasRequiredPermissions() = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (hasRequiredPermissions()) registerP2pReceiver()
            else setStatus("Wi-Fi Direct চালাতে প্রয়োজনীয় Nearby Wi-Fi/Location permission দিতে হবে।")
        }
    }

    private fun registerP2pReceiver() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        if (receiverRegistered) return
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(p2pReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(p2pReceiver, filter)
        }
        receiverRegistered = true
        setStatus("প্রস্তুত। দুই ফোনে Wi-Fi চালু করে ‘আশেপাশের ফোন খুঁজুন’ চাপুন।")
    }

    private fun discoverPeers() {
        if (!hasRequiredPermissions()) {
            requestRequiredPermissions()
            return
        }
        setStatus("Wi-Fi Direct ডিভাইস খোঁজা হচ্ছে…")
        try {
            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { setStatus("স্ক্যান শুরু হয়েছে। কিছুক্ষণ অপেক্ষা করুন…") }
                override fun onFailure(reason: Int) { setStatus("স্ক্যান ব্যর্থ: ${reasonText(reason)}") }
            })
        } catch (e: SecurityException) {
            setStatus("Wi-Fi permission পাওয়া যায়নি।")
        }
    }

    private fun connectToDevice(device: WifiP2pDevice) {
        if (!hasRequiredPermissions()) {
            requestRequiredPermissions()
            return
        }
        setStatus("${device.deviceName} ফোনে সংযোগের অনুরোধ পাঠানো হচ্ছে…")
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        try {
            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { setStatus("সংযোগের অনুরোধ পাঠানো হয়েছে। অন্য ফোনে অনুমতি/সংযোগ সম্পন্ন হতে দিন।") }
                override fun onFailure(reason: Int) { setStatus("সংযোগ ব্যর্থ: ${reasonText(reason)}") }
            })
        } catch (e: SecurityException) {
            setStatus("Wi-Fi permission পাওয়া যায়নি।")
        }
    }

    private fun reasonText(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "এই ফোনে Wi-Fi Direct সমর্থিত নয়"
        WifiP2pManager.BUSY -> "সিস্টেম ব্যস্ত; কিছুক্ষণ পরে আবার চেষ্টা করুন"
        WifiP2pManager.ERROR -> "সিস্টেম ত্রুটি"
        else -> "কোড $reason"
    }

    private fun startAccepting() {
        if (!acceptStarted.compareAndSet(false, true)) return
        ioExecutor.execute {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(SOCKET_PORT), 1)
                serverSocket = server
                setStatus("Wi-Fi Direct গ্রুপ তৈরি। অন্য ফোনের ডেটা-লিংকের অপেক্ষায়…")
                val client = server.accept()
                installSocket(client)
            } catch (e: Exception) {
                acceptStarted.set(false)
                if (!isFinishing) setStatus("লোকাল সংযোগ ব্যর্থ: ${e.message ?: "অজানা ত্রুটি"}")
            }
        }
    }

    private fun connectToOwner(host: String) {
        if (isConnected || !connectAttempting.compareAndSet(false, true)) return
        ioExecutor.execute {
            var lastError: Exception? = null
            try {
                repeat(30) {
                    if (isConnected || isFinishing) return@execute
                    try {
                        val client = Socket()
                        client.tcpNoDelay = true
                        client.keepAlive = true
                        client.connect(InetSocketAddress(host, SOCKET_PORT), 4000)
                        installSocket(client)
                        return@execute
                    } catch (e: Exception) {
                        lastError = e
                        try { Thread.sleep(750) } catch (_: InterruptedException) { return@execute }
                    }
                }
                if (!isConnected && !isFinishing) setStatus("ডেটা সংযোগ এখনও তৈরি হয়নি: ${lastError?.message ?: "সময় শেষ"}। দুই ফোন কাছাকাছি রেখে আবার চেষ্টা করুন।")
            } finally {
                connectAttempting.set(false)
            }
        }
    }

    private fun installSocket(newSocket: Socket) {
        synchronized(socketLock) {
            if (isConnected) {
                try { newSocket.close() } catch (_: Exception) {}
                return
            }
            newSocket.tcpNoDelay = true
            newSocket.keepAlive = true
            socket = newSocket
            input = DataInputStream(BufferedInputStream(newSocket.getInputStream()))
            output = DataOutputStream(BufferedOutputStream(newSocket.getOutputStream()))
            isConnected = true
        }
        runOnUiThread {
            sendButton.isEnabled = true
            setStatus("সরাসরি Wi-Fi Direct সংযোগ তৈরি হয়েছে। এখন ফাইল পাঠাতে পারবেন।")
        }
        if (receiverStarted.compareAndSet(false, true)) ioExecutor.execute { readIncomingLoop() }
    }

    private fun showFileBrowserCategories() {
        val categories = arrayOf("🖼️  ছবি / Images", "🎬  ভিডিও / Videos", "🎵  অডিও / Audio", "📄  ডকুমেন্ট ও অন্যান্য ফাইল", "⬇️  Downloads / যেকোনো ফাইল", "📦  ইনস্টল করা APK অ্যাপ")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("বিল্ট-ইন ফাইল ব্রাউজার")
            .setItems(categories) { _, which ->
                when (which) {
                    0 -> showMediaFiles(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*", "ছবি")
                    1 -> showMediaFiles(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*", "ভিডিও")
                    2 -> showMediaFiles(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio/*", "অডিও")
                    3 -> showDocumentsPicker()
                    4 -> showDownloads()
                    5 -> showInstalledAppsInBrowser()
                }
            }
            .setNegativeButton("বন্ধ", null).show()
    }

    private fun showDocumentsPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "text/*", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.openxmlformats-officedocument.presentationml.presentation"))
        }
        startActivityForResult(intent, REQUEST_BROWSER_PICK_FILE)
    }

    private fun chooseFileForSmartShare() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_BROWSER_PICK_FILE)
    }

    private fun showStorageAnalyzer() {
        try {
            val path = Environment.getDataDirectory().path
            val stat = StatFs(path)
            val total = stat.blockCountLong * stat.blockSizeLong
            val available = stat.availableBlocksLong * stat.blockSizeLong
            val used = (total - available).coerceAtLeast(0L)
            val percent = if (total > 0L) (used * 100 / total).toInt() else 0
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("স্টোরেজ বিশ্লেষণ")
                .setMessage("ডিভাইসের ডেটা পার্টিশন\n\nমোট: ${formatSize(total)}\nব্যবহৃত: ${formatSize(used)} ($percent%)\nখালি: ${formatSize(available)}\n\nনোট: Android-এর সিস্টেম পার্টিশন ও অ্যাপ অনুমতির কারণে এটি সম্পূর্ণ মিডিয়া ফাইলের পৃথক হিসাব নয়।")
                .setPositiveButton("ঠিক আছে", null)
                .show()
        } catch (e: Exception) {
            toast("স্টোরেজ তথ্য পড়া যায়নি: ${e.localizedMessage ?: "অজানা সমস্যা"}")
        }
    }

    private fun showTransferHistory() {
        val history = getSharedPreferences("rishat_transfer", MODE_PRIVATE).getString(HISTORY_KEY, "").orEmpty()
        val entries = history.lines().filter { it.isNotBlank() }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("ট্রান্সফার ইতিহাস (${entries.size})")
            .setMessage(if (entries.isEmpty()) "এখনো কোনো ট্রান্সফার হয়নি।" else entries.joinToString("\n\n"))
            .setPositiveButton("ঠিক আছে", null)
            .setNeutralButton("ইতিহাস মুছুন") { _, _ ->
                getSharedPreferences("rishat_transfer", MODE_PRIVATE).edit().remove(HISTORY_KEY).apply()
                toast("ট্রান্সফার ইতিহাস মুছে ফেলা হয়েছে।")
            }.show()
    }

    private fun addHistory(direction: String, name: String, size: Long, result: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val line = "$stamp • $direction • ${name.replace('\n', ' ').take(100)} • ${formatSize(size)} • $result"
        val prefs = getSharedPreferences("rishat_transfer", MODE_PRIVATE)
        val old = prefs.getString(HISTORY_KEY, "").orEmpty().lines().filter { it.isNotBlank() }
        prefs.edit().putString(HISTORY_KEY, (listOf(line) + old).take(50).joinToString("\n")).apply()
    }

    private fun sha256Uri(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        } ?: throw IOException("ফাইল খোলা যায়নি")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { stream ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun showMediaFiles(collection: Uri, mime: String, heading: String) {
        val relevantPermission = when {
            Build.VERSION.SDK_INT >= 33 && mime.startsWith("image/") -> Manifest.permission.READ_MEDIA_IMAGES
            Build.VERSION.SDK_INT >= 33 && mime.startsWith("audio/") -> Manifest.permission.READ_MEDIA_AUDIO
            Build.VERSION.SDK_INT >= 33 -> Manifest.permission.READ_MEDIA_VIDEO
            else -> Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, relevantPermission) != PackageManager.PERMISSION_GRANTED) {
            val permissions = when {
                Build.VERSION.SDK_INT >= 34 -> arrayOf(
                    relevantPermission,
                    "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
                )
                Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
                else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            ActivityCompat.requestPermissions(this, permissions, 413)
            toast("মিডিয়া ফাইল দেখার অনুমতি দিন, তারপর ফাইল ব্রাউজার আবার খুলুন।")
            return
        }
        val files = mutableListOf<BrowserFile>()
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE)
        try {
            contentResolver.query(collection, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
                val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                while (cursor.moveToNext()) {
                    val name = if (nameCol >= 0) cursor.getString(nameCol) ?: "ফাইল" else "ফাইল"
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else -1L
                    val type = if (mimeCol >= 0) cursor.getString(mimeCol) ?: mime else mime
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    files.add(BrowserFile(name, Uri.withAppendedPath(collection, id.toString()), size, type))
                }
            }
        } catch (e: Exception) { toast("ফাইল পড়া যায়নি: ${e.localizedMessage ?: "অনুমতি পরীক্ষা করুন"}"); return }
        showBrowserFileList(heading, files)
    }

    private fun showDownloads() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_BROWSER_PICK_FILE)
    }

    @Suppress("DEPRECATION")
    private fun showInstalledAppsInBrowser() {
        val files = mutableListOf<BrowserFile>()
        try {
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.enabled }
                .forEach { info ->
                    try {
                        val source = File(info.sourceDir)
                        if (source.isFile && source.length() > 0L) {
                            val label = packageManager.getApplicationLabel(info).toString()
                            val shareDir = File(cacheDir, "app-share").apply { mkdirs() }
                            val safe = label.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifBlank { "Android-App" }
                            val copy = File(shareDir, "$safe.apk")
                            source.inputStream().use { input -> copy.outputStream().use { output -> input.copyTo(output) } }
                            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", copy)
                            files.add(BrowserFile("📦 $label.apk", uri, copy.length(), "application/vnd.android.package-archive"))
                        }
                    } catch (_: Exception) { }
                }
        } catch (e: Exception) { toast("অ্যাপের তালিকা পড়া যায়নি: ${e.localizedMessage}"); return }
        showBrowserFileList("ইনস্টল করা APK", files.sortedBy { it.name.lowercase() })
    }

    private fun showBrowserFileList(heading: String, files: List<BrowserFile>) {
        if (files.isEmpty()) { toast("এই বিভাগে কোনো ফাইল পাওয়া যায়নি।"); return }
        val labels = files.map { "${it.name}\n${formatSize(it.size)}  •  ${it.mime}" }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("$heading — ${files.size}টি")
            .setItems(labels) { _, index -> showFileActions(files[index]) }
            .setNegativeButton("পেছনে") { _, _ -> showFileBrowserCategories() }
            .show()
    }

    private fun showFileActions(file: BrowserFile) {
        val recommendation = when {
            isConnected && file.size >= 50L * 1024 * 1024 -> "পরামর্শ: বড় ফাইলের জন্য সংযুক্ত Wi-Fi Direct ব্যবহার করুন।"
            isConnected -> "পরামর্শ: Wi-Fi Direct সংযুক্ত আছে; WebRTC-ও আলাদাভাবে চেষ্টা করা যাবে।"
            file.size >= 50L * 1024 * 1024 -> "পরামর্শ: বড় ফাইলের জন্য আগে Wi-Fi Direct সংযোগ করুন।"
            else -> "ফাইলের আকার ${formatSize(file.size)}। দুই পদ্ধতির শর্ত দেখে বেছে নিন।"
        }
        val actions = arrayOf("📡 Wi-Fi Direct দিয়ে পাঠান", "🔗 WebRTC QR দিয়ে পাঠান")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("${file.name}\n$recommendation")
            .setItems(actions) { _, which ->
                if (which == 0) {
                    if (!isConnected) {
                        toast("Wi-Fi Direct পাঠানোর আগে আশেপাশের ফোন খুঁজে সংযোগ করুন।")
                        return@setItems
                    }
                    ioExecutor.execute { sendFile(file.uri) }
                } else {
                    try {
                        startActivity(Intent(this, WebRtcActivity::class.java).apply {
                            putExtra(WebRtcActivity.EXTRA_SELECTED_URI, file.uri.toString())
                            putExtra(WebRtcActivity.EXTRA_SELECTED_NAME, file.name)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            clipData = android.content.ClipData.newUri(contentResolver, file.name, file.uri)
                        })
                    } catch (e: Exception) { toast("WebRTC খোলা যায়নি: ${e.localizedMessage}") }
                }
            }
            .setNegativeButton("বাতিল", null).show()
    }

    private fun chooseFile() {
        if (!isConnected) {
            toast("আগে অন্য ফোনের সঙ্গে Wi-Fi Direct সংযোগ করুন।")
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_PICK_FILE)
    }

    @Deprecated("Deprecated by Android; retained for broad compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_FILE && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            ioExecutor.execute { sendFile(uri) }
        } else if (requestCode == REQUEST_BROWSER_PICK_FILE && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            val name = queryDisplayName(uri) ?: "selected_file"
            val size = try { contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L } catch (_: Exception) { -1L }
            showFileActions(BrowserFile(name, uri, size, contentResolver.getType(uri) ?: "application/octet-stream"))
        } else if (requestCode == REQUEST_SAVE_FILE && resultCode == Activity.RESULT_OK) {
            val destination = data?.data
            val source = pendingSaveFile
            if (destination != null && source != null) {
                ioExecutor.execute {
                    try {
                        contentResolver.openOutputStream(destination, "w")?.use { out ->
                            FileInputStream(source).use { inputStream -> inputStream.copyTo(out, BUFFER_SIZE) }
                        } ?: throw IOException("ফাইল সংরক্ষণ করা যায়নি")
                        source.delete()
                        runOnUiThread { toast("ফাইল সংরক্ষণ সম্পন্ন হয়েছে।") }
                    } catch (e: Exception) {
                        runOnUiThread { toast("সংরক্ষণ ব্যর্থ: ${e.message}") }
                    }
                }
            }
        }
    }

    private fun sendFile(uri: Uri) {
        val name = queryDisplayName(uri) ?: "shared_file"
        val size = try { contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L } catch (_: Exception) { -1L }
        if (size < 0) {
            runOnUiThread { setStatus("এই ফাইলের আকার পড়া যায়নি; অন্য ফাইল নির্বাচন করুন।") }
            return
        }
        try {
            val out = output ?: throw IOException("সংযোগ নেই")
            setStatus("ফাইল যাচাইয়ের checksum তৈরি হচ্ছে: $name")
            val expectedHash = sha256Uri(uri)
            synchronized(socketLock) {
                out.writeByte(CMD_FILE)
                out.writeUTF(name.take(MAX_NAME_BYTES / 2))
                out.writeLong(size)
                out.writeUTF(expectedHash)
                out.flush()
                var sent = 0L
                contentResolver.openInputStream(uri)?.use { stream ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                        sent += count
                        if (sent % (2L * 1024 * 1024) < BUFFER_SIZE) {
                            val progress = if (size == 0L) 100 else (sent * 100 / size).toInt()
                            runOnUiThread { setStatus("ফাইল পাঠানো হচ্ছে: $name — $progress%") }
                        }
                    }
                    out.flush()
                } ?: throw IOException("ফাইল খোলা যায়নি")
                addHistory("পাঠানো", name, sent, "SHA-256 তৈরি ও পাঠানো সম্পন্ন")
                runOnUiThread { setStatus("পাঠানো শেষ: $name (${formatSize(sent)}) • SHA-256 যাচাই তথ্য পাঠানো হয়েছে") }
            }
        } catch (e: Exception) {
            runOnUiThread {
                setStatus("ফাইল পাঠানো ব্যর্থ: ${e.message ?: "সংযোগ বিচ্ছিন্ন"}")
                disconnectSocketOnly()
            }
        }
    }

    private fun readIncomingLoop() {
        try {
            val source = input ?: return
            while (isConnected) {
                val command = source.readUnsignedByte()
                if (command != CMD_FILE) throw IOException("অজানা ডেটা কমান্ড")
                val name = source.readUTF().replace('/', '_').replace('\\', '_').take(200).ifBlank { "received_file" }
                val size = source.readLong()
                val expectedHash = source.readUTF()
                if (size < 0 || expectedHash.length != 64) throw IOException("ফাইলের metadata ভুল")
                val file = File(cacheDir, "rfs_${System.currentTimeMillis()}_$name")
                var remaining = size
                var received = 0L
                FileOutputStream(file).use { fileOut ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (remaining > 0) {
                        val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count < 0) throw IOException("ফাইল অসম্পূর্ণ এসেছে")
                        fileOut.write(buffer, 0, count)
                        remaining -= count
                        received += count
                        if (received % (2L * 1024 * 1024) < BUFFER_SIZE) {
                            val progress = if (size == 0L) 100 else (received * 100 / size).toInt()
                            runOnUiThread { setStatus("ফাইল গ্রহণ হচ্ছে: $name — $progress%") }
                        }
                    }
                    fileOut.fd.sync()
                }
                val actualHash = sha256File(file)
                if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                    file.delete()
                    addHistory("গ্রহণ", name, size, "Checksum ব্যর্থ — ফাইল বাতিল")
                    throw IOException("SHA-256 মিলেনি; নিরাপত্তার জন্য ফাইল বাতিল করা হয়েছে")
                }
                addHistory("গ্রহণ", name, size, "SHA-256 যাচাই সফল")
                runOnUiThread {
                    setStatus("ফাইল গ্রহণ সম্পন্ন ও SHA-256 যাচাই সফল: $name (${formatSize(size)})")
                    addReceivedFile(name, file)
                }
            }
        } catch (e: Exception) {
            if (isConnected && !isFinishing) runOnUiThread { setStatus("গ্রহণ সংযোগ বন্ধ: ${e.message ?: "সংযোগ বিচ্ছিন্ন"}") }
            disconnectSocketOnly()
        }
    }

    private fun addReceivedFile(name: String, file: File) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(1), 0xFFD7E5F0.toInt())
                cornerRadius = dp(16).toFloat()
            }
        }
        val mime = mimeTypeFor(name)
        val icon = when {
            mime.startsWith("image/") -> "🖼️"
            mime.startsWith("video/") -> "🎬"
            mime.startsWith("audio/") -> "🎵"
            mime == "application/pdf" -> "📕"
            mime.startsWith("text/") -> "📝"
            else -> "📄"
        }
        row.addView(TextView(this).apply {
            text = "$icon  $name"
            textSize = 15f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(0xFF12304A.toInt())
        }, matchWrap(bottom = 4))
        row.addView(TextView(this).apply {
            text = "${formatSize(file.length())}  •  ${mime.substringBefore(';')}"
            textSize = 12f
            setTextColor(0xFF687B8D.toInt())
        }, matchWrap(bottom = 10))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(Button(this).apply {
            text = "খুলুন / Preview"
            isAllCaps = false
            setOnClickListener { openReceivedFile(name, file) }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        actions.addView(Button(this).apply {
            text = "শেয়ার"
            isAllCaps = false
            setOnClickListener { shareReceivedFile(name, file) }
        }, LinearLayout.LayoutParams(0, -2, 0.65f).apply { marginEnd = dp(4) })
        actions.addView(Button(this).apply {
            text = "সংরক্ষণ"
            isAllCaps = false
            setOnClickListener {
                pendingSaveFile = file
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = mimeTypeFor(name)
                    putExtra(Intent.EXTRA_TITLE, name)
                }
                startActivityForResult(intent, REQUEST_SAVE_FILE)
            }
        }, LinearLayout.LayoutParams(0, -2, 0.7f))
        row.addView(actions, matchWrap())
        receivedBox.addView(row, matchWrap(bottom = 8))
    }

    private fun restoreReceivedFiles() {
        val files = cacheDir.listFiles { file -> file.isFile && file.name.startsWith("rfs_") }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        files.forEach { file ->
            val first = file.name.indexOf('_')
            val second = if (first >= 0) file.name.indexOf('_', first + 1) else -1
            val displayName = if (second >= 0) file.name.substring(second + 1) else file.name
            addReceivedFile(displayName, file)
        }
    }

    private fun mimeTypeFor(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    private fun openReceivedFile(name: String, file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeTypeFor(name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "ফাইলটি কোন অ্যাপে খুলবেন?"))
        } catch (_: android.content.ActivityNotFoundException) {
            toast("এই ফাইল খোলার মতো অ্যাপ পাওয়া যায়নি। শেয়ার করে অন্য অ্যাপে খুলুন।")
        } catch (e: Exception) {
            toast("ফাইল খোলা যায়নি: ${e.localizedMessage ?: "অজানা সমস্যা"}")
        }
    }

    private fun shareReceivedFile(name: String, file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeTypeFor(name)
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newUri(contentResolver, name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "ফাইল শেয়ার করুন"))
        } catch (e: Exception) {
            toast("ফাইল শেয়ার করা যায়নি: ${e.localizedMessage ?: "অজানা সমস্যা"}")
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment
    }

    private fun disconnect() {
        try {
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { setStatus("Wi-Fi Direct গ্রুপ বন্ধ করা হয়েছে।") }
                override fun onFailure(reason: Int) { setStatus("গ্রুপ বন্ধের ফলাফল: ${reasonText(reason)}") }
            })
        } catch (_: Exception) {}
        disconnectSocketOnly()
        acceptStarted.set(false)
        receiverStarted.set(false)
        connectAttempting.set(false)
        setStatus("সংযোগ বিচ্ছিন্ন করা হয়েছে। আবার স্ক্যান করতে পারেন।")
    }

    private fun disconnectSocketOnly() {
        isConnected = false
        sendButton.post { sendButton.isEnabled = false }
        synchronized(socketLock) {
            try { socket?.close() } catch (_: Exception) {}
            try { input?.close() } catch (_: Exception) {}
            try { output?.close() } catch (_: Exception) {}
            socket = null
            input = null
            output = null
        }
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptStarted.set(false)
        receiverStarted.set(false)
        connectAttempting.set(false)
    }

    private fun setStatus(message: String) {
        if (LooperCheck.isMainThread()) status.text = message else runOnUiThread { status.text = message }
    }

    private fun toast(message: String) = runOnUiThread {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun matchWrap(bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            if (bottom > 0) bottomMargin = dp(bottom)
        }

    override fun onResume() {
        super.onResume()
        if (hasRequiredPermissions()) {
            // Re-register only if onCreate registration did not happen. Receiver registration is tracked below.
            if (!receiverRegistered) registerP2pReceiver()
        }
    }

    private var receiverRegistered = false

    private fun safeUnregisterReceiver() {
        if (receiverRegistered) {
            try { unregisterReceiver(p2pReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
    }

    override fun onPause() {
        safeUnregisterReceiver()
        super.onPause()
    }

    override fun onDestroy() {
        disconnectSocketOnly()
        try { manager.cancelConnect(channel, null) } catch (_: Exception) {}
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private object LooperCheck {
        fun isMainThread() = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
    }
}
