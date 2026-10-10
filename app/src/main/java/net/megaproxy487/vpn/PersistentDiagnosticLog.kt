package net.megaproxy487.vpn

import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.io.IOException
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object PersistentDiagnosticLog {
    const val DEFAULT_LIMIT_MB = 3
    const val MIN_LIMIT_MB = 1
    const val MAX_LIMIT_MB = 100

    private const val CURRENT_FILE = "diagnostic.log"
    private const val PREVIOUS_FILE = "diagnostic.1.log"
    // Native networking can emit diagnostics faster than flash storage can append them.
    // Keep memory bounded under an error storm; oldest pending diagnostics are expendable.
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2_048),
        { task -> Thread(task, "megaproxy-diagnostic-log").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )
    private val lock = Any()
    private val revisionCounter = AtomicLong()
    val revision: Long get() = revisionCounter.get()
    private val limiter = DiagnosticLogLimiter()
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
        val safeMessage = limiter.filter(PrivacyLogSanitizer.sanitize(rawMessage), SystemClock.elapsedRealtime()) ?: return
        val line = "${Instant.now()} session=$sessionId $safeMessage\n"
        enqueue {
            synchronized(lock) {
                val logDirectory = directory ?: return@synchronized
                rotateIfNeeded(logDirectory, line.toByteArray().size.toLong())
                File(logDirectory, CURRENT_FILE).appendText(line, Charsets.UTF_8)
                revisionCounter.incrementAndGet()
            }
        }
    }

    /** Crash-path write: deliberately synchronous so it survives immediate process termination. */
    fun writeCrash(thread: Thread, throwable: Throwable): Unit = synchronized(lock) {
        val logDirectory = directory ?: return
        val entry = buildString {
            append("${Instant.now()} session=$sessionId event=uncaught_exception api=${Build.VERSION.SDK_INT}")
            append(" thread_id=${thread.id}\n")
            var current: Throwable? = throwable
            var causeDepth = 0
            while (current != null && causeDepth < 8) {
                val prefix = if (causeDepth == 0) "exception" else "cause_$causeDepth"
                append("$prefix=${safeCrashClass(current.javaClass.name)}")
                append(" reason=${nativeFailureReason(current.message.orEmpty())}\n")
                current.stackTrace.take(256).forEach { frame ->
                    append("at ${safeCrashClass(frame.className)}.${frame.methodName}")
                    append("(${frame.fileName ?: "unknown"}:${frame.lineNumber})\n")
                }
                current = current.cause
                causeDepth++
            }
            append("${Instant.now()} session=$sessionId event=crash_end\n")
        }
        rotateIfNeeded(logDirectory, entry.toByteArray(Charsets.UTF_8).size.toLong())
        File(logDirectory, CURRENT_FILE).appendText(entry, Charsets.UTF_8)
        revisionCounter.incrementAndGet()
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

    fun copyTo(output: OutputStream) {
        // A bounded logger queue may discard diagnostics, but an export must never lose
        // its task and then wait forever on a discarded Future.
        synchronized(lock) {
            val logDirectory = directory ?: return
            listOf(PREVIOUS_FILE, CURRENT_FILE).forEach { name ->
                val file = File(logDirectory, name)
                if (file.isFile) file.inputStream().buffered().use { it.copyTo(output) }
            }
        }
    }

    fun clear() {
        enqueue {
            synchronized(lock) {
                val logDirectory = directory ?: return@synchronized
                File(logDirectory, CURRENT_FILE).delete()
                File(logDirectory, PREVIOUS_FILE).delete()
                revisionCounter.incrementAndGet()
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
        enqueue {
            synchronized(lock) {
                val logDirectory = directory ?: return@synchronized
                val segmentLimit = segmentLimitBytes()
                trimToTail(File(logDirectory, PREVIOUS_FILE), segmentLimit)
                trimToTail(File(logDirectory, CURRENT_FILE), segmentLimit)
                revisionCounter.incrementAndGet()
            }
        }
    }

    private fun enqueue(action: () -> Unit) {
        executor.execute { runDiagnosticIo(action) }
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

    private fun safeCrashClass(name: String): String = when {
        name.startsWith("net.megaproxy487.") ||
            name.startsWith("android.") || name.startsWith("androidx.") ||
            name.startsWith("java.") || name.startsWith("kotlin.") ||
            name.startsWith("kotlinx.") -> name
        else -> "[external]"
    }
}

/** Bound repetitive traffic/error records after delivering every event to recovery/UI. */
internal class DiagnosticLogLimiter {
    private data class Bucket(var start: Long, var emitted: Int = 0, var suppressed: Int = 0)
    private val buckets = LinkedHashMap<String, Bucket>()
    private val limitedEvents = setOf("connection", "doh", "ssh_transport", "masque_udp")
    private val fields = Regex("(?:^| )(event|protocol|mode|hop|stage|result|reason|operation|provider_index|status|scope|dpi_hint)=([^ ]+)")

    @Synchronized
    fun filter(message: String, nowMillis: Long): String? {
        // Lifecycle, host-key decisions, crashes and user actions stay unthrottled.
        val event = fields.findAll(message).firstOrNull { it.groupValues[1] == "event" }?.groupValues?.get(2)
        if (event !in limitedEvents) return message
        val key = fields.findAll(message).joinToString(" ") { it.value.trim() }
        val bucket = buckets[key] ?: Bucket(nowMillis).also {
            if (buckets.size >= 128) buckets.remove(buckets.keys.first())
            buckets[key] = it
        }
        var suppressed = 0
        if (nowMillis - bucket.start >= 10_000) {
            suppressed = bucket.suppressed
            bucket.start = nowMillis
            bucket.emitted = 0
            bucket.suppressed = 0
        }
        if (bucket.emitted >= 20) {
            if (bucket.suppressed < Int.MAX_VALUE) bucket.suppressed++
            return null
        }
        bucket.emitted++
        return if (suppressed > 0) "$message suppressed=$suppressed" else message
    }
}

object PrivacyLogSanitizer {
    private val email = Regex("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}")
    private val url = Regex("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s]+")
    private val ipv4 = Regex("(?<![A-Za-z0-9])(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?")
    private val bracketedIpv6 = Regex("\\[[0-9A-Fa-f:]+](?::\\d{1,5})?")
    private val hostname = Regex("(?i)(?<![A-Za-z0-9_.-])(?:[A-Za-z0-9-]+\\.)+[A-Za-z]{2,}(?::\\d{1,5})?")
    private val credentials = Regex("(?i)\\b(username|password|authorization|proxy-authorization|privateKey|private_key|token|secret)\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|(?:Basic|Bearer)\\s+\\S+|\\S+)")
    private val bareIpv6 = Regex("(?<![A-Za-z0-9_])(?:[0-9A-Fa-f]*:){2,}[0-9A-Fa-f:.]*(?:%[A-Za-z0-9_.-]+)?")
    private val privateKey = Regex("-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----[\\s\\S]*?(?:-----END (?:[A-Z]+ )?PRIVATE KEY-----|\\z)")
    private val hostKeyDecision = Regex("SSH_HOST_KEY_(?:UNKNOWN|CHANGED)\\|[^\\r\\n]*")
    private val hostKey = Regex("SHA256:[A-Za-z0-9+/=]+")
    private val macAddress = Regex("(?i)(?<![0-9A-F])(?:[0-9A-F]{2}:){5}[0-9A-F]{2}(?![0-9A-F])")
    private val uuid = Regex("(?i)(?<![0-9A-F])[0-9A-F]{8}-[0-9A-F]{4}-[1-5][0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12}(?![0-9A-F])")
    private val privatePath = Regex("(?i)/(?:data|storage|sdcard|mnt)/[^\\s]+")
    private val packageName = Regex("(?<![A-Za-z0-9_])(?:[a-z][a-z0-9_]*\\.){2,}[a-zA-Z0-9_]+")

    private val lineBreaks = Regex("[\\r\\n]+")

    fun sanitize(message: String): String = message.take(16_000)
        .replace(privateKey, "[private_key]")
        .replace(hostKeyDecision) { it.value.substringBefore('|') + " [host_key_redacted]" }
        .replace(hostKey, "[host_key]")
        .replace(credentials) { "${it.groupValues[1]}=[redacted]" }
        .replace(email, "[email]")
        .replace(url, "[url]")
        .replace(macAddress, "[mac]")
        .replace(uuid, "[id]")
        .replace(privatePath, "[path]")
        .replace(bracketedIpv6, "[ip]")
        .replace(bareIpv6) { if ("::" in it.value || it.value.count { char -> char == ':' } >= 7) "[ip]" else it.value }
        .replace(ipv4, "[ip]")
        .replace(hostname, "[host]")
        .replace(packageName, "[package]")
        .replace(lineBreaks, " ")
        .take(2_000)
}

/** Diagnostics must not terminate the VPN process when storage is unavailable. */
internal fun runDiagnosticIo(action: () -> Unit): Boolean = try {
    action()
    true
} catch (_: IOException) {
    false
} catch (_: SecurityException) {
    false
}
