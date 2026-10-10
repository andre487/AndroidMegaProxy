package net.megaproxy487

import androidx.compose.ui.test.*
import net.megaproxy487.vpn.NativeConnectionStats
import org.junit.Test

class ConnectionStatsCardUiTest : MainUiTestBase() {
    @Test fun showsKernelMetricsAndFiveMinuteScope() {
        content { ConnectionStatsCard(DisplayedConnectionStats(NativeConnectionStats(0, 0, 42.0, 7), 0.0, 0.0)) }
        node(R.string.tcp_rtt).assertIsDisplayed()
        compose.onNodeWithText(activity.getString(R.string.tcp_retransmits, 7L)).assertIsDisplayed()
        node(R.string.tcp_metrics_scope).assertIsDisplayed()
    }

    @Test fun unavailableIsNotZero() {
        content { ConnectionStatsCard(DisplayedConnectionStats(NativeConnectionStats(0, 0, null, null), 0.0, 0.0)) }
        node(R.string.tcp_retransmits_unavailable).assertIsDisplayed()
        compose.onNodeWithText("—").assertIsDisplayed()
    }
    @Test fun showsQuicMetricsWithSeparateMeaning() {
        content { ConnectionStatsCard(DisplayedConnectionStats(NativeConnectionStats(0, 0, 999.0, 999, 42.0, 0), 0.0, 0.0), quic = true) }
        node(R.string.quic_rtt).assertIsDisplayed()
        compose.onNodeWithText(activity.getString(R.string.quic_packets_lost, 0L)).assertIsDisplayed()
        node(R.string.quic_metrics_scope).assertIsDisplayed()
        node(R.string.tcp_rtt).assertDoesNotExist()
    }

    @Test fun unavailableQuicIsNotZeroOrTcp() {
        content { ConnectionStatsCard(DisplayedConnectionStats(NativeConnectionStats(0, 0, 42.0, 7), 0.0, 0.0), quic = true) }
        node(R.string.quic_packets_lost_unavailable).assertIsDisplayed()
        compose.onNodeWithText("—").assertIsDisplayed()
        node(R.string.tcp_rtt).assertDoesNotExist()
    }
}
