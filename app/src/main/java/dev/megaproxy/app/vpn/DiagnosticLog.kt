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
