package com.rishat.fileshare

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Wi-Fi Direct ট্রান্সপোর্ট (রাউটার/ইন্টারনেট ছাড়া)।
 * মূল উন্নতি: রিসিভার অ্যাক্টিভিটির সঙ্গে নয়, অ্যাপ-কনটেক্সটে বাঁধা — তাই ফাইল-পিকার
 * খুললেও ইভেন্ট হারায় না। BUSY হলে স্বয়ংক্রিয় রিট্রাই, পুরোনো গ্রুপ পরিষ্কার, চ্যানেল হারালে পুনরায় চালু।
 */
class DirectTransport(private val ctx: Context, private val cb: Callback) {

    interface Callback {
        fun onDirectState(enabled: Boolean)
        fun onDirectPeers(peers: List<WifiP2pDevice>)
        fun onDirectGroup(info: WifiP2pInfo)
        fun onDirectSelf(name: String)
        fun onDirectScanning(active: Boolean)
        fun onDirectError(msg: String)
        fun hasSession(): Boolean
    }

    private val manager: WifiP2pManager? = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var registered = false
    private val main = Handler(Looper.getMainLooper())

    val supported: Boolean
        get() = manager != null && ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val i: Intent = intent ?: return
            when (i.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val st = i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    cb.onDirectState(st == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnection()
                WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                    val st = i.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1)
                    cb.onDirectScanning(st == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED)
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val dev: WifiP2pDevice? = if (Build.VERSION.SDK_INT >= 33) {
                        i.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        i.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    }
                    if (dev != null) cb.onDirectSelf(dev.deviceName ?: "")
                }
            }
        }
    }

    fun start() {
        val m = manager ?: return
        if (channel == null) {
            channel = m.initialize(ctx, Looper.getMainLooper(), WifiP2pManager.ChannelListener { onChannelLost() })
        }
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
        requestConnection()
    }

    private fun onChannelLost() {
        channel = null
        main.postDelayed({ start() }, 500)
    }

    @SuppressLint("MissingPermission")
    private fun requestPeers() {
        val m = manager ?: return
        val ch = channel ?: return
        try {
            m.requestPeers(ch) { list -> cb.onDirectPeers(list?.deviceList?.toList() ?: emptyList()) }
        } catch (_: SecurityException) {
            cb.onDirectError("Wi-Fi Direct-এর অনুমতি দিন")
        }
    }

    private fun requestConnection() {
        val m = manager ?: return
        val ch = channel ?: return
        try {
            m.requestConnectionInfo(ch) { info -> if (info != null) cb.onDirectGroup(info) }
        } catch (_: SecurityException) {
            cb.onDirectError("Wi-Fi Direct-এর অনুমতি দিন")
        }
    }

    /** পুরোনো আটকে-থাকা গ্রুপ (BUSY-র প্রধান কারণ) পরিষ্কার করে স্ক্যান শুরু করে। */
    @SuppressLint("MissingPermission")
    fun discover() {
        val m = manager ?: return
        if (channel == null) start()
        val ch = channel ?: return
        try {
            m.requestGroupInfo(ch) { group ->
                if (group != null && !cb.hasSession()) {
                    m.removeGroup(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() { doDiscover(0) }
                        override fun onFailure(reason: Int) { doDiscover(0) }
                    })
                } else {
                    doDiscover(0)
                }
            }
        } catch (_: SecurityException) {
            cb.onDirectError("Wi-Fi Direct-এর অনুমতি দিন")
        }
    }

    @SuppressLint("MissingPermission")
    private fun doDiscover(attempt: Int) {
        val m = manager ?: return
        val ch = channel ?: return
        try {
            m.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    cb.onDirectScanning(true)
                    requestPeers()
                }

                override fun onFailure(reason: Int) {
                    if (reason == WifiP2pManager.BUSY && attempt < 3) {
                        main.postDelayed({ doDiscover(attempt + 1) }, 1200)
                    } else {
                        cb.onDirectError("স্ক্যান ব্যর্থ: " + reasonText(reason))
                    }
                }
            })
        } catch (_: SecurityException) {
            cb.onDirectError("Wi-Fi Direct-এর অনুমতি দিন")
        }
    }

    /** সংযোগের আগে ডিসকভারি থামালে সফলতার হার বাড়ে। */
    @SuppressLint("MissingPermission")
    fun connect(device: WifiP2pDevice) {
        val m = manager ?: return
        val ch = channel ?: return
        try {
            m.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { doConnect(device, 0) }
                override fun onFailure(reason: Int) { doConnect(device, 0) }
            })
        } catch (_: SecurityException) {
            cb.onDirectError("Wi-Fi Direct-এর অনুমতি দিন")
        }
    }

    @SuppressLint("MissingPermission")
    private fun doConnect(device: WifiP2pDevice, attempt: Int) {
        val m = manager ?: return
        val ch = channel ?: return
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        try {
            m.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {
                    if (reason == WifiP2pManager.BUSY && attempt < 3) {
                        main.postDelayed({ doConnect(device, attempt + 1) }, 1000)
                    } else {
                        cb.onDirectError("সংযোগ ব্যর্থ: " + reasonText(reason))
                    }
                }
            })
        } catch (_: SecurityException) {
            cb.onDirectError("Wi-Fi Direct-এর অনুমতি দিন")
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        val m = manager ?: return
        val ch = channel ?: return
        try { m.cancelConnect(ch, null) } catch (_: Exception) {}
        try { m.removeGroup(ch, null) } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    fun cancelConnect() {
        val m = manager ?: return
        val ch = channel ?: return
        try { m.cancelConnect(ch, null) } catch (_: Exception) {}
    }

    private fun reasonText(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "এই ফোনে Wi-Fi Direct সমর্থিত নয়"
        WifiP2pManager.BUSY -> "সিস্টেম ব্যস্ত; কিছুক্ষণ পরে আবার চেষ্টা করুন"
        WifiP2pManager.ERROR -> "সিস্টেম ত্রুটি; Wi-Fi একবার বন্ধ-চালু করুন"
        else -> "কোড $reason"
    }
}
