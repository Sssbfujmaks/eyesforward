package ai.eyesforward.car

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 중계 서버(WebSocket) 연결. 차량 1대 = 방 1개, 태블릿 = 좌석별 기기.
 * 에뮬레이터에서 호스트(맥북)는 10.0.2.2 로 보인다.
 */
class Relay(
    private val url: String,
    private val onMessage: (JSONObject) -> Unit,
    private val onConnection: (Boolean) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().pingInterval(10, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    private var closed = false

    fun connect() {
        if (closed) return
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("type", "hello").put("role", "car").toString())
                main.post { onConnection(true) }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { JSONObject(text) }.getOrNull()?.let { m -> main.post { onMessage(m) } }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = retry()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = retry()
        })
    }

    private fun retry() {
        main.post { onConnection(false) }
        if (!closed) main.postDelayed({ connect() }, 2000)
    }

    fun send(msg: JSONObject) { ws?.send(msg.toString()) }

    fun close() { closed = true; ws?.close(1000, "bye") }
}
