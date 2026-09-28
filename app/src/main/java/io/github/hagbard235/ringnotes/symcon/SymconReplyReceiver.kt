package io.github.hagbard235.ringnotes.symcon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import io.github.hagbard235.ringnotes.symconJobs

/** Handles the option buttons of a clarification notification. */
class SymconReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra(EXTRA_PATH) ?: return
        val optionId = intent.getStringExtra(EXTRA_OPTION_ID) ?: return
        val label = intent.getStringExtra(EXTRA_LABEL) ?: optionId
        context.symconJobs.answer(path, optionId, label)
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_OPTION_ID = "optionId"
        const val EXTRA_LABEL = "label"
        const val EXTRA_NOTIFICATION_ID = "notificationId"
    }
}
