package com.ereader.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import androidx.core.app.TaskStackBuilder
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.ereader.app.MainActivity
import com.ereader.app.R
import com.ereader.app.data.local.dao.BookDao
import com.ereader.app.data.local.dao.ReadingDayDao
import com.ereader.app.domain.model.ReadingStreak
import com.ereader.app.ui.reader.ReaderActivity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Home-screen widget: the book you're reading (cover, title, progress) and your
 * reading streak. Tapping it opens the book where you left off; with no book in
 * progress it opens the library.
 *
 * Add it: long-press the home screen → Widgets → OmniTome → Continue Reading.
 * It refreshes when you close a book and when the app starts (no battery-using
 * timer).
 */
class ContinueReadingWidget : AppWidgetProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun bookDao(): BookDao
        fun readingDayDao(): ReadingDayDao
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            try { render(context.applicationContext, manager, ids) }
            catch (e: Exception) { Timber.w(e, "Widget update failed") }
            finally { pending.finish() }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Refreshes every Continue Reading widget on the home screen. */
        fun updateAll(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, ContinueReadingWidget::class.java))
            if (ids.isEmpty()) return
            scope.launch { runCatching { render(app, manager, ids) }.onFailure { Timber.w(it, "Widget update failed") } }
        }

        private suspend fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val deps   = EntryPointAccessors.fromApplication(context, Deps::class.java)
            val book   = deps.bookDao().getMostRecentInProgress()
            val streak = ReadingStreak.from(deps.readingDayDao().observeDays().first())
            val views  = RemoteViews(context.packageName, R.layout.widget_continue_reading)

            val streakText = when {
                streak.current == 0 -> null
                streak.readToday    -> "🔥 ${streak.current}-day streak"
                else                -> "🔥 ${streak.current}-day streak — read today to keep it"
            }

            if (book == null) {
                views.setTextViewText(R.id.widget_title, "Open OmniTome to start a book")
                views.setTextViewText(R.id.widget_author, "")
                views.setProgressBar(R.id.widget_progress, 100, 0, false)
                views.setTextViewText(R.id.widget_detail, streakText ?: "")
                views.setImageViewResource(R.id.widget_cover, R.mipmap.ic_launcher)
                views.setOnClickPendingIntent(R.id.widget_root, openApp(context))
            } else {
                val pct = (book.readingProgress * 100).toInt().coerceIn(0, 100)
                views.setTextViewText(R.id.widget_title, book.title)
                views.setTextViewText(R.id.widget_author, book.author)
                views.setProgressBar(R.id.widget_progress, 100, pct, false)
                views.setTextViewText(R.id.widget_detail, listOfNotNull("$pct% read", streakText).joinToString(" · "))
                val cover = loadCover(context, book.coverUrl)
                if (cover != null) views.setImageViewBitmap(R.id.widget_cover, cover)
                else views.setImageViewResource(R.id.widget_cover, R.mipmap.ic_launcher)
                views.setOnClickPendingIntent(R.id.widget_root, openBook(context, book.id, book.title))
            }
            manager.updateAppWidget(ids, views)
        }

        /** Cover through the app's image loader (Calibre login + cache, so it works offline too). */
        private suspend fun loadCover(context: Context, url: String?): Bitmap? {
            if (url.isNullOrBlank()) return null
            val result = context.imageLoader.execute(
                ImageRequest.Builder(context).data(url).size(168, 252).allowHardware(false).build()
            )
            return (result as? SuccessResult)?.drawable?.toBitmap(168, 252)
        }

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        /** Opens the book; Back returns to the library. */
        private fun openBook(context: Context, bookId: String, title: String): PendingIntent =
            TaskStackBuilder.create(context)
                .addNextIntent(Intent(context, MainActivity::class.java))
                .addNextIntent(Intent(context, ReaderActivity::class.java)
                    .putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId)
                    .putExtra(ReaderActivity.EXTRA_BOOK_TITLE, title))
                .getPendingIntent(1, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)!!
    }
}
