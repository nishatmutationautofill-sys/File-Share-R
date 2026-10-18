package com.rishat.fileshare

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

class LanPeer(val key: String, val id: String, val name: String, val host: String, val port: Int)

/**
 * একই Wi-Fi / হটস্পটে স্বয়ংক্রিয় ফোন-খোঁজা (mDNS/NSD)। কোনো সার্ভার বা ইন্টারনেট লাগে না।
 */
class LanDiscovery(ctx: Context, private val onChange: (List<LanPeer>) -> Unit) {
    private val nsd = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null
    private val found = LinkedHashMap<String, LanPeer>()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private val retries = HashMap<String, Int>()
    private var resolving = false
    private var running = false
    private var ownId = ""

    fun start(id: String, name: String, port: Int) {
        synchronized(lock) {
            if (running) return
            running = true
            ownId = id
        }
        val info = NsdServiceInfo().apply {
            serviceName = "RFS-" + id.take(10)
            serviceType = Proto.SERVICE_TYPE
            this.port = port
            setAttribute("id", id)
            setAttribute("n", name.take(60))
            setAttribute("v", Proto.VERSION.toString())
        }
        val reg = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
        }
        val disc = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.contains("rishatfs")) return
                if (serviceInfo.serviceName.startsWith("RFS-" + ownId.take(10))) return
                synchronized(lock) {
                    queue.addLast(serviceInfo)
                    pump()
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                synchronized(lock) {
                    found.remove(serviceInfo.serviceName)
                }
                publish()
            }
        }
        regListener = reg
        discListener = disc
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg) } catch (_: Exception) { regListener = null }
        try { nsd.discoverServices(Proto.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, disc) } catch (_: Exception) { discListener = null }
    }

    fun stop() {
        var reg: NsdManager.RegistrationListener? = null
        var disc: NsdManager.DiscoveryListener? = null
        synchronized(lock) {
            if (!running) return
            running = false
            reg = regListener
            disc = discListener
            regListener = null
            discListener = null
            found.clear()
            queue.clear()
            retries.clear()
            resolving = false
        }
        val r = reg
        val d = disc
        if (r != null) try { nsd.unregisterService(r) } catch (_: Exception) {}
        if (d != null) try { nsd.stopServiceDiscovery(d) } catch (_: Exception) {}
        publish()
    }

    // lock ধরা অবস্থায় কল হয়
    @Suppress("DEPRECATION")
    private fun pump() {
        if (resolving) return
        val next = queue.removeFirstOrNull() ?: return
        resolving = true
        try {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    synchronized(lock) {
                        resolving = false
                        val n = (retries[serviceInfo.serviceName] ?: 0) + 1
                        retries[serviceInfo.serviceName] = n
                        if (n <= 2 && running) {
                            main.postDelayed({
                                synchronized(lock) {
                                    if (running) {
                                        queue.addLast(serviceInfo)
                                        pump()
                                    }
                                }
                            }, 800)
                        }
                        pump()
                    }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val attrs = serviceInfo.attributes
                    val id = attrs["id"]?.let { String(it, Charsets.UTF_8) } ?: ""
                    val name = attrs["n"]?.let { String(it, Charsets.UTF_8) } ?: serviceInfo.serviceName
                    val host = pickHost(serviceInfo)
                    synchronized(lock) {
                        if (running && id.isNotEmpty() && id != ownId && host != null) {
                            found[serviceInfo.serviceName] =
                                LanPeer(serviceInfo.serviceName, id, name, host, serviceInfo.port)
                        }
                        resolving = false
                        pump()
                    }
                    publish()
                }
            })
        } catch (_: Exception) {
            resolving = false
        }
    }

    @Suppress("DEPRECATION")
    private fun pickHost(info: NsdServiceInfo): String? {
        val addrs: List<InetAddress> =
            if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        val v4 = addrs.firstOrNull { it is Inet4Address }
        return (v4 ?: addrs.firstOrNull())?.hostAddress
    }

    private fun publish() {
        val list = synchronized(lock) { found.values.toList() }
        main.post { onChange(list) }
    }
}

object Net {
    /** নিজের লোকাল IPv4 (Wi-Fi / হটস্পট / Wi-Fi Direct)। মোবাইল ডেটা ইন্টারফেস বাদ। */
    fun localIpv4(): String? {
        val list = ArrayList<Pair<String, String>>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val n = iface.name.lowercase()
                if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("tun") || n.startsWith("dummy")) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let { list.add(Pair(n, it)) }
                    }
                }
            }
        } catch (_: Exception) {
        }
        val pick = list.firstOrNull { it.first.startsWith("wlan") }
            ?: list.firstOrNull { it.first.startsWith("ap") || it.first.startsWith("swlan") || it.first.startsWith("p2p") }
            ?: list.firstOrNull()
        return pick?.second
    }
}
