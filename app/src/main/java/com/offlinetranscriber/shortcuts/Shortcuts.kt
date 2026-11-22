package com.offlinetranscriber.shortcuts

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import com.offlinetranscriber.R
import com.offlinetranscriber.ui.MainActivity

private fun routeIntent(context: Context, route: String) = Intent(context, MainActivity::class.java)
    .putExtra(MainActivity.EXTRA_ROUTE, route)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

private fun routePending(context: Context, route: String, req: Int) =
    PendingIntent.getActivity(context, req, routeIntent(context, route), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

/** Quick Settings tile: one tap opens VoxNote and starts recording. */
class RecordTileService : TileService() {
    override fun onClick() {
        super.onClick()
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(routePending(this, "record?autostart=true", 10))
        else @Suppress("DEPRECATION") startActivityAndCollapse(routeIntent(this, "record?autostart=true"))
    }
}

/** Home-screen widget with "Record" and "Transcribe a file" buttons. */
class RecordWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_record)
            views.setOnClickPendingIntent(R.id.widget_record, routePending(context, "record?autostart=true", 20))
            views.setOnClickPendingIntent(R.id.widget_transcribe, routePending(context, "transcribe", 21))
            manager.updateAppWidget(id, views)
        }
    }
}
