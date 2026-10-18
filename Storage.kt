package com.rishat.fileshare

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

fun guessMime(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext.isEmpty()) return "application/octet-stream"
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
}

/** Content-URI থেকে পাঠানোর উৎস। */
class UriSource(
    private val ctx: Context,
    private val uri: Uri,
    override val name: String,
    override val size: Long,
    override val mime: String
) : FileSource {

    override fun open(): InputStream =
        ctx.contentResolver.openInputStream(uri) ?: throw IOException("ফাইল খোলা যায়নি")

    companion object {
        fun from(ctx: Context, uri: Uri): UriSource? {
            var name: String? = null
            var size = -1L
            try {
                ctx.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
            } catch (_: Exception) {
            }
            if (size < 0) {
                size = try {
                    ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                } catch (_: Exception) {
                    -1L
                }
            }
            val finalName = (name ?: uri.lastPathSegment ?: "shared_file").substringAfterLast('/')
            val providerMime: String? = try {
                ctx.contentResolver.getType(uri)
            } catch (_: Exception) {
                null
            }
            val mime = providerMime ?: guessMime(finalName)
            return UriSource(ctx, uri, finalName, size, mime)
        }
    }
}

/** গ্রহণ করা ফাইল সরাসরি Download/Rishat File Share-এ লেখে। */
class Storage(private val ctx: Context) {

    fun createSink(name: String, @Suppress("UNUSED_PARAMETER") senderMime: String, size: Long): FileSink {
        checkSpace(size)
        // MIME সবসময় এক্সটেনশন থেকে নির্ধারণ — MediaStore-এ নাম/টাইপ অমিল এড়াতে
        val mime = guessMime(name)
        return if (Build.VERSION.SDK_INT >= 29) MediaStoreSink(ctx, name, mime) else LegacySink(ctx, name)
    }

    @Suppress("DEPRECATION")
    private fun checkSpace(size: Long) {
        val free = try {
            StatFs(Environment.getExternalStorageDirectory().path).availableBytes
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
        if (size + 20L * 1024 * 1024 > free) throw IOException("ফোনে পর্যাপ্ত জায়গা নেই")
    }
}

@SuppressLint("NewApi")
private class MediaStoreSink(ctx: Context, name: String, mime: String) : FileSink {
    private val resolver = ctx.contentResolver
    private val uri: Uri
    override val output: OutputStream
    override val label: String = "Download/Rishat File Share"
    private val done = AtomicBoolean(false)

    init {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Rishat File Share")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val created = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("ফাইল তৈরি করা যায়নি")
        val stream = try {
            resolver.openOutputStream(created)
        } catch (e: Exception) {
            null
        }
        if (stream == null) {
            try { resolver.delete(created, null, null) } catch (_: Exception) {}
            throw IOException("ফাইল খোলা যায়নি")
        }
        uri = created
        output = BufferedOutputStream(stream, Proto.CHUNK)
    }

    override fun commit(): Uri? {
        output.flush()
        output.close()
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, values, null, null)
        done.set(true)
        return uri
    }

    override fun abort() {
        if (done.get()) return
        try { output.close() } catch (_: Exception) {}
        try { resolver.delete(uri, null, null) } catch (_: Exception) {}
    }
}

/** Android 8–9: অ্যাপের নিজস্ব এক্সটার্নাল Download ফোল্ডারে (কোনো স্টোরেজ পারমিশন লাগে না)। */
private class LegacySink(private val ctx: Context, name: String) : FileSink {
    private val dir: File = (ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir).also { it.mkdirs() }
    private val target: File = uniqueFile(dir, name)
    private val temp: File = File(dir, target.name + ".part")
    override val output: OutputStream = BufferedOutputStream(FileOutputStream(temp), Proto.CHUNK)
    override val label: String = dir.absolutePath
    private val done = AtomicBoolean(false)

    override fun commit(): Uri? {
        output.flush()
        output.close()
        if (!temp.renameTo(target)) throw IOException("ফাইল সংরক্ষণ করা যায়নি")
        done.set(true)
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", target)
    }

    override fun abort() {
        if (done.get()) return
        try { output.close() } catch (_: Exception) {}
        temp.delete()
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (f.exists()) {
            f = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            i++
        }
        return f
    }
}
