package dev.megaproxy.app.vpn

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object DiagnosticLog {
    private const val MAX_ENTRIES = 200
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val mainHandler = Handler(Looper.getMainLooper())

    val entries = mutableStateListOf<String>()

    fun add(message: String) {
        val append = {
            entries.add("${LocalTime.now().format(timeFormat)}  $message")
            while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) append() else mainHandler.post(append)
    }

    fun clear() {
        if (Looper.myLooper() == Looper.getMainLooper()) entries.clear() else mainHandler.post { entries.clear() }
    }
}

enum class TestState { IDLE, RUNNING, SUCCEEDED, FAILED }

object TestDiagnosticLog {
    private const val MAX_ENTRIES = 300
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val mainHandler = Handler(Looper.getMainLooper())
    val entries = mutableStateListOf<String>()
    private val mutableState = androidx.compose.runtime.mutableStateOf(TestState.IDLE)
    private val mutableExitIp = androidx.compose.runtime.mutableStateOf<String?>(null)
    val state: androidx.compose.runtime.State<TestState> = mutableState
    val exitIp: androidx.compose.runtime.State<String?> = mutableExitIp

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    fun reset() = onMain {
        entries.clear()
        mutableExitIp.value = null
        mutableState.value = TestState.IDLE
    }

    fun begin() = onMain {
        entries.clear()
        mutableExitIp.value = null
        mutableState.value = TestState.RUNNING
    }

    fun add(message: String) = onMain {
        entries.add("${LocalTime.now().format(timeFormat)}  $message")
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
    }

    fun succeed(ip: String) = onMain {
        mutableExitIp.value = ip
        mutableState.value = TestState.SUCCEEDED
    }

    fun fail(message: String? = null) = onMain {
        if (message != null) add(message)
        mutableState.value = TestState.FAILED
    }
}
