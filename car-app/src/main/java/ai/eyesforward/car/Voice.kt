package ai.eyesforward.car

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

/**
 * Pleos TextToSpeech / SpeechToText SDK (ON_DEVICE, ko-KR).
 * ON_DEVICE 모드는 앱 등록(Client ID/Secret) 없이 쓸 수 있다.
 * 차량 음성 엔진을 쓸 수 없는 환경(일부 에뮬레이터)에서는 fallbackSpeak 로 대신 말한다.
 */
class Voice(
    context: Context,
    private val fallbackSpeak: (String) -> Unit,
    private val onListening: (Boolean) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val tts = runCatching { TextToSpeech(context.applicationContext, TtsMode.ON_DEVICE) }.getOrNull()
    private val stt = runCatching { SpeechToText(context.applicationContext, SttMode.ON_DEVICE) }.getOrNull()
    var ttsReady = false; private set
    var sttReady = false; private set

    fun start() {
        tts?.let { t ->
            runCatching {
                t.initialize(object : TtsInit {
                    override fun onSuccess() {
                        runCatching { t.setLocale(Locale.KOREA) }
                        ttsReady = true; main.post { onStatus("Pleos TTS 준비됨 (ON_DEVICE, ko-KR)") }
                    }
                    override fun onFailure(e: TtsError) { main.post { onStatus("Pleos TTS 사용 불가: $e → 대체 음성 사용") } }
                })
                t.addEventListener(object : EventListener {
                    override fun onStart() {}
                    override fun onReady() {}
                    override fun onDone() {}
                    override fun onStop() {}
                    override fun onUpdatedRms(rms: Double) {}
                    override fun onError(e: TtsError) { Log.w(TAG, "tts error $e") }
                })
            }.onFailure { onStatus("Pleos TTS 초기화 실패: ${it.message}") }
        }
        stt?.let { s ->
            runCatching {
                s.initialize(object : SttInit {
                    override fun onSuccess() {
                        runCatching { s.setLocale(Locale.KOREA) }
                        sttReady = true; main.post { onStatus("Pleos STT 준비됨 (ON_DEVICE, ko-KR)") }
                    }
                    override fun onFailure(e: SttError) { main.post { onStatus("Pleos STT 사용 불가: $e") } }
                })
                s.addListener(object : ResultListener {
                    override fun onUpdated(text: String, completed: Boolean) {
                        main.post { if (completed) { onListening(false); if (text.isNotBlank()) onFinal(text) } else onPartial(text) }
                    }
                    override fun onUpdatedEpdData(on: Long, off: Long) {}
                    override fun onStartedRecognition() { main.post { onListening(true) } }
                    override fun onEndedRecognition() { main.post { onListening(false) } }
                    override fun onError(e: SttError) { main.post { onListening(false); onStatus("음성 인식 오류: $e") } }
                    override fun onReady() {}
                    override fun onUpdatedRms(rms: Double) {}
                })
            }.onFailure { onStatus("Pleos STT 초기화 실패: ${it.message}") }
        }
    }

    fun speak(text: String) {
        val t = tts
        if (ttsReady && t != null && runCatching { t.speak(text, true) }.isSuccess) return
        fallbackSpeak(text)
    }

    /** 마이크 버튼: 차량 마이크로 인식 시작. */
    fun listen(): Boolean {
        val s = stt
        if (!sttReady || s == null) return false
        return runCatching { s.request() }.isSuccess
    }

    fun release() {
        runCatching { tts?.release() }
        runCatching { stt?.release() }
    }

    companion object { const val TAG = "EyesForward" }
}
