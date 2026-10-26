package dev.solon.bucklerhome

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast

/**
 * Receives PackageInstaller session results. When Android needs the user to confirm an
 * install (the normal case for sideloaded updates) the confirmation Intent arrives here
 * and must be launched explicitly, otherwise the update stalls silently.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> launchConfirmation(context, intent)
            PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "Update installed")
            else -> {
                Log.w(TAG, "Install failed: status=$status message=$message")
                Toast.makeText(context, "Update failed: ${message ?: "status $status"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun launchConfirmation(context: Context, intent: Intent) {
        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(confirm) }
            .onFailure { Log.w(TAG, "Could not show install confirmation", it) }
    }

    companion object {
        const val ACTION = "dev.solon.bucklerhome.INSTALL_RESULT"
        private const val TAG = "InstallResult"
    }
}
