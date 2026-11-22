package com.ereader.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ereader.app.data.local.dao.BookDao
import com.ereader.app.data.remote.calibre.CalibreSyncService
import com.ereader.app.data.repository.NotesSyncRepository
import com.ereader.app.data.repository.ReadPushResult
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import timber.log.Timber

/**
 * The background job that sends pending changes to Calibre.
 * Queued by [SyncScheduler]; Android runs it once the phone is online.
 *
 *  1. Nothing waiting → done.
 *  2. Quick check that the Calibre server answers. Not reachable (e.g. you're on
 *     mobile data away from home) → try again in 15 minutes.
 *  3. Sends notes, deleted notes and reading positions, then Read statuses.
 *  4. Anything that still didn't go through → try again in 15 minutes.
 */
class PendingSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    /** Lets this job use the app's shared objects (it isn't created by Hilt). */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun bookDao(): BookDao
        fun notesSync(): NotesSyncRepository
        fun calibreSync(): CalibreSyncService
        fun scheduler(): SyncScheduler
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val scheduler = deps.scheduler()

        if (!scheduler.hasPending()) return Result.success()

        if (!deps.calibreSync().isReachable()) {
            Timber.d("Auto-sync: Calibre not reachable on this network — retrying in ${SyncScheduler.RETRY_MINUTES} min")
            scheduler.scheduleRetry()
            return Result.success()
        }

        // Notes, deleted notes, reading positions
        val notes = runCatching { deps.notesSync().syncAllPending() }.getOrDefault(0)

        // Read statuses (each book looks up its library's Read column)
        var read = 0
        var readFailed = false
        deps.bookDao().getBooksWithUnsyncedReadStatus().forEach { book ->
            when (deps.notesSync().pushReadStatus(book.id)) {
                ReadPushResult.Synced    -> read++
                is ReadPushResult.Failed -> readFailed = true
                // No Read column in that library: nothing to retry until one is added.
                // (The next library sync sends it once the column exists.)
                else -> Unit
            }
        }
        Timber.d("Auto-sync: sent notes/page for $notes book(s), Read status for $read book(s)")

        // Something still failed (but not just "no Read column") → try again later.
        val notesLeft = deps.bookDao().getBooksWithUnsyncedNotes().isNotEmpty()
        if (notesLeft || readFailed) scheduler.scheduleRetry()
        return Result.success()
    }
}
