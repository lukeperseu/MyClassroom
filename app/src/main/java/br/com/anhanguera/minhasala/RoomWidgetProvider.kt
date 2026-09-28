package br.com.anhanguera.minhasala

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
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

        // Cache keys for room tracking
        const val KEY_LAST_RECORDED_ROOM = "last_recorded_room"
        const val KEY_LAST_RECORDED_DATE = "last_recorded_date"

        const val CHANNEL_ID = "room_changes_alert_channel"
        const val NOTIFICATION_ID = 1007

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

        /**
         * Checks whether the current time is strictly within the 30-minute pre-class window:
         * - Matutino (Class at 09:00): window is 08:30 to 09:00
         * - Noturno (Class at 19:00): window is 18:30 to 19:00
         */
        fun isWithinPreClassWindow(turno: String?): Boolean {
            val calendar = Calendar.getInstance()
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val minute = calendar.get(Calendar.MINUTE)
            val currentMinuteOfDay = hour * 60 + minute

            val cleanTurno = turno?.lowercase(Locale.getDefault()) ?: ""

            val isMorningWindow = currentMinuteOfDay in 510 until 540 // 08:30 <= time < 09:00
            val isNightWindow = currentMinuteOfDay in 1110 until 1140 // 18:30 <= time < 19:00

            return when (cleanTurno) {
                "matutino" -> isMorningWindow
                "noturno" -> isNightWindow
                else -> isMorningWindow || isNightWindow
            }
        }

        /**
         * Triggers high-priority notification with distinctive vibration ONLY when room changed
         */
        fun triggerRoomChangeNotification(
            context: Context,
            newRoom: String,
            location: String,
            discipline: String,
            oldRoom: String
        ) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Ensure channel exists
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Mudança de Sala (Anhanguera)",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alertas com vibração quando houver troca de sala 30 min antes da aula"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 800)
                    setShowBadge(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            // Direct vibration invocation for physical feedback
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    val vibrator = vibratorManager.defaultVibrator
                    vibrator.vibrate(
                        VibrationEffect.createWaveform(
                            longArrayOf(0, 500, 200, 500, 200, 800),
                            -1
                        )
                    )
                } else {
                    @Suppress("DEPRECATION")
                    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(
                            VibrationEffect.createWaveform(
                                longArrayOf(0, 500, 200, 500, 200, 800),
                                -1
                            )
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(longArrayOf(0, 500, 200, 500, 200, 800), -1)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Intent to open app on tap
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val locationText = if (location.isNotBlank()) " ($location)" else ""
            val disciplineText = if (discipline.isNotBlank()) " da matéria '$discipline'" else ""

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.app_icon)
                .setContentTitle("⚠️ ATENÇÃO: Mudança de Sala!")
                .setContentText("Sua aula$disciplineText mudou para a $newRoom$locationText!")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("Atenção! Sua sala foi remanejada:\nAnterior: $oldRoom\n👉 NOVA SALA: $newRoom$locationText\nDisciplina: $discipline")
                )
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setSound(soundUri)
                .setVibrate(longArrayOf(0, 500, 200, 500, 200, 800))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            notificationManager.notify(NOTIFICATION_ID, notification)
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

        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val currentMinuteOfDay = hour * 60 + minute
        val todayDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val urlBuilder = Uri.parse("$baseUrl/api/student-portal/schedule").buildUpon()
                    .appendQueryParameter("course", course)
                    .appendQueryParameter("turno", turno)
                    .appendQueryParameter("modalidade", modalidade)
                    .appendQueryParameter("semester", semester)
                    .appendQueryParameter("clientMinuteOfDay", currentMinuteOfDay.toString())
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
                        val todayScheduledObj = if (!json.isNull("todayScheduledClass")) json.getJSONObject("todayScheduledClass") else null
                        val nextClassObj = if (!json.isNull("nextClass")) json.getJSONObject("nextClass") else null

                        val isServerPreWindow = json.optBoolean("isPreClassWindow", false)
                        val inNotificationWindow = isServerPreWindow || isWithinPreClassWindow(turno)

                        // --- NOTIFICATION & VIBRATION DETECTION ---
                        // Rule: ONLY notify if we are strictly in the 30-minute pre-class window
                        // (08:30-09:00 for matutino, 18:30-19:00 for noturno)
                        if (todayScheduledObj != null) {
                            val currentRoomName = todayScheduledObj.optString("roomName", "").trim()
                            val currentRoomLocation = todayScheduledObj.optString("roomLocation", "").trim()
                            val currentDiscipline = todayScheduledObj.optString("disciplineName", "").trim()

                            val lastRecordedRoom = prefs.getString(KEY_LAST_RECORDED_ROOM, null)
                            val lastRecordedDate = prefs.getString(KEY_LAST_RECORDED_DATE, null)

                            if (inNotificationWindow) {
                                // We ARE in the 30-minute pre-class window!
                                if (lastRecordedDate == todayDateStr &&
                                    !lastRecordedRoom.isNullOrBlank() &&
                                    lastRecordedRoom != currentRoomName
                                ) {
                                    // ROOM CHANGED DURING THE PRE-CLASS WINDOW!
                                    triggerRoomChangeNotification(
                                        context,
                                        newRoom = currentRoomName,
                                        location = currentRoomLocation,
                                        discipline = currentDiscipline,
                                        oldRoom = lastRecordedRoom
                                    )
                                }
                                // Update current known room for today
                                prefs.edit()
                                    .putString(KEY_LAST_RECORDED_ROOM, currentRoomName)
                                    .putString(KEY_LAST_RECORDED_DATE, todayDateStr)
                                    .apply()
                            } else {
                                // OUTSIDE the 30-minute window:
                                // Silently save today's scheduled room so we have a baseline,
                                // but NEVER notify or vibrate outside this window.
                                prefs.edit()
                                    .putString(KEY_LAST_RECORDED_ROOM, currentRoomName)
                                    .putString(KEY_LAST_RECORDED_DATE, todayDateStr)
                                    .apply()
                            }
                        }

                        // --- WIDGET DISPLAY LOGIC ---
                        // Display room when class is active (starts 30 min before class time)
                        if (activeClassObj != null) {
                            val roomName = activeClassObj.optString("roomName", "Sala")
                            val roomLocation = activeClassObj.optString("roomLocation", "")
                            val disciplineName = activeClassObj.optString("disciplineName", "")

                            views.setViewVisibility(R.id.layout_has_class, View.VISIBLE)
                            views.setViewVisibility(R.id.layout_no_class, View.GONE)

                            val badgeText = if (inNotificationWindow) "SALA HOJE (EM BREVE)" else "EM AULA AGORA"
                            views.setTextViewText(R.id.tv_status_badge, badgeText)
                            views.setTextViewText(R.id.tv_room_name, roomName)
                            views.setTextViewText(R.id.tv_room_location, roomLocation)
                            views.setTextViewText(R.id.tv_discipline_name, disciplineName)
                        } else {
                            // NO CLASS NOW: Display Pinterest Icon
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
