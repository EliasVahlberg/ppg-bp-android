/*
 * Helpers to enqueue SyncWorker jobs. One unique job per bundle dir
 * (ExistingWorkPolicy.KEEP), gated on network connectivity with exponential
 * backoff so unattended offline sessions upload when connectivity returns.
 */

package com.polarppgbp.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.TimeUnit

object SyncScheduler {

    private val networkConstraint =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Enqueue upload of a single bundle dir. */
    fun enqueue(context: Context, bundleDir: File) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SyncWorker.KEY_BUNDLE_DIR to bundleDir.absolutePath))
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("sync_${bundleDir.name}", ExistingWorkPolicy.KEEP, req)
    }

    /** Enqueue every completed-but-unsynced bundle under [root]. Returns count enqueued. */
    fun enqueueAllUnsynced(context: Context, root: File): Int {
        val dirs = root.listFiles()?.filter {
            it.isDirectory &&
                File(it, "manifest.json").isFile &&
                !File(it, SyncWorker.MARKER).exists()
        } ?: emptyList()
        dirs.forEach { enqueue(context, it) }
        return dirs.size
    }

    /**
     * Enqueue an upload of the local cuff-readings store. REPLACE so the latest
     * (most complete) store supersedes any pending upload. Idempotent server-side.
     */
    fun enqueueCuff(context: Context) {
        val req = OneTimeWorkRequestBuilder<CuffSyncWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("sync_cuff", ExistingWorkPolicy.REPLACE, req)
    }
}
