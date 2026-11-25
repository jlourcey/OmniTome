package com.ereader.app.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ereader.app.data.local.dao.BookDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Makes sure anything waiting to go to Calibre — notes, deleted notes, the page
 * you're on, Read status — gets there automatically, without you having to open
 * the app or pull to refresh.
 *
 * How it works:
 *  1. Whenever something changes that Calibre needs to hear about, [schedule]
 *     queues a background job ([PendingSyncWorker]). Android holds the job until
 *     the phone has an internet connection, then runs it — even if OmniTome is closed.
 *  2. Your Calibre server is only reachable at home, so having *some* connection
 *     isn't enough (mobile data won't reach it). If Calibre can't be reached, the
 *     job tries again every [RETRY_MINUTES] minutes until it gets through.
 *  3. While OmniTome is running, it also watches for the phone switching networks
 *     (e.g. joining your home Wi-Fi) and tries straight away instead of waiting
 *     for the next retry.
 *
 * Nothing is lost if a try fails: changes stay "pending" in the database until
 * Calibre confirms them.
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookDao: BookDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workManager get() = WorkManager.getInstance(context)

    companion object {
        /** The main job — runs as soon as there's a connection. */
        const val WORK_NOW   = "omnitome-pending-sync"
        /** The follow-up job — runs [RETRY_MINUTES] after a failed try. */
        const val WORK_RETRY = "omnitome-pending-sync-retry"
        const val RETRY_MINUTES = 15L
    }

    private val needsInternet = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * Queues a sync for the next time the phone is online.
     * Safe to call often — if one is already queued, nothing new is added.
     */
    fun schedule() {
        val request = OneTimeWorkRequestBuilder<PendingSyncWorker>()
            .setConstraints(needsInternet)
            .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_MINUTES, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.KEEP, request)
    }

    /** Calibre couldn't be reached — try again in [RETRY_MINUTES] minutes. */
    fun scheduleRetry() {
        val request = OneTimeWorkRequestBuilder<PendingSyncWorker>()
            .setConstraints(needsInternet)
            .setInitialDelay(RETRY_MINUTES, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(WORK_RETRY, ExistingWorkPolicy.REPLACE, request)
    }

    /** The network just changed — try right now (if there's anything to send). */
    private fun syncNowIfPending() {
        scope.launch {
            if (!hasPending()) return@launch
            Timber.d("Network changed and changes are waiting — syncing to Calibre now")
            workManager.cancelUniqueWork(WORK_RETRY)   // no need to wait for the timer
            val request = OneTimeWorkRequestBuilder<PendingSyncWorker>()
                .setConstraints(needsInternet)
                .build()
            workManager.enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.REPLACE, request)
        }
    }

    /** True if any Calibre book has notes, a page or a Read status not yet sent. */
    suspend fun hasPending(): Boolean =
        bookDao.getBooksWithUnsyncedNotes().isNotEmpty() ||
        bookDao.getBooksWithUnsyncedReadStatus().isNotEmpty()

    /**
     * Called once when the app starts: queues a sync if anything is waiting,
     * and starts watching for network changes while the app is running.
     */
    fun start() {
        scope.launch { if (hasPending()) schedule() }

        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                private var lastNetwork: Network? = null

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    // Only act once the connection actually works (VALIDATED), and only
                    // once per new network — this callback fires often for the same one.
                    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
                    if (network == lastNetwork) return
                    lastNetwork = network
                    syncNowIfPending()
                }

                override fun onLost(network: Network) {
                    if (network == lastNetwork) lastNetwork = null
                }
            })
        }.onFailure { Timber.w(it, "Couldn't watch for network changes") }
    }
}
