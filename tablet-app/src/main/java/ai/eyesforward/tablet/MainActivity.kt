package ai.eyesforward.tablet

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONObject

/**
 * 앞만봐 태블릿 앱.
 * 중계 서버가 제공하는 좌석 화면(tablet.html)을 전체 화면으로 띄우고,
 * 화면 안의 YouTube IFrame Player를 차량 명령에 따라 재생·정지·잠금한다.
 * 배터리 값은 네이티브(BatteryManager)로 읽어 화면 스크립트에 넘긴다.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private val prefs by lazy { getSharedPreferences("eyesforward", MODE_PRIVATE) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false // 차량 명령만으로 재생
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            addJavascriptInterface(Bridge(), "Tablet")
            // 왼쪽 위 길게 누르면 좌석·서버 설정
            setOnLongClickListener { showSetup(); true }
        }
        setContentView(web)
        hideSystemBars()

        if (prefs.contains("seat")) load() else showSetup()
    }

    private fun load() {
        val server = prefs.getString("server", DEFAULT_SERVER)!!
        val seat = prefs.getString("seat", "left")!!
        web.loadUrl("http://$server/tablet.html?seat=$seat")
    }

    /** 좌석 연결: 이 태블릿을 2열 왼쪽(첫째) / 오른쪽(둘째) 중 어디에 둘지 고른다. */
    private fun showSetup() {
        val input = EditText(this).apply { setText(prefs.getString("server", DEFAULT_SERVER)) }
        val box = LinearLayout(this).apply { setPadding(48, 16, 48, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle("이 태블릿의 좌석을 골라 주세요 (중계 서버 주소)")
            .setView(box)
            .setPositiveButton("첫째 · 2열 왼쪽") { _, _ -> save(input.text.toString(), "left") }
            .setNegativeButton("둘째 · 2열 오른쪽") { _, _ -> save(input.text.toString(), "right") }
            .setCancelable(prefs.contains("seat"))
            .show()
    }

    private fun save(server: String, seat: String) {
        prefs.edit().putString("server", server.trim()).putString("seat", seat).apply()
        load()
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    inner class Bridge {
        @JavascriptInterface
        fun battery(): String {
            val i: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            return JSONObject().put("level", if (level >= 0) level * 100 / scale else null).put("charging", charging).toString()
        }
    }

    companion object {
        const val DEFAULT_SERVER = "192.168.219.112:8765"
    }
}
