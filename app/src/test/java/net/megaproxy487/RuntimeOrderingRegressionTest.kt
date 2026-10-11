package net.megaproxy487

import android.app.Application
import android.os.Looper
import net.megaproxy487.vpn.*
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class RuntimeOrderingRegressionTest {
 @org.junit.After fun drainSnapshotNotifications() {
  androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
  shadowOf(Looper.getMainLooper()).idle()
 }
 @Test fun stopMustWinOverQueuedConnectedUpdate() {
  val worker=Thread { VpnRuntimeState.update(VpnConnectionState.CONNECTED) }
  worker.start();worker.join()
  VpnRuntimeState.update(VpnConnectionState.DISCONNECTED)
  shadowOf(Looper.getMainLooper()).idle()
  try { assertEquals(VpnConnectionState.DISCONNECTED,VpnRuntimeState.connection.value) }
  finally { VpnRuntimeState.update(VpnConnectionState.DISCONNECTED) }
 }

 @Test fun queuedSessionCallbacksCannotChangeReplacement() {
  val service = org.robolectric.Robolectric.buildService(ProxyVpnService::class.java).get()
  val generation = ProxyVpnService::class.java.getDeclaredField("startGeneration")
   .apply { isAccessible = true }.get(service) as java.util.concurrent.atomic.AtomicLong
  var completions = 0
  service.runForGeneration(generation.get()) { completions++ }
  generation.incrementAndGet() // Stop/Reconnect replaces the owner before delivery.
  service.runForGeneration(generation.get()) { completions += 10 }
  shadowOf(Looper.getMainLooper()).idle()
  assertEquals(10, completions)
 }

 @Test fun queuedProtocolWarningAndProfileCannotReviveOldSession() {
  var current = true
  val worker = Thread {
   VpnRuntimeState.observeDiagnostic("event=connection protocol=http3 stage=tunnel result=established") { current }
   VpnRuntimeState.updateNetworkWarning("old warning") { current }
   VpnRuntimeState.updateSystem(false, false, "old") { current }
  }
  worker.start(); worker.join()
  current = false
  VpnRuntimeState.update(VpnConnectionState.DISCONNECTED)
  VpnRuntimeState.updateNetworkWarning(null)
  VpnRuntimeState.updateSystem(false, false, "new")
  shadowOf(Looper.getMainLooper()).idle()
  assertEquals(VpnTransportProtocol.UNKNOWN, VpnRuntimeState.transportProtocol.value)
  assertEquals(null, VpnRuntimeState.networkWarning.value)
  assertEquals("new", VpnRuntimeState.connectionProfileId.value)
 }

 @Test fun oldTemporaryTestCannotStopReplacementOrPublishSuccess() {
  val service = org.robolectric.Robolectric.buildService(ProxyVpnService::class.java).get()
  val generation = ProxyVpnService::class.java.getDeclaredField("startGeneration")
   .apply { isAccessible = true }.get(service) as java.util.concurrent.atomic.AtomicLong
  TestDiagnosticLog.begin()
  service.finishConnectionTest(generation.get(), true, ConnectionTestResult("192.0.2.1", null))
  generation.incrementAndGet()
  VpnRuntimeState.update(VpnConnectionState.CONNECTED)
  shadowOf(Looper.getMainLooper()).idle()
  try {
   org.junit.Assert.assertFalse(shadowOf(service).isStoppedBySelf)
   assertEquals(VpnConnectionState.CONNECTED, VpnRuntimeState.connection.value)
   assertEquals(TestState.RUNNING, TestDiagnosticLog.state.value)
   assertEquals(null, TestDiagnosticLog.exitIp.value)
  } finally {
   VpnRuntimeState.update(VpnConnectionState.DISCONNECTED)
   TestDiagnosticLog.reset()
  }
 }
}
