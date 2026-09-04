package net.megaproxy487.vpn

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

object DiagnosticLog {
    fun add(message: String) {
        // The persistent logger already has its own bounded background queue. Do not
        // mirror normal VPN traffic into Compose state: native diagnostics can be very
        // frequent and flooding the main looper makes the entire UI unresponsive.
        PersistentDiagnosticLog.write(message)
    }

    fun clear() = Unit
}

enum class TestState { IDLE, RUNNING, SUCCEEDED, FAILED }

object TestDiagnosticLog {
    private const val MAX_ENTRIES = 300
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingUiUpdates = AtomicInteger(0)
    val entries = mutableStateListOf<String>()
    private val mutableState = androidx.compose.runtime.mutableStateOf(TestState.IDLE)
    private val mutableExitIp = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val mutableCountryCode = androidx.compose.runtime.mutableStateOf<String?>(null)
    val state: androidx.compose.runtime.State<TestState> = mutableState
    val exitIp: androidx.compose.runtime.State<String?> = mutableExitIp
    val countryCode: androidx.compose.runtime.State<String?> = mutableCountryCode

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else if (pendingUiUpdates.incrementAndGet() <= MAX_PENDING_UI_UPDATES) {
            mainHandler.post {
                try { action() } finally { pendingUiUpdates.decrementAndGet() }
            }
        } else pendingUiUpdates.decrementAndGet()
    }

    fun reset() = onMain {
        entries.clear()
        mutableExitIp.value = null
        mutableCountryCode.value = null
        mutableState.value = TestState.IDLE
    }

    fun begin() = onMain {
        entries.clear()
        mutableExitIp.value = null
        mutableCountryCode.value = null
        mutableState.value = TestState.RUNNING
    }

    fun add(message: String) {
        val safeMessage = PrivacyLogSanitizer.sanitize(message)
        PersistentDiagnosticLog.write("scope=connection_test $safeMessage")
        onMain {
            entries.add("${LocalTime.now().format(timeFormat)}  $safeMessage")
            while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        }
    }

    fun succeed(ip: String, countryCode: String?) = onMain {
        mutableExitIp.value = ip
        mutableCountryCode.value = countryCode
        mutableState.value = TestState.SUCCEEDED
    }

    fun fail(message: String? = null) = onMain {
        if (message != null) add(message)
        mutableState.value = TestState.FAILED
    }

    private const val MAX_PENDING_UI_UPDATES = 512
}
