package com.rishat.fileshare

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.integration.android.IntentIntegrator
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoDecoderFactory
import org.webrtc.VideoEncoderFactory
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.PeerConnection.IceServer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Same-Wi-Fi WebRTC DataChannel mode:
 * Sender displays a QR containing its SDP offer; receiver scans it and displays
 * an SDP answer QR; sender scans the answer. This mode is intended for phones
 * on the same local Wi-Fi network. It uses local ICE host candidates only:
 * no cloud signaling, file upload server, STUN, or TURN is configured.
 *
 * Note: SDP can exceed QR capacity. This remains an experimental setup method;
 * if the QR is too large or host candidates are not reachable, use Wi-Fi Direct.
 */
class WebRtcActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SELECTED_URI = "com.rishat.fileshare.SELECTED_URI"
        const val EXTRA_SELECTED_NAME = "com.rishat.fileshare.SELECTED_NAME"
    }
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var qr: ImageView
    private lateinit var details: TextView
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var channel: DataChannel? = null
    private var pendingRole: String = ""
    private var incomingFile: File? = null
    private var incomingOutput: FileOutputStream? = null
    private var expectedBytes: Long = -1
    private var receivedBytes: Long = 0
    private var incomingName = "received_file"
    private var incomingMime = "application/octet-stream"
    private var incomingExpectedHash = ""
    private val executor = Executors.newSingleThreadExecutor()
    private val filePickerRequest = 1001
    private val saveRequest = 1002
    private var selectedUri: Uri? = null
    private var selectedName: String = "file"
    private var selectedSize: Long = 0L
    private val stopped = AtomicBoolean(false)
    private val sendStarted = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        buildUi()
        val presetUri = intent.getStringExtra(EXTRA_SELECTED_URI)?.let { Uri.parse(it) }
        if (presetUri != null) {
            selectedUri = presetUri
            selectedName = intent.getStringExtra(EXTRA_SELECTED_NAME) ?: queryDisplayName(presetUri) ?: "shared_file"
            selectedSize = try { contentResolver.openAssetFileDescriptor(presetUri, "r")?.use { it.length } ?: -1L } catch (_: Exception) { -1L }
            createOffer()
        } else setStatus("দুই ফোনকে একই Wi-Fi নেটওয়ার্কে যুক্ত করুন। এরপর Send বা Receive নির্বাচন করুন।")
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(24))
            setBackgroundColor(Color.rgb(243, 247, 252))
        }
        scroll.addView(root)
        setContentView(scroll)

        val title = TextView(this).apply {
            text = "Rishat File Share"
            textSize = 26f
            setTextColor(Color.rgb(20, 43, 80))
            gravity = Gravity.CENTER
        }
        root.addView(title, lp(match = true, height = -2, bottom = 8))
        val subtitle = TextView(this).apply {
            text = "একই Wi-Fi-তে সরাসরি ফাইল পাঠান • কোনো ক্লাউড আপলোড নয়"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
        }
        root.addView(subtitle, lp(match = true, height = -2, bottom = 20))

        addButton("📁  ১. ফাইল নির্বাচন করে পাঠান") { chooseFile() }
        addButton("📷  ২. QR স্ক্যান করে ফাইল গ্রহণ করুন") { startReceiver(); scanSdp("offer") }
        addButton("🔗  ৩. প্রেরক: উত্তর QR স্ক্যান করুন") { scanSdp("answer") }
        addButton("⛔  সংযোগ বন্ধ / নতুন করে শুরু") { closePeer(); qr.visibility = View.GONE; details.text = ""; setStatus("সংযোগ বন্ধ। প্রয়োজন হলে আবার শুরু করুন।") }

        status = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(30, 50, 70))
            setPadding(0, dp(18), 0, dp(12))
        }
        root.addView(status, lp(true, -2))
        qr = ImageView(this).apply { adjustViewBounds = true; visibility = View.GONE }
        root.addView(qr, lp(true, -2, top = 8, bottom = 8))
        details = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.DKGRAY)
        }
        root.addView(details, lp(true, -2))
        val note = TextView(this).apply {
            text = "সহজ ধাপ: (১) প্রেরক ফাইল নির্বাচন করবে—Offer QR দেখাবে। (২) গ্রাহক ‘QR স্ক্যান করে ফাইল গ্রহণ করুন’ চাপ দিয়ে QR স্ক্যান করবে—Answer QR দেখাবে। (৩) প্রেরক ‘উত্তর QR স্ক্যান করুন’ চাপ দিয়ে Answer QR স্ক্যান করবে। সংযোগ তৈরি হলেই ফাইল স্বয়ংক্রিয়ভাবে পাঠানো শুরু হবে। দুই ফোন একই Wi-Fi-তে থাকতে হবে। QR স্ক্যানের পরও সংযোগ না হলে মূল পর্দার Wi-Fi Direct পদ্ধতি ব্যবহার করুন।"
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(18), 0, 0)
        }
        root.addView(note, lp(true, -2))
        styleUi(root)
    }

    private fun styleUi(view: View) {
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
            is TextView -> if (view.text.toString() == "Rishat File Share") {
                view.textSize = 28f
                view.setTextColor(0xFF102A43.toInt())
                view.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            }
            is LinearLayout -> for (i in 0 until view.childCount) styleUi(view.getChildAt(i))
        }
    }

    private fun addButton(label: String, action: () -> Unit) {
        val button = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }
        root.addView(button, lp(true, -2, bottom = 8))
    }

    private fun chooseFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, filePickerRequest)
    }

    private fun startReceiver() {
        pendingRole = "receiver"
        closePeer()
        createPeer(isSender = false)
        setStatus("গ্রহণকারী প্রস্তুত। প্রেরকের Offer QR স্ক্যান করুন।")
    }

    private fun createOffer() {
        sendStarted.set(false)
        pendingRole = "sender"
        closePeer()
        createPeer(isSender = true)
        val p = peer ?: return
        p.createDataChannel("file-transfer", DataChannel.Init()).also { attachChannel(it) }
        p.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                p.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() { waitForIceAndShow("offer") }
                    override fun onSetFailure(error: String) = setStatus("SDP সেট ব্যর্থ: $error")
                    override fun onCreateSuccess(sdp: SessionDescription) {}
                    override fun onCreateFailure(error: String) {}
                }, sdp)
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) = setStatus("Offer তৈরি ব্যর্থ: $error")
            override fun onCreateFailure(error: String) = setStatus("Offer তৈরি ব্যর্থ: $error")
        }, MediaConstraints())
    }

    private fun createPeer(isSender: Boolean) {
        val config = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }
        peer = factory?.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                runOnUiThread {
                    setStatus("P2P সংযোগ: $state")
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) {
                    runOnUiThread { showSdpQr(if (isSender) "offer" else "answer") }
                }
            }
            override fun onIceCandidate(candidate: IceCandidate) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: org.webrtc.MediaStream) {}
            override fun onRemoveStream(stream: org.webrtc.MediaStream) {}
            override fun onDataChannel(dataChannel: DataChannel) { attachChannel(dataChannel) }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<out org.webrtc.MediaStream>) {}
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                runOnUiThread { setStatus("সংযোগের অবস্থা: $newState") }
            }
        })
    }

    private fun waitForIceAndShow(kind: String) {
        setStatus("ICE candidate সংগ্রহ হচ্ছে। সম্পূর্ণ হলে QR দেখানো হবে…")
        // Some WebRTC builds may not invoke COMPLETE if gathering fails; the observer
        // owns the normal completion path.
    }

    private fun showSdpQr(kind: String) {
        val sdp = peer?.localDescription?.description ?: return
        val payload = "RFS1|$kind|$sdp"
        try {
            val bitmap = qrBitmap(payload, 900)
            qr.setImageBitmap(bitmap)
            qr.visibility = View.VISIBLE
            details.text = "QR ধরন: $kind\nSDP অক্ষর: ${sdp.length}\nঅন্য ফোনে এই QR স্ক্যান করুন।"
            setStatus(if (kind == "offer") "Offer QR প্রস্তুত। অন্য ফোনে স্ক্যান করুন।" else "Answer QR প্রস্তুত। প্রেরকের ফোনে স্ক্যান করুন।")
        } catch (e: Exception) {
            qr.visibility = View.GONE
            details.text = "QR তৈরি করা যায়নি। SDP অনেক বড় হতে পারে।\nSDP length: ${sdp.length}"
            setStatus("QR তৈরি ব্যর্থ: ${e.message}")
        }
    }

    private fun scanSdp(expectedKind: String) {
        val integrator = IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt("Rishat File Share: $expectedKind QR স্ক্যান করুন")
        integrator.setBeepEnabled(false)
        integrator.setOrientationLocked(false)
        integrator.initiateScan()
    }

    @Deprecated("Deprecated by Android; kept for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == filePickerRequest && resultCode == Activity.RESULT_OK) {
            selectedUri = data?.data
            if (selectedUri != null) {
                val uri = selectedUri!!
                selectedName = queryDisplayName(uri) ?: "shared_file"
                selectedSize = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                createOffer()
            }
            return
        }
        if (requestCode == saveRequest && resultCode == Activity.RESULT_OK) {
            val uri = data?.data
            val file = incomingFile
            if (uri != null && file != null) {
                contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                file.delete()
                Toast.makeText(this, "ফাইল সংরক্ষণ সম্পন্ন", Toast.LENGTH_LONG).show()
            }
            return
        }
        val scan = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scan != null && scan.contents != null) {
            handleScannedPayload(scan.contents)
        }
    }

    private fun handleScannedPayload(payload: String) {
        if (!payload.startsWith("RFS1|")) {
            setStatus("এটি Rishat File Share QR নয়।")
            return
        }
        val kind = payload.substringAfter("RFS1|").substringBefore("|")
        val sdpText = payload.substringAfter("RFS1|$kind|", "")
        if (sdpText.isBlank()) { setStatus("QR-এ SDP পাওয়া যায়নি।"); return }
        val p = peer ?: run {
            setStatus("আগে Send বা Receive নির্বাচন করুন।")
            return
        }
        val type = if (kind == "offer") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
        p.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                if (type == SessionDescription.Type.OFFER) {
                    p.createAnswer(object : SdpObserver {
                        override fun onCreateSuccess(sdp: SessionDescription) {
                            p.setLocalDescription(object : SdpObserver {
                                override fun onSetSuccess() { setStatus("Answer সেট হয়েছে; ICE সম্পূর্ণ হলে Answer QR দেখাবে।") }
                                override fun onSetFailure(error: String) = setStatus("Answer সেট ব্যর্থ: $error")
                                override fun onCreateSuccess(sdp: SessionDescription) {}
                                override fun onCreateFailure(error: String) {}
                            }, sdp)
                        }
                        override fun onSetSuccess() {}
                        override fun onSetFailure(error: String) = setStatus("Answer তৈরি ব্যর্থ: $error")
                        override fun onCreateFailure(error: String) = setStatus("Answer তৈরি ব্যর্থ: $error")
                    }, MediaConstraints())
                } else {
                    setStatus("Answer গ্রহণ করা হয়েছে; P2P সংযোগের অপেক্ষা করুন।")
                }
            }
            override fun onSetFailure(error: String) = setStatus("SDP গ্রহণ ব্যর্থ: $error")
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onCreateFailure(error: String) {}
        }, SessionDescription(type, sdpText))
    }

    private fun attachChannel(dc: DataChannel) {
        channel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                val currentState = dc.state()
                runOnUiThread {
                    setStatus("ডাটা সংযোগ: $currentState")
                    if (currentState == DataChannel.State.OPEN && pendingRole == "sender" && selectedUri != null && sendStarted.compareAndSet(false, true)) {
                        // Once the QR handshake succeeds, start sending exactly once.
                        sendSelectedFile()
                    }
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                if (buffer.binary) receiveBinary(bytes) else receiveControl(String(bytes, StandardCharsets.UTF_8))
            }
        })
    }

    private fun receiveControl(text: String) {
        // Wire format: META|filename|size|mime|sha256; filename delimiters are sanitized by sender.
        val parts = text.split("|", limit = 5)
        if (parts.size >= 5 && parts[0] == "META") {
            incomingName = parts[1].replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "received_file" }
            expectedBytes = parts[2].toLongOrNull() ?: -1
            incomingMime = parts[3]
            incomingExpectedHash = parts[4]
            receivedBytes = 0
            incomingFile = File(cacheDir, "incoming_${System.currentTimeMillis()}.part")
            incomingOutput = FileOutputStream(incomingFile!!)
            runOnUiThread { setStatus("ফাইল গ্রহণ হচ্ছে: $incomingName") }
        } else if (text == "DONE") {
            incomingOutput?.flush()
            incomingOutput?.close()
            incomingOutput = null
            val file = incomingFile ?: return
            try {
                val actualHash = sha256File(file)
                if (incomingExpectedHash.length != 64 || !actualHash.equals(incomingExpectedHash, ignoreCase = true)) {
                    file.delete()
                    incomingFile = null
                    runOnUiThread { setStatus("SHA-256 যাচাই ব্যর্থ। ফাইল বাতিল করা হয়েছে; আবার পাঠান।") }
                    return
                }
            } catch (e: Exception) {
                file.delete()
                incomingFile = null
                runOnUiThread { setStatus("ফাইল যাচাই করা যায়নি: ${e.message}") }
                return
            }
            addTransferHistory("গ্রহণ", incomingName, file.length(), "WebRTC • SHA-256 যাচাই সফল")
            runOnUiThread {
                setStatus("ফাইল গ্রহণ ও SHA-256 যাচাই সম্পন্ন: $incomingName (${file.length()} bytes)। সংরক্ষণের স্থান নির্বাচন করুন।")
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = incomingMime.ifBlank { "*/*" }
                    putExtra(Intent.EXTRA_TITLE, incomingName)
                }
                startActivityForResult(intent, saveRequest)
            }
        }
    }

    private fun receiveBinary(bytes: ByteArray) {
        try {
            incomingOutput?.write(bytes)
            receivedBytes += bytes.size
            runOnUiThread { status.text = "ফাইল গ্রহণ হচ্ছে: $incomingName\n$receivedBytes / $expectedBytes bytes" }
        } catch (e: Exception) {
            runOnUiThread { setStatus("ফাইল লেখা ব্যর্থ: ${e.message}") }
        }
    }

    private fun sendSelectedFile() {
        val uri = selectedUri ?: return
        val dc = channel ?: run { setStatus("DataChannel প্রস্তুত নয়।"); return }
        if (dc.state() != DataChannel.State.OPEN) { setStatus("অন্য ডিভাইসের সংযোগ সম্পূর্ণ হওয়ার অপেক্ষা করুন।"); return }
        thread(name = "rfs-file-sender") {
            try {
                val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                setStatus("ফাইলের SHA-256 checksum তৈরি হচ্ছে…")
                val expectedHash = sha256Uri(uri)
                val metadata = "META|${selectedName.replace("|", "_")}|$selectedSize|$mime|$expectedHash"
                if (!dc.send(DataChannel.Buffer(ByteBuffer.wrap(metadata.toByteArray(StandardCharsets.UTF_8)), false))) {
                    throw IllegalStateException("Metadata পাঠানো যায়নি")
                }
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "ফাইল খোলা যায়নি" }
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        while (dc.bufferedAmount() > 1024 * 1024 && dc.state() == DataChannel.State.OPEN) Thread.sleep(20)
                        if (dc.state() != DataChannel.State.OPEN) throw IllegalStateException("সংযোগ বিচ্ছিন্ন")
                        val data = ByteBuffer.wrap(buffer.copyOf(count))
                        if (!dc.send(DataChannel.Buffer(data, true))) throw IllegalStateException("Chunk পাঠানো যায়নি")
                    }
                }
                dc.send(DataChannel.Buffer(ByteBuffer.wrap("DONE".toByteArray(StandardCharsets.UTF_8)), false))
                addTransferHistory("পাঠানো", selectedName, selectedSize, "WebRTC • SHA-256 checksum পাঠানো হয়েছে")
                runOnUiThread { setStatus("ফাইল পাঠানো শেষ হয়েছে।") }
            } catch (e: Exception) {
                runOnUiThread { setStatus("ফাইল পাঠানো ব্যর্থ: ${e.message}") }
            }
        }
    }

    private fun addTransferHistory(direction: String, name: String, size: Long, result: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val line = "$stamp • $direction • ${name.replace('\n', ' ').take(100)} • ${size.coerceAtLeast(0L)} bytes • $result"
        val prefs = getSharedPreferences("rishat_transfer", MODE_PRIVATE)
        val old = prefs.getString("transfer_history", "").orEmpty().lines().filter { it.isNotBlank() }
        prefs.edit().putString("transfer_history", (listOf(line) + old).take(50).joinToString("\n")).apply()
    }

    private fun sha256Uri(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        } ?: throw IllegalStateException("ফাইল খোলা যায়নি")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return uri.lastPathSegment
    }

    private fun qrBitmap(contents: String, size: Int): Bitmap {
        val matrix = MultiFormatWriter().encode(contents, BarcodeFormat.QR_CODE, size, size)
        return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).also { bitmap ->
            for (x in 0 until size) for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
    }

    private fun setStatus(text: String) {
        if (::status.isInitialized) runOnUiThread { status.text = text }
    }

    private fun closePeer() {
        sendStarted.set(false)
        try { channel?.unregisterObserver() } catch (_: Exception) {}
        try { channel?.close() } catch (_: Exception) {}
        channel = null
        try { peer?.close() } catch (_: Exception) {}
        peer = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun lp(match: Boolean, height: Int, top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(if (match) LinearLayout.LayoutParams.MATCH_PARENT else LinearLayout.LayoutParams.WRAP_CONTENT, height).apply {
            topMargin = dp(top); bottomMargin = dp(bottom)
        }

    override fun onDestroy() {
        closePeer()
        factory?.dispose()
        executor.shutdownNow()
        super.onDestroy()
    }
}
