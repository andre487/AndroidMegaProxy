package net.megaproxy487.vpn

import android.content.Context
import android.os.Build
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

object PersistentDiagnosticLog {
    const val DEFAULT_LIMIT_MB = 3
    const val MIN_LIMIT_MB = 1
    const val MAX_LIMIT_MB = 100

    private const val CURRENT_FILE = "diagnostic.log"
    private const val PREVIOUS_FILE = "diagnostic.1.log"
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "megaproxy-diagnostic-log").apply { isDaemon = true }
    }
    private val lock = Any()
    private val sessionId = UUID.randomUUID().toString().take(8)
    @Volatile private var directory: File? = null
    @Volatile private var limitMb = DEFAULT_LIMIT_MB

    fun initialize(context: Context, configuredLimitMb: Int) {
        directory = File(context.filesDir, "logs").also { it.mkdirs() }
        limitMb = configuredLimitMb.coerceIn(MIN_LIMIT_MB, MAX_LIMIT_MB)
        enforceLimitAsync()
        write("event=logger_initialized session=$sessionId api=${Build.VERSION.SDK_INT} limit_mb=$limitMb")
    }

    fun setLimitMb(value: Int) {
        limitMb = value.coerceIn(MIN_LIMIT_MB, MAX_LIMIT_MB)
        enforceLimitAsync()
        write("event=log_limit_changed limit_mb=$limitMb")
    }

    fun write(rawMessage: String) {
        val safeMessage = PrivacyLogSanitizer.sanitize(rawMessage)
        val line = "${Instant.now()} session=$sessionId $safeMessage\n"
        executor.execute {
            synchronized(lock) {
                val logDirectory = directory ?: return@synchronized
                rotateIfNeeded(logDirectory, line.toByteArray().size.toLong())
                File(logDirectory, CURRENT_FILE).appendText(line, Charsets.UTF_8)
            }
        }
    }

    fun readTail(maxBytes: Int): String = synchronized(lock) {
        val logDirectory = directory ?: return ""
        val files = listOf(PREVIOUS_FILE, CURRENT_FILE).map { File(logDirectory, it) }.filter(File::isFile)
        var remaining = maxBytes.coerceAtLeast(1)
        val chunks = ArrayDeque<ByteArray>()
        for (file in files.asReversed()) {
            if (remaining == 0) break
            val count = minOf(file.length(), remaining.toLong()).toInt()
            if (count == 0) continue
            val chunk = ByteArray(count)
            RandomAccessFile(file, "r").use { input ->
                input.seek(file.length() - count)
                input.readFully(chunk)
            }
            chunks.addFirst(chunk)
            remaining -= count
        }
        val bytes = chunks.fold(ByteArray(0)) { result, chunk -> result + chunk }
        val text = bytes.toString(Charsets.UTF_8)
        if (remaining == 0) text.substringAfter('\n', "") else text
    }

    fun copyTo(output: OutputStream) = synchronized(lock) {
        val logDirectory = directory ?: return
        listOf(PREVIOUS_FILE, CURRENT_FILE).forEach { name ->
            val file = File(logDirectory, name)
            if (file.isFile) file.inputStream().buffered().use { it.copyTo(output) }
        }
    }

    fun clear() {
        executor.execute {
            synchronized(lock) {
                val logDirectory = directory ?: return@synchronized
                File(logDirectory, CURRENT_FILE).delete()
                File(logDirectory, PREVIOUS_FILE).delete()
            }
            write("event=log_cleared")
        }
    }

    private fun rotateIfNeeded(logDirectory: File, incomingBytes: Long) {
        val segmentLimit = segmentLimitBytes()
        val current = File(logDirectory, CURRENT_FILE)
        if (current.length() + incomingBytes <= segmentLimit) return
        val previous = File(logDirectory, PREVIOUS_FILE)
        previous.delete()
        if (current.isFile) current.renameTo(previous)
        if (incomingBytes > segmentLimit) current.writeText("", Charsets.UTF_8)
    }

    private fun enforceLimitAsync() {
        executor.execute {
            synchronized(lock) {
                val logDirectory = directory ?: return@synchronized
                val segmentLimit = segmentLimitBytes()
                trimToTail(File(logDirectory, PREVIOUS_FILE), segmentLimit)
                trimToTail(File(logDirectory, CURRENT_FILE), segmentLimit)
            }
        }
    }

    private fun segmentLimitBytes(): Long = limitMb.toLong() * 1024 * 1024 / 2

    private fun trimToTail(file: File, maxBytes: Long) {
        if (!file.isFile || file.length() <= maxBytes) return
        val temporary = File(file.parentFile, "${file.name}.trim")
        RandomAccessFile(file, "r").use { input ->
            input.seek(file.length() - maxBytes)
            while (input.filePointer < input.length() && input.read() != '\n'.code) Unit
            temporary.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
        file.delete()
        temporary.renameTo(file)
    }
}

object PrivacyLogSanitizer {
    private val url = Regex("(?i)https?://[^\\s]+")
    private val ipv4 = Regex("(?<![A-Za-z0-9])(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?")
    private val bracketedIpv6 = Regex("\\[[0-9A-Fa-f:]+](?::\\d{1,5})?")
    private val hostname = Regex("(?i)(?<![A-Za-z0-9_.-])(?:[A-Za-z0-9-]+\\.)+[A-Za-z]{2,}(?::\\d{1,5})?")
    private val credentials = Regex("(?i)(username|password|authorization|proxy-authorization)=\\S+")
    private val packageName = Regex("(?<![A-Za-z0-9_])(?:[a-z][a-z0-9_]*\\.){2,}[a-zA-Z0-9_]+")

    fun sanitize(message: String): String = message
        .replace(credentials) { "${it.groupValues[1]}=[redacted]" }
        .replace(url, "[url]")
        .replace(bracketedIpv6, "[ip]")
        .replace(ipv4, "[ip]")
        .replace(hostname, "[host]")
        .replace(packageName, "[package]")
        .replace(Regex("[\\r\\n]+"), " ")
        .take(2_000)
}
