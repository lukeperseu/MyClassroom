package br.com.anhanguera.minhasala

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    // Launcher for notification permission (Android 13+)
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            // Permission result handled
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)

        createNotificationChannel()
        requestNotificationPermission()

        val prefs = getSharedPreferences(RoomWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
        val baseUrl = prefs.getString(RoomWidgetProvider.KEY_API_URL, RoomWidgetProvider.DEFAULT_BASE_URL)
            ?: RoomWidgetProvider.DEFAULT_BASE_URL

        // Schedule periodic background refresh for widget (runs every 15 mins)
        val workRequest = PeriodicWorkRequestBuilder<RoomUpdateWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "RoomWidgetUpdateWork",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        // Bridge to capture student profile from Web to Native Android Widget
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidWidgetBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE

                // Inject bridge listener on localstorage change
                val jsHook = """
                    (function() {
                        function syncToAndroid() {
                            try {
                                var raw = localStorage.getItem('anhanguera_student_profile_v1');
                                if (raw && window.AndroidWidgetBridge) {
                                    window.AndroidWidgetBridge.onProfileSaved(raw);
                                }
                            } catch(e) {}
                        }
                        syncToAndroid();
                        window.addEventListener('storage', syncToAndroid);
                    })();
                """.trimIndent()
                webView.evaluateJavascript(jsHook, null)
            }
        }

        webView.webChromeClient = WebChromeClient()

        // Load isolated student portal
        webView.loadUrl("$baseUrl/aluno")
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                RoomWidgetProvider.CHANNEL_ID,
                "Mudança de Sala (Anhanguera)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificações vibratórias 30 minutos antes da aula se houver alteração de sala"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 800)
                setShowBadge(true)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    inner class WebAppInterface(private val context: Context) {
        @JavascriptInterface
        fun onProfileSaved(jsonString: String) {
            try {
                val obj = org.json.JSONObject(jsonString)
                val course = obj.optString("course", "")
                val turno = obj.optString("turno", "")
                val modalidade = obj.optString("modalidade", "")
                val semester = obj.optString("semester", "")

                val prefs = context.getSharedPreferences(RoomWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(RoomWidgetProvider.KEY_COURSE, course)
                    .putString(RoomWidgetProvider.KEY_TURNO, turno)
                    .putString(RoomWidgetProvider.KEY_MODALIDADE, modalidade)
                    .putString(RoomWidgetProvider.KEY_SEMESTER, semester)
                    .apply()

                // Immediately refresh all home screen widgets
                RoomWidgetProvider.updateAllWidgets(context)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
