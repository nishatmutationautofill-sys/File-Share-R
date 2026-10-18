package com.rishat.fileshare

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * একটি সংযুক্ত পিয়ারের সঙ্গে সম্পূর্ণ ডুপ্লেক্স সেশন।
 *  - ফ্রেম-ভিত্তিক প্রোটোকল: একই সংযোগে দু-দিকেই একসাথে একাধিক ফাইল চলে
 *  - প্রতিটি ফাইলে SHA-256 যাচাই
 *  - হার্টবিট + রিড-টাইমআউট: মরা সংযোগ দ্রুত ধরা পড়ে
 *  - রিডার থ্রেড কখনো সকেটে লেখে না (ডেডলক এড়াতে)
 */
class Session(val socket: Socket, val link: LinkType, private val host: Host) {

    interface Host {
        fun nextUid(): Long
        fun createSink(name: String, mime: String, size: Long): FileSink
        fun onItemAdded(item: TransferItem)
        fun onSessionClosed(sess: Session, reason: String)
    }

    class Hello(val deviceId: String, val deviceName: String, val token: String)

    private class Incoming(val item: TransferItem, val sink: FileSink) {
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
        var received: Long = 0L
    }

    var peerId: String = ""
        private set
    var peerName: String = ""
        private set

    private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), Proto.CHUNK))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), Proto.CHUNK))
    private val writeLock = ReentrantLock()
    private val closed = AtomicBoolean(false)
    @Volatile private var lastWrite = System.currentTimeMillis()

    private val incoming = ConcurrentHashMap<Int, Incoming>()
    private val outgoing = ConcurrentHashMap<Int, TransferItem>()
    private val nextWireId = AtomicInteger(1)

    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "rfs-sender").apply { isDaemon = true } }
    private val control = Executors.newSingleThreadExecutor { r -> Thread(r, "rfs-control").apply { isDaemon = true } }

    val isClosed: Boolean get() = closed.get()

    // ───────────────────────── Handshake ─────────────────────────

    /** সফল হলে null, নইলে ব্যর্থতার কারণ। */
    fun handshakeClient(me: Hello): String? {
        socket.soTimeout = Proto.CLIENT_WAIT_MS
        writeRaw(Proto.T_HELLO, Proto.payload { d ->
            d.writeInt(Proto.MAGIC)
            d.writeInt(Proto.VERSION)
            d.writeUTF(me.deviceId)
            d.writeUTF(me.deviceName)
            d.writeUTF(me.token)
        })
        val type = input.readUnsignedByte()
        val len = input.readInt()
        if (len < 0 || len > 4096) return "অবৈধ উত্তর"
        val data = ByteArray(len)
        input.readFully(data)
        val d = DataInputStream(ByteArrayInputStream(data))
        if (type == Proto.T_HELLO_NO) return d.readUTF()
        if (type != Proto.T_HELLO_OK) return "অবৈধ উত্তর"
        if (d.readInt() != Proto.MAGIC) return "এটি Rishat File Share নয়"
        if (d.readInt() != Proto.VERSION) return "অ্যাপের সংস্করণ মেলেনি; দুই ফোনে একই সংস্করণ ইনস্টল করুন"
        peerId = d.readUTF()
        peerName = d.readUTF()
        return null
    }

    /** decide: null = গ্রহণ, নইলে প্রত্যাখ্যানের কারণ। সফল হলে null রিটার্ন করে। */
    fun handshakeServer(me: Hello, decide: (Hello) -> String?): String? {
        socket.soTimeout = Proto.HANDSHAKE_TIMEOUT_MS
        val type = input.readUnsignedByte()
        val len = input.readInt()
        if (type != Proto.T_HELLO || len < 0 || len > 4096) return "অবৈধ অনুরোধ"
        val data = ByteArray(len)
        input.readFully(data)
        val d = DataInputStream(ByteArrayInputStream(data))
        if (d.readInt() != Proto.MAGIC) return "অবৈধ অনুরোধ"
        val version = d.readInt()
        val hello = Hello(d.readUTF(), d.readUTF(), d.readUTF())
        if (version != Proto.VERSION) {
            reject("অ্যাপের সংস্করণ মেলেনি; দুই ফোনে একই সংস্করণ ইনস্টল করুন")
            return "সংস্করণ মেলেনি"
        }
        val reason = decide(hello)
        if (reason != null) {
            reject(reason)
            return reason
        }
        peerId = hello.deviceId
        peerName = hello.deviceName
        writeRaw(Proto.T_HELLO_OK, Proto.payload { o ->
            o.writeInt(Proto.MAGIC)
            o.writeInt(Proto.VERSION)
            o.writeUTF(me.deviceId)
            o.writeUTF(me.deviceName)
        })
        return null
    }

    private fun reject(reason: String) {
        try {
            writeRaw(Proto.T_HELLO_NO, Proto.payload { o -> o.writeUTF(reason) })
        } catch (_: Exception) {
        }
    }

    // ───────────────────────── Lifecycle ─────────────────────────

    fun start() {
        try {
            socket.soTimeout = Proto.READ_TIMEOUT_MS
        } catch (_: Exception) {
        }
        Thread({ readLoop() }, "rfs-reader").apply { isDaemon = true }.start()
        Thread({ heartbeatLoop() }, "rfs-heartbeat").apply { isDaemon = true }.start()
    }

    fun close(reason: String, notifyPeer: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        if (notifyPeer) {
            try {
                if (writeLock.tryLock(500, TimeUnit.MILLISECONDS)) {
                    try {
                        output.writeByte(Proto.T_BYE)
                        output.writeInt(0)
                        output.flush()
                    } finally {
                        writeLock.unlock()
                    }
                }
            } catch (_: Exception) {
            }
        }
        try { socket.close() } catch (_: Exception) {}
        sender.shutdownNow()
        control.shutdownNow()
        for (st in incoming.values) {
            st.sink.abort()
            fail(st.item, reason)
        }
        incoming.clear()
        for (item in outgoing.values) fail(item, reason)
        host.onSessionClosed(this, reason)
    }

    // ───────────────────────── Sending ─────────────────────────

    fun enqueue(source: FileSource): TransferItem {
        val item = TransferItem(host.nextUid(), true, source.name, source.size, source.mime)
        item.wireId = nextWireId.getAndIncrement()
        outgoing[item.wireId] = item
        host.onItemAdded(item)
        try {
            sender.execute { sendOne(source, item) }
        } catch (_: RejectedExecutionException) {
            fail(item, "সংযোগ নেই")
        }
        return item
    }

    private fun sendOne(src: FileSource, item: TransferItem) {
        if (closed.get()) return
        if (item.cancelRequested.get()) {
            if (!item.isFinished) item.state = TState.CANCELED
            return
        }
        item.state = TState.ACTIVE
        val id = item.wireId
        try {
            writeRaw(Proto.T_OFFER, Proto.payload { d ->
                d.writeInt(id)
                d.writeUTF(item.name)
                d.writeLong(item.size)
                d.writeUTF(item.mime)
            })
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(Proto.CHUNK)
            var total = 0L
            src.open().use { stream ->
                while (true) {
                    if (item.cancelRequested.get() || closed.get()) break
                    val n = stream.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    md.update(buf, 0, n)
                    writeData(id, buf, n)
                    total += n
                    item.bytes = total
                }
            }
            if (closed.get()) {
                fail(item, "সংযোগ বিচ্ছিন্ন")
                return
            }
            if (item.cancelRequested.get()) {
                if (!item.isFinished) {
                    item.state = TState.CANCELED
                    try {
                        writeRaw(Proto.T_CANCEL, Proto.payload { d -> d.writeInt(id) })
                    } catch (_: Exception) {
                    }
                }
                return
            }
            item.state = TState.VERIFYING
            val digest = md.digest()
            writeRaw(Proto.T_END, Proto.payload { d ->
                d.writeInt(id)
                d.writeLong(total)
                d.write(digest)
            })
        } catch (e: Exception) {
            if (!item.isFinished) fail(item, e.message ?: "ফাইল পাঠানো ব্যর্থ")
            if (!closed.get()) {
                try {
                    writeRaw(Proto.T_CANCEL, Proto.payload { d -> d.writeInt(id) })
                } catch (_: Exception) {
                }
            }
        }
    }

    /** UI থ্রেড থেকে নিরাপদে কল করা যায়। */
    fun cancel(item: TransferItem) {
        if (item.isFinished || item.state == TState.VERIFYING) return
        item.cancelRequested.set(true)
        if (!item.outgoing) {
            val st = incoming.remove(item.wireId)
            if (st != null) {
                st.sink.abort()
                item.message = "আপনি বাতিল করেছেন"
                item.state = TState.CANCELED
                sendCancelAsync(item.wireId)
            }
        } else if (item.state == TState.QUEUED) {
            item.message = "আপনি বাতিল করেছেন"
            item.state = TState.CANCELED
        }
        // ACTIVE outgoing: sendOne লুপ নিজেই CANCELED সেট করে পিয়ারকে জানাবে
    }

    // ───────────────────────── Reading ─────────────────────────

    private fun readLoop() {
        var reason = "সংযোগ বন্ধ হয়েছে"
        try {
            val buf = ByteArray(Proto.MAX_FRAME)
            loop@ while (!closed.get()) {
                val type = input.readUnsignedByte()
                val len = input.readInt()
                if (len < 0 || len > buf.size) throw IOException("ভুল ফ্রেম")
                input.readFully(buf, 0, len)
                when (type) {
                    Proto.T_DATA -> onData(buf, len)
                    Proto.T_OFFER -> onOffer(DataInputStream(ByteArrayInputStream(buf, 0, len)))
                    Proto.T_END -> onEnd(DataInputStream(ByteArrayInputStream(buf, 0, len)))
                    Proto.T_CANCEL -> onCancel(DataInputStream(ByteArrayInputStream(buf, 0, len)))
                    Proto.T_RESULT -> onResult(DataInputStream(ByteArrayInputStream(buf, 0, len)))
                    Proto.T_PING -> Unit
                    Proto.T_BYE -> {
                        reason = "অন্য ফোন সংযোগ বিচ্ছিন্ন করেছে"
                        break@loop
                    }
                    else -> throw IOException("অজানা ফ্রেম")
                }
            }
        } catch (_: SocketTimeoutException) {
            reason = "অন্য ফোন সাড়া দিচ্ছে না (সংযোগ হারিয়েছে)"
        } catch (e: Exception) {
            if (closed.get()) return
            reason = "সংযোগ বিচ্ছিন্ন হয়েছে"
        }
        close(reason, false)
    }

    private fun onOffer(d: DataInputStream) {
        val id = d.readInt()
        val name = sanitizeName(d.readUTF())
        val size = d.readLong()
        val mime = d.readUTF()
        val item = TransferItem(host.nextUid(), false, name, size, mime)
        item.wireId = id
        host.onItemAdded(item)
        if (size < 0) {
            fail(item, "ফাইলের আকার ভুল")
            sendCancelAsync(id)
            return
        }
        try {
            val sink = host.createSink(name, mime, size)
            item.state = TState.ACTIVE
            incoming[id] = Incoming(item, sink)
        } catch (e: Exception) {
            fail(item, e.message ?: "ফাইল তৈরি করা যায়নি")
            sendCancelAsync(id)
        }
    }

    private fun onData(buf: ByteArray, len: Int) {
        if (len < 4) throw IOException("ভুল ডেটা ফ্রেম")
        val id = readInt(buf, 0)
        val st = incoming[id] ?: return
        val n = len - 4
        try {
            if (st.received + n > st.item.size) throw IOException("প্রত্যাশার চেয়ে বেশি ডেটা এসেছে")
            st.sink.output.write(buf, 4, n)
            st.digest.update(buf, 4, n)
            st.received += n
            st.item.bytes = st.received
        } catch (e: IOException) {
            incoming.remove(id)
            st.sink.abort()
            fail(st.item, e.message ?: "ফোনে ফাইল লেখা যায়নি")
            sendCancelAsync(id)
        }
    }

    private fun onEnd(d: DataInputStream) {
        val id = d.readInt()
        val total = d.readLong()
        val sha = ByteArray(32)
        d.readFully(sha)
        val st = incoming.remove(id) ?: return
        val item = st.item
        val ok = st.received == total && total == item.size &&
            MessageDigest.isEqual(st.digest.digest(), sha)
        if (!ok) {
            st.sink.abort()
            fail(item, "ফাইল যাচাই ব্যর্থ (ডেটা মেলেনি)")
            sendResultAsync(id, false, "checksum")
            return
        }
        try {
            val uri = st.sink.commit()
            item.savedUri = uri
            item.savedLabel = st.sink.label
            item.bytes = item.size
            item.state = TState.DONE
            sendResultAsync(id, true, "")
        } catch (e: Exception) {
            st.sink.abort()
            val msg = "সংরক্ষণ ব্যর্থ: " + (e.message ?: "")
            fail(item, msg)
            sendResultAsync(id, false, msg)
        }
    }

    private fun onCancel(d: DataInputStream) {
        val id = d.readInt()
        val st = incoming.remove(id)
        if (st != null) {
            st.sink.abort()
            st.item.message = "প্রেরক বাতিল করেছে"
            if (!st.item.isFinished) st.item.state = TState.CANCELED
        }
        val out = outgoing[id]
        if (out != null && !out.isFinished) {
            out.message = "অন্য ফোন বাতিল করেছে"
            if (out.state == TState.VERIFYING) out.state = TState.FAILED
            else out.cancelRequested.set(true)
        }
    }

    private fun onResult(d: DataInputStream) {
        val id = d.readInt()
        val ok = d.readBoolean()
        val msg = d.readUTF()
        val item = outgoing[id] ?: return
        if (item.isFinished) return
        if (ok) {
            item.bytes = item.size
            item.state = TState.DONE
        } else {
            fail(item, if (msg == "checksum") "অন্য ফোনে যাচাই ব্যর্থ" else msg)
        }
    }

    // ───────────────────────── Writing helpers ─────────────────────────

    private fun heartbeatLoop() {
        try {
            while (!closed.get()) {
                Thread.sleep(Proto.HEARTBEAT_MS)
                if (System.currentTimeMillis() - lastWrite >= Proto.HEARTBEAT_MS - 300) {
                    writeRaw(Proto.T_PING, EMPTY)
                }
            }
        } catch (_: InterruptedException) {
        } catch (_: Exception) {
            close("সংযোগ বিচ্ছিন্ন হয়েছে", false)
        }
    }

    private fun writeRaw(type: Int, body: ByteArray) {
        writeLock.lock()
        try {
            output.writeByte(type)
            output.writeInt(body.size)
            output.write(body)
            output.flush()
            lastWrite = System.currentTimeMillis()
        } finally {
            writeLock.unlock()
        }
    }

    private fun writeData(id: Int, buf: ByteArray, n: Int) {
        writeLock.lock()
        try {
            output.writeByte(Proto.T_DATA)
            output.writeInt(4 + n)
            output.writeInt(id)
            output.write(buf, 0, n)
            output.flush()
            lastWrite = System.currentTimeMillis()
        } finally {
            writeLock.unlock()
        }
    }

    private fun async(block: () -> Unit) {
        try {
            control.execute {
                try { block() } catch (_: Exception) {}
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun sendCancelAsync(id: Int) =
        async { writeRaw(Proto.T_CANCEL, Proto.payload { d -> d.writeInt(id) }) }

    private fun sendResultAsync(id: Int, ok: Boolean, msg: String) =
        async {
            writeRaw(Proto.T_RESULT, Proto.payload { d ->
                d.writeInt(id)
                d.writeBoolean(ok)
                d.writeUTF(msg)
            })
        }

    private fun fail(item: TransferItem, msg: String) {
        if (item.isFinished) return
        item.message = msg
        item.state = TState.FAILED
    }

    companion object {
        private val EMPTY = ByteArray(0)

        fun readInt(b: ByteArray, o: Int): Int =
            ((b[o].toInt() and 0xff) shl 24) or
                ((b[o + 1].toInt() and 0xff) shl 16) or
                ((b[o + 2].toInt() and 0xff) shl 8) or
                (b[o + 3].toInt() and 0xff)

        fun sanitizeName(raw: String): String {
            var s = raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimStart('.')
            if (s.length > 120) {
                val dot = s.lastIndexOf('.')
                s = if (dot > 0 && s.length - dot <= 12) {
                    s.substring(0, 120 - (s.length - dot)) + s.substring(dot)
                } else {
                    s.substring(0, 120)
                }
            }
            return if (s.isBlank()) "received_file" else s
        }
    }
}
