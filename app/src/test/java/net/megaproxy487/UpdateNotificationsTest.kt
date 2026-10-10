package net.megaproxy487

import android.app.Application
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.Intent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UpdateNotificationsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val scheduler get() = app.getSystemService(JobScheduler::class.java)
    private val notifications get() = app.getSystemService(NotificationManager::class.java)

    @Before fun reset() {
        app.getSharedPreferences("updates", 0).edit().clear().commit()
        scheduler.cancelAll()
        notifications.cancelAll()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test fun defaultsEnabledButUnknownInstallerSchedulesNothing() {
        assertTrue(UpdatePreferences(app).automatic)
        UpdateNotifications.schedule(app)
        assertNull(scheduler.getPendingJob(UPDATE_JOB_ID))
        AppUpdates(app).select(UpdateSource.GITHUB)
        UpdateNotifications.schedule(app)
        val job = scheduler.getPendingJob(UPDATE_JOB_ID)!!
        assertTrue(job.isPeriodic)
        assertTrue(job.isPersisted)
        assertEquals(java.util.concurrent.TimeUnit.DAYS.toMillis(1), job.intervalMillis)
    }

    @Test fun notificationActionsSkipOnlyThatVersionAndDisableCancelsJob() {
        AppUpdates(app).select(UpdateSource.GITHUB)
        UpdateNotifications.schedule(app)
        val update = AppUpdate(UpdateSource.GITHUB, "0.1.2")
        UpdateNotifications.show(app, update, 1000)
        val notification = shadowOf(notifications).getNotification(UPDATE_NOTIFICATION_ID)
        assertNotNull(notification)
        assertEquals(3, notification.actions.size)
        val open = shadowOf(notification.actions[0].actionIntent).savedIntent
        assertEquals(OPEN_UPDATES_ACTION, open.action)
        assertEquals(MainActivity::class.java.name, open.component!!.className)
        UpdateActionReceiver().onReceive(app, shadowOf(notification.actions[1].actionIntent).savedIntent)
        assertNull(shadowOf(notifications).getNotification(UPDATE_NOTIFICATION_ID))
        assertFalse(UpdatePreferences(app).shouldNotify(update, UPDATE_REMINDER_INTERVAL * 2))
        assertTrue(UpdatePreferences(app).shouldNotify(update.copy(version = "0.1.3"), 1001))
        UpdateActionReceiver().onReceive(app, shadowOf(notification.actions[2].actionIntent).savedIntent)
        assertFalse(UpdatePreferences(app).automatic)
        assertNull(scheduler.getPendingJob(UPDATE_JOB_ID))
    }

    @Test fun reminderPersistsAndDoesNotPostBeforeOneWeek() {
        AppUpdates(app).select(UpdateSource.FDROID)
        val update = AppUpdate(UpdateSource.FDROID, "0.1.2")
        UpdateNotifications.show(app, update, 1000)
        notifications.cancelAll()
        UpdateNotifications.show(app, update, 1000 + UPDATE_REMINDER_INTERVAL - 1)
        assertNull(shadowOf(notifications).getNotification(UPDATE_NOTIFICATION_ID))
        UpdateNotifications.show(app, update, 1000 + UPDATE_REMINDER_INTERVAL)
        assertNotNull(shadowOf(notifications).getNotification(UPDATE_NOTIFICATION_ID))
    }

    @Test fun changedSourceOrDisabledChecksSuppressInFlightResult() {
        AppUpdates(app).select(UpdateSource.FDROID)
        UpdateNotifications.show(app, AppUpdate(UpdateSource.GITHUB, "0.1.2"))
        assertNull(shadowOf(notifications).getNotification(UPDATE_NOTIFICATION_ID))
        UpdatePreferences(app).automatic = false
        UpdateNotifications.show(app, AppUpdate(UpdateSource.FDROID, "0.1.2"))
        assertNull(shadowOf(notifications).getNotification(UPDATE_NOTIFICATION_ID))
    }
    @Test fun disablingChecksSuppressesPreviouslyDetectedDialog() {
        AppUpdates(app).select(UpdateSource.GITHUB)
        val prefs = UpdatePreferences(app)
        prefs.detected(UpdateSource.GITHUB, AppUpdate(UpdateSource.GITHUB, "99.0.0"))
        assertNotNull(prefs.pending())
        prefs.automatic = false
        assertNull(prefs.pending())
    }
    @Test fun detectionSurvivesDeniedNotificationsAndReminderAndSkipPersist() {
        AppUpdates(app).select(UpdateSource.GITHUB)
        val update = AppUpdate(UpdateSource.GITHUB, "99.0.0")
        shadowOf(app).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        kotlinx.coroutines.runBlocking { assertFalse(runBackgroundUpdateCheck(app, update.source) { update }) }
        val prefs = UpdatePreferences(app)
        assertTrue(prefs.lastBackgroundTime > 0)
        assertEquals("available", prefs.lastBackgroundResult)
        assertEquals(update, prefs.pending())
        prefs.remindLater(update, 1000)
        assertNull(UpdatePreferences(app).pending(1001))
        assertFalse(prefs.shouldNotify(update, 1001))
        assertEquals(update, prefs.pending(1000 + UPDATE_REMINDER_INTERVAL))
        prefs.skip(UpdatePreferences.key(update))
        assertNull(prefs.pending(1000 + UPDATE_REMINDER_INTERVAL * 2))
        prefs.detected(update.source, update.copy(version = "99.0.1"))
        assertEquals("99.0.1", prefs.pending(1001)?.version)
        AppUpdates(app).select(UpdateSource.FDROID)
        assertNull(prefs.pending())
    }

    @Test fun backgroundFailureRetriesOnlyTransientErrorsAndKeepsDetectedVersion() {
        AppUpdates(app).select(UpdateSource.GITHUB)
        val prefs = UpdatePreferences(app)
        val update = AppUpdate(UpdateSource.GITHUB, "99.0.0")
        prefs.detected(update.source, update)
        kotlinx.coroutines.runBlocking {
            assertTrue(runBackgroundUpdateCheck(app, update.source) { throw java.io.IOException() })
            assertEquals("network_error", prefs.lastBackgroundResult)
            assertEquals(update, prefs.pending())
            assertFalse(runBackgroundUpdateCheck(app, update.source) { throw IllegalArgumentException() })
            assertEquals("error", prefs.lastBackgroundResult)
            assertFalse(runBackgroundUpdateCheck(app, update.source) { null })
            assertEquals("current", prefs.lastBackgroundResult)
            assertNull(prefs.pending())
            assertFalse(runBackgroundUpdateCheck(app, update.source) {
                prefs.automatic = false
                throw java.io.IOException()
            })
        }
        assertEquals(UPDATE_RETRY_INTERVAL, run {
            prefs.automatic = true
            UpdateNotifications.schedule(app)
            scheduler.getPendingJob(UPDATE_JOB_ID)!!.initialBackoffMillis
        })
    }

    @Test fun staleBackgroundResultDoesNotReplaceDetectedSourceAndCancellationIsRecorded() {
        AppUpdates(app).select(UpdateSource.FDROID)
        val prefs = UpdatePreferences(app)
        val current = AppUpdate(UpdateSource.FDROID, "99.0.0")
        prefs.detected(current.source, current)
        kotlinx.coroutines.runBlocking {
            runBackgroundUpdateCheck(app, UpdateSource.GITHUB) { AppUpdate(it, "99.0.1") }
            assertEquals(current, prefs.pending())
            try {
                runBackgroundUpdateCheck(app, current.source) { throw kotlinx.coroutines.CancellationException() }
                fail("Cancellation must propagate")
            } catch (_: kotlinx.coroutines.CancellationException) { }
        }
        assertEquals("interrupted", prefs.lastBackgroundResult)
    }

}
