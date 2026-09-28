package br.com.anhanguera.minhasala

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RoomWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH_WIDGET = "br.com.anhanguera.minhasala.ACTION_REFRESH_WIDGET"
        const val PREFS_NAME = "anhanguera_student_prefs"
        const val KEY_COURSE = "course"
        const val KEY_MODALIDADE = "modalidade"
        const val KEY_TURNO = "turno"
        const val KEY_SEMESTER = "semester"
        const val KEY_API_URL = "api_url"

        // Default deployed API endpoint
        const val DEFAULT_BASE_URL = "https://ais-dev-sbtair6avqepdyav2d5553-644130323775.us-west2.run.app"

        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        fun updateAllWidgets(context: Context) {
            val intent = Intent(context, RoomWidgetProvider::class.java).apply {
                action = ACTION_REFRESH_WIDGET
            }
            context.sendBroadcast(intent)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            refreshWidgetData(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH_WIDGET || intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, RoomWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            for (id in appWidgetIds) {
                refreshWidgetData(context, appWidgetManager, id)
            }
        }
    }

    private fun refreshWidgetData(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.room_widget_layout)

        // Setup click on widget to open MainActivity
        val openAppIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            appWidgetId,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val course = prefs.getString(KEY_COURSE, "Psicologia") ?: "Psicologia"
        val turno = prefs.getString(KEY_TURNO, "matutino") ?: "matutino"
        val modalidade = prefs.getString(KEY_MODALIDADE, "") ?: ""
        val semester = prefs.getString(KEY_SEMESTER, "4º") ?: "4º"
        val baseUrl = prefs.getString(KEY_API_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val urlBuilder = Uri.parse("$baseUrl/api/student-portal/schedule").buildUpon()
                    .appendQueryParameter("course", course)
                    .appendQueryParameter("turno", turno)
                    .appendQueryParameter("modalidade", modalidade)
                    .appendQueryParameter("semester", semester)
                    .build()

                val request = Request.Builder()
                    .url(urlBuilder.toString())
                    .header("User-Agent", "AnhangueraRoomWidget-Android")
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && responseBody != null) {
                        val json = JSONObject(responseBody)
                        val activeClassObj = if (!json.isNull("activeClass")) json.getJSONObject("activeClass") else null
                        val nextClassObj = if (!json.isNull("nextClass")) json.getJSONObject("nextClass") else null

                        if (activeClassObj != null) {
                            // 1. CLASS IS ON NOW: Display big room
                            val roomName = activeClassObj.optString("roomName", "Sala")
                            val roomLocation = activeClassObj.optString("roomLocation", "")
                            val disciplineName = activeClassObj.optString("disciplineName", "")

                            views.setViewVisibility(R.id.layout_has_class, View.VISIBLE)
                            views.setViewVisibility(R.id.layout_no_class, View.GONE)

                            views.setTextViewText(R.id.tv_room_name, roomName)
                            views.setTextViewText(R.id.tv_room_location, roomLocation)
                            views.setTextViewText(R.id.tv_discipline_name, disciplineName)
                        } else {
                            // 2. NO CLASS NOW: Display Pinterest Icon
                            views.setViewVisibility(R.id.layout_has_class, View.GONE)
                            views.setViewVisibility(R.id.layout_no_class, View.VISIBLE)

                            views.setTextViewText(R.id.tv_no_class_title, "Sem aula agora")

                            if (nextClassObj != null) {
                                val nextRoom = nextClassObj.optString("roomName", "")
                                val nextDay = nextClassObj.optString("dayOfWeek", "").take(3)
                                views.setTextViewText(R.id.tv_next_class_preview, "Próx: $nextDay ($nextRoom)")
                            } else {
                                views.setTextViewText(R.id.tv_next_class_preview, course)
                            }
                        }
                    } else {
                        // Offline or API error fallback: show icon
                        views.setViewVisibility(R.id.layout_has_class, View.GONE)
                        views.setViewVisibility(R.id.layout_no_class, View.VISIBLE)
                        views.setTextViewText(R.id.tv_no_class_title, course)
                    }

                    appWidgetManager.updateAppWidget(appWidgetId, views)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
