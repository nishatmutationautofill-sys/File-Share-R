package com.rishat.fileshare

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Rishat File Share তার-প্রোটোকল (Wi-Fi Direct ও LAN — দুই মোডেই এক)। */
object Proto {
    const val VERSION = 2
    const val MAGIC = 0x52465332 // "RFS2"
    const val DEFAULT_PORT = 39471
    const val SERVICE_TYPE = "_rishatfs._tcp."

    const val CHUNK = 256 * 1024
    const val SOCKET_BUFFER = 512 * 1024
    const val MAX_FRAME = CHUNK + 64

    const val HEARTBEAT_MS = 4_000L
    const val READ_TIMEOUT_MS = 20_000
    const val HANDSHAKE_TIMEOUT_MS = 10_000
    const val CLIENT_WAIT_MS = 60_000
    const val APPROVAL_TIMEOUT_MS = 45_000L

    // ফ্রেম টাইপ
    const val T_HELLO = 1
    const val T_HELLO_OK = 2
    const val T_HELLO_NO = 3
    const val T_OFFER = 10   // id, name, size, mime
    const val T_DATA = 12    // id, bytes...
    const val T_END = 13     // id, totalBytes, sha256
    const val T_CANCEL = 14  // id
    const val T_RESULT = 15  // id, ok, message
    const val T_PING = 20
    const val T_BYE = 21

    fun payload(block: (DataOutputStream) -> Unit): ByteArray {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        block(dos)
        dos.flush()
        return bos.toByteArray()
    }
}
