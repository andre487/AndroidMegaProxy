package net.megaproxy487

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import net.megaproxy487.vpn.PendingSshHostKey
import net.megaproxy487.vpn.SshHostKeyPromptState

/** Only the app's immutable notification PendingIntent may restore a key challenge. */
class SshHostKeyReviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == MainActivity.ACTION_REVIEW_SSH_HOST_KEY) {
            SshHostKeyPromptState.show(PendingSshHostKey(
                profileId = intent.getStringExtra(MainActivity.EXTRA_PROFILE_ID).orEmpty(),
                hop = intent.getStringExtra(MainActivity.EXTRA_HOP).orEmpty(),
                algorithm = intent.getStringExtra(MainActivity.EXTRA_ALGORITHM).orEmpty(),
                fingerprint = intent.getStringExtra(MainActivity.EXTRA_FINGERPRINT).orEmpty(),
                changed = intent.getBooleanExtra(MainActivity.EXTRA_CHANGED, false),
                testOnly = intent.getBooleanExtra(MainActivity.EXTRA_TEST_ONLY, false),
            ))
        }
        startActivity(Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        ))
        finish()
    }
}
