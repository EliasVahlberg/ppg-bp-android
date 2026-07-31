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

    /**
     * Bundles under [root] that finished recording but never reached the server.
     *
     * A bundle qualifies when it has a manifest (so recording actually finalised
     * -- a dir without one is either still being written or was abandoned
     * mid-recording, and has nothing coherent to upload) and has no completion
     * marker (written only after open + all files + complete all succeed, so its
     * absence means the upload did not finish).
     *
     * Pure and separate from [enqueueAllUnsynced] so this decision can be tested
     * without a WorkManager or an Android context. It is the part that has to be
     * right: a false negative means a recording is silently never uploaded, which
     * is exactly what stranded three sessions from the 2026-07-27 visit.
     */
    fun findUnsynced(root: File): List<File> =
        root.listFiles()?.filter {
            it.isDirectory &&
                File(it, "manifest.json").isFile &&
                !File(it, SyncWorker.MARKER).exists()
        }?.sortedBy { it.name } ?: emptyList()

    /** Enqueue every completed-but-unsynced bundle under [root]. Returns count enqueued. */
    fun enqueueAllUnsynced(context: Context, root: File): Int {
        val dirs = findUnsynced(root)
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
