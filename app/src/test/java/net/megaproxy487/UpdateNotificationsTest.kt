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
}
