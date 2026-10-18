package com.rishat.fileshare

import android.net.Uri
import android.os.SystemClock
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

enum class TState { QUEUED, ACTIVE, VERIFYING, DONE, FAILED, CANCELED }

enum class LinkType { DIRECT, LAN }

/** পাঠানোর ফাইলের উৎস। */
interface FileSource {
    val name: String
    val size: Long
    val mime: String
    fun open(): InputStream
}

/** গ্রহণ করা ফাইলের গন্তব্য (সরাসরি Downloads-এ লেখে, কোনো ডাবল-কপি নেই)। */
interface FileSink {
    val output: OutputStream
    val label: String
    fun commit(): Uri?
    fun abort()
}

class TransferItem(
    val uid: Long,
    val outgoing: Boolean,
    val name: String,
    val size: Long,
    val mime: String
) {
    @Volatile var state: TState = TState.QUEUED
    @Volatile var bytes: Long = 0L
    @Volatile var message: String = ""
    @Volatile var savedUri: Uri? = null
    @Volatile var savedLabel: String = ""
    @Volatile var wireId: Int = 0
    @Volatile var speed: Double = 0.0
    val cancelRequested = AtomicBoolean(false)

    private var lastBytes = 0L
    private var lastTick = 0L

    val isFinished: Boolean
        get() = state == TState.DONE || state == TState.FAILED || state == TState.CANCELED

    @Synchronized
    fun sampleSpeed() {
        val now = SystemClock.elapsedRealtime()
        if (lastTick == 0L) {
            lastTick = now
            lastBytes = bytes
            return
        }
        val dt = now - lastTick
        if (dt < 400) return
        val inst = (bytes - lastBytes) * 1000.0 / dt
        speed = if (speed == 0.0) inst else speed * 0.6 + inst * 0.4
        lastTick = now
        lastBytes = bytes
        if (state != TState.ACTIVE) speed = 0.0
    }

    fun percent(): Int =
        if (size <= 0L) (if (state == TState.DONE) 100 else 0)
        else ((bytes * 100) / size).toInt().coerceIn(0, 100)
}
