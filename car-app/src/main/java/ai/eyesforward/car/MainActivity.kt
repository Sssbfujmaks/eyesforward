package ai.eyesforward.car

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * 앞만봐 차량 앱 (Pleos Connect).
 * - 차량 신호(VehicleSignals) → 규칙 엔진(RuleEngine) → 중계 서버(Relay) → 좌석별 태블릿
 * - 운전자 화면은 assets/car.html (주행 중에는 카드 2장 + 마이크만)
 * - 시나리오 주입 패널: Virtual Console에 2열 벨트 카드가 없어서, 같은 규칙 엔진 함수(onBelt 등)를 직접 호출한다.
 */
class MainActivity : Activity(), RuleEngine.Output {

    private lateinit var web: WebView
    private lateinit var engine: RuleEngine
    private lateinit var relay: Relay
    private lateinit var signals: VehicleSignals
    private lateinit var voice: Voice
    private var pageReady = false
    private var lastState: JSONObject? = null
    private val status = linkedMapOf<String, String>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) { pageReady = true; lastState?.let { render(it) } }
            }
            addJavascriptInterface(Bridge(), "Car")
            loadUrl("file:///android_asset/car.html")
        }
        setContentView(web)

        engine = RuleEngine(this)
        relay = Relay(RELAY_URL, ::onRelayMessage) { connected -> engine.relayConnected = connected; engine.push() }
        signals = VehicleSignals(this, engine) { setStatus("vehicle", it) }
        voice = Voice(
            this,
            fallbackSpeak = { text -> relay.send(JSONObject().put("type", "speak").put("text", text)) },
            onListening = { js("window.onListening && onListening(${it})") },
            onPartial = { js("window.onPartial && onPartial(${JSONObject.quote(it)})") },
            onFinal = { engine.onVoice(it) },
            onStatus = { setStatus(if (it.contains("STT") || it.contains("인식")) "stt" else "tts", it) },
        )

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        relay.connect()
        signals.start()
        voice.start()
        engine.push()
    }

    private fun onRelayMessage(m: JSONObject) {
        when (m.optString("type")) {
            "status" -> engine.onTabletStatus(m)
            "presence" -> engine.onPresence(m.optJSONObject("seats") ?: JSONObject())
            "hello_tablet" -> engine.onTabletHello(m.optString("seat"))
        }
    }

    // ---- RuleEngine.Output ----
    override fun sendToTablet(seat: String, action: String, extra: Map<String, Any?>) {
        val msg = JSONObject().put("type", "cmd").put("seat", seat).put("action", action)
        extra.forEach { (k, v) -> msg.put(k, v) }
        relay.send(msg)
    }

    override fun speak(text: String) {
        voice.speak(text)
        js("window.onSpeak && onSpeak(${JSONObject.quote(text)})")
    }

    override fun event(kind: String, text: String) {
        relay.send(JSONObject().put("type", "event").put("kind", kind).put("text", text))
    }

    override fun render(state: JSONObject) {
        state.put("status", JSONObject(status as Map<*, *>))
        lastState = state
        if (pageReady) js("window.render && render($state)")
    }

    private fun setStatus(key: String, text: String) { status[key] = text; engine.push() }

    private fun js(code: String) = runOnUiThread { if (pageReady) web.evaluateJavascript(code, null) }

    /** car.html 에서 부르는 함수들 */
    inner class Bridge {
        @JavascriptInterface fun belt(seat: String, buckled: Boolean) = runOnUiThread { event("panel", "belt $seat $buckled"); engine.onBelt(seat, buckled, "주입 패널") }
        @JavascriptInterface fun door(seat: String, open: Boolean) = runOnUiThread { event("panel", "door $seat $open"); engine.onDoor(seat, open, "주입 패널") }
        @JavascriptInterface fun occupancy(seat: String, occupied: Boolean) = runOnUiThread { engine.onOccupancy(seat, occupied, "주입 패널") }
        @JavascriptInterface fun gear(g: String) = runOnUiThread { event("panel", "gear $g"); engine.onGear(if (g == "D") RuleEngine.Gear.DRIVE else RuleEngine.Gear.PARK, "주입 패널") }
        @JavascriptInterface fun mode(seat: String, mode: String) = runOnUiThread { engine.setMode(seat, if (mode == "booster") RuleEngine.Mode.BOOSTER else RuleEngine.Mode.CARSEAT) }
        @JavascriptInterface fun say(text: String) = runOnUiThread { engine.onVoice(text) }
        @JavascriptInterface fun mic(): Boolean = voice.listen()
        @JavascriptInterface fun reset() = runOnUiThread { engine.resetDay() }
    }

    override fun onDestroy() {
        signals.stop(); voice.release(); relay.close()
        super.onDestroy()
    }

    companion object {
        /** 에뮬레이터 → 호스트(맥북) 중계 서버 */
        const val RELAY_URL = "ws://10.0.2.2:8765"
    }
}
