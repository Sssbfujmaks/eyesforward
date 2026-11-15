package ai.eyesforward.car

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 앞만봐 규칙 엔진: 좌석 × 신호 × 모드(부스터/카시트) → 태블릿 명령 + 음성 안내.
 *
 * - 부스터 좌석: 차량 벨트 = 재생 버튼. 매면 재생 + 스티커, 풀면 잠금 + 운전자 음성 알림.
 * - 카시트 좌석: 차량 벨트로 아이 상태를 알 수 없으므로, 뒷문이 닫히면 부모에게 묻고
 *   부모 음성("응, 틀어줘")으로 시작한다.
 * - 부모 음성: 좌석 × 동작(다음 편·멈춰·틀어줘·소리·남은 시간·응).
 */
class RuleEngine(private val out: Output) {

    interface Output {
        fun sendToTablet(seat: String, action: String, extra: Map<String, Any?> = emptyMap())
        fun speak(text: String)
        fun render(state: JSONObject)
        fun event(kind: String, text: String) {}
    }

    enum class Mode { BOOSTER, CARSEAT }
    enum class Gear { PARK, DRIVE }

    class Seat(val id: String, val name: String, val side: String, var mode: Mode) {
        var belt: Boolean? = null
        var occupied: Boolean? = null
        var doorOpen = false
        var connected = false
        var battery: Int? = null
        var charging = false
        var title = ""
        var tabletState = "wait"
        var stickers = 0
        var batteryWarned = false
    }

    companion object {
        /** 내비 남은 시간 (시연값: 1시간 52분) */
        const val REMAINING_MIN = 112
        /** 영상 재생 중 태블릿 배터리 소모 추정치 (%/시간). 실제로는 기기별 소모 기록으로 보정 */
        const val DRAIN_PER_HOUR = 30
    }

    /** 도착까지 필요한 배터리 % (남은 시간 × 소모 속도 + 여유 5%) */
    fun batteryNeeded(): Int = Math.ceil(REMAINING_MIN / 60.0 * DRAIN_PER_HOUR).toInt() + 5

    val seats = linkedMapOf(
        "left" to Seat("left", "첫째", "2열 왼쪽", Mode.BOOSTER),
        "right" to Seat("right", "둘째", "2열 오른쪽", Mode.CARSEAT),
    )
    var gear = Gear.PARK
        private set
    private var autoCount = 0
    private var voiceCount = 0
    private var selfBuckled = 0
    private var pendingConfirm: String? = null // 카시트 시작 확인을 기다리는 좌석
    private var lastSpeech = ""
    private var lastHeard = ""
    private val events = ArrayDeque<Pair<String, String>>()
    var relayConnected = false
    var signalSource = mutableMapOf<String, String>() // 어떤 신호가 실제 SDK에서 왔는지 표시

    // ---------- 차량 신호 ----------

    fun onBelt(seatId: String, buckled: Boolean, source: String) {
        val s = seats[seatId] ?: return
        val first = s.belt == null
        if (s.belt == buckled) return
        s.belt = buckled
        signalSource["belt_$seatId"] = source
        if (s.mode != Mode.BOOSTER) { log("${s.name} 벨트 신호 (카시트 좌석이라 무시)"); push(); return }

        if (buckled) {
            selfBuckled++; autoCount++; s.stickers++
            out.sendToTablet(seatId, "play", mapOf("sticker" to true))
            log("${s.name}가 스스로 벨트를 맸어요 → 재생 · 스티커 +1")
            checkBattery(s)
        } else if (first) {
            out.sendToTablet(seatId, "wait")
            log("${s.name} 벨트 대기 중")
        } else {
            autoCount++
            out.sendToTablet(seatId, "lock")
            if (gear == Gear.DRIVE) {
                log("${s.name} 벨트 해제 → ${s.name} 태블릿만 정지·잠금")
                say("${s.name}가 벨트를 풀었어요. 다시 매면 이어서 나와요.")
            } else {
                log("${s.name} 벨트 해제 (정차 중 하차) → 잠금, 다시 매야 재생")
                out.event("getting_out", seatId)
            }
        }
        push()
    }

    fun onOccupancy(seatId: String, occupied: Boolean, source: String) {
        val s = seats[seatId] ?: return
        if (s.occupied == occupied) return
        s.occupied = occupied
        signalSource["occ_$seatId"] = source
        log("${s.name} 좌석 ${if (occupied) "착석" else "비어 있음"}")
        // 부스터 좌석에서 주행 중 일어나면 벨트를 푼 것과 같게 처리
        if (!occupied && s.mode == Mode.BOOSTER && gear == Gear.DRIVE && s.tabletState == "play") {
            autoCount++
            out.sendToTablet(seatId, "lock")
            say("${s.name}가 자리에서 일어났어요.")
        }
        push()
    }

    fun onDoor(seatId: String, open: Boolean, source: String) {
        val s = seats[seatId] ?: return
        if (s.doorOpen == open) return
        s.doorOpen = open
        signalSource["door_$seatId"] = source
        out.event(if (open) "door_open" else "door_close", seatId)
        if (s.mode == Mode.BOOSTER) {
            // 부스터: 아이가 내렸는지는 벨트로 판단한다.
            // 문이 열려도 벨트를 맨 채면 아이는 그대로 앉아 있으므로 계속 재생하고,
            // 문이 닫혀도 벨트를 다시 매기 전에는 재생하지 않는다 (아이가 내렸을 수 있음).
            if (open) log("${s.side} 문 열림 · ${if (s.belt == true) "벨트 매진 상태라 계속 재생" else "벨트 풀림 상태 유지"}")
            else log("${s.side} 문 닫힘 · ${if (s.belt == true) "계속 재생" else "벨트를 매야 재생"}")
        } else if (open) {
            // 카시트: 차량 벨트로 아이 상태를 알 수 없으므로 문이 열리면 멈춘다
            autoCount++
            out.sendToTablet(seatId, "pause", mapOf("text" to "문이 열렸어요. 내릴 때는 조심!"))
            log("${s.side} 문 열림 → ${s.name} 태블릿 일시정지")
        } else {
            log("${s.side} 문 닫힘")
            pendingConfirm = seatId
            out.sendToTablet(seatId, "wait", mapOf("text" to "엄마 아빠가 확인하면 시작해요."))
            say("${s.name} 카시트 벨트 채웠나요?")
        }
        push()
    }

    fun onGear(g: Gear, source: String) {
        if (gear == g) return
        gear = g
        signalSource["gear"] = source
        out.event(if (g == Gear.DRIVE) "gear_d" else "gear_p", "")
        if (g == Gear.DRIVE) {
            log("기어 D → 주행 모드 (운전자 화면은 카드와 음성만)")
        } else {
            log("기어 P → 정차 모드 (설정 열림)")
            seats.values.filter { it.tabletState == "play" && (it.battery ?: 100) < 30 }.forEach {
                out.sendToTablet(it.id, "say", mapOf("text" to "잠깐 쉬어 가요. 충전기를 꽂아 주세요."))
            }
        }
        push()
    }

    /** 출발 전(정차 중) 배터리 × 남은 시간 비교 → 부족하면 차가 먼저 충전 안내 */
    private fun checkBattery(s: Seat) {
        val b = s.battery ?: return
        if (gear != Gear.PARK || s.charging || s.batteryWarned) return
        val need = batteryNeeded()
        if (b < need) {
            s.batteryWarned = true
            autoCount++
            log("${s.name} 태블릿 ${b}% < 도착까지 필요 ${need}% → 충전 안내")
            say("${s.name} 태블릿이 도착 전에 꺼질 수 있어요. 충전해 주세요.")
            out.event("battery_low", "${s.id}:$b:$need")
        }
    }

    // ---------- 태블릿 상태 ----------

    fun onTabletStatus(m: JSONObject) {
        val s = seats[m.optString("seat")] ?: return
        s.connected = true
        if (!m.isNull("battery")) s.battery = m.optInt("battery")
        val wasCharging = s.charging
        s.charging = m.optBoolean("charging")
        if (s.charging && !wasCharging && s.batteryWarned) {
            s.batteryWarned = false
            log("${s.name} 태블릿 충전 시작")
            say("${s.name} 태블릿 충전을 시작했어요. 도착까지 충분해요.")
            out.event("battery_charging", s.id)
        }
        s.title = m.optString("title")
        s.tabletState = m.optString("state", s.tabletState)
        if (m.has("stickers")) s.stickers = m.optInt("stickers")
        push()
    }

    fun onPresence(seatsJson: JSONObject) {
        seats.values.forEach { it.connected = seatsJson.optBoolean(it.id) }
        push()
    }

    fun onTabletHello(seatId: String) {
        val s = seats[seatId] ?: return
        log("${s.name} 태블릿 연결 (${s.side})")
        // 현재 상태에 맞춰 화면을 맞춘다
        when {
            s.mode == Mode.BOOSTER && s.belt == true -> out.sendToTablet(seatId, "play")
            else -> out.sendToTablet(seatId, "wait")
        }
        push()
    }

    // ---------- 설정 (정차 중) ----------

    fun setMode(seatId: String, mode: Mode) {
        val s = seats[seatId] ?: return
        if (gear == Gear.DRIVE) { say("설정은 정차 중에만 바꿀 수 있어요."); return }
        s.mode = mode
        log("${s.name} 좌석 → ${if (mode == Mode.BOOSTER) "부스터 (벨트로 동작)" else "카시트 (뒷문 + 부모 음성)"}")
        push()
    }

    fun resetDay() {
        autoCount = 0; voiceCount = 0; selfBuckled = 0; events.clear()
        seats.values.forEach { it.stickers = 0; it.batteryWarned = false; out.sendToTablet(it.id, "reset_stickers") }
        push()
    }

    // ---------- 부모 음성 ----------

    fun onVoice(text: String) {
        lastHeard = text
        val cmd = VoiceParser.parse(text)
        val target = cmd.seat ?: pendingConfirm ?: "all"
        val who = when (target) { "left" -> "첫째"; "right" -> "둘째"; else -> "두 태블릿" }
        val ok = when (cmd.action) {
            VoiceParser.Action.NEXT -> { out.sendToTablet(target, "next"); say("$who, 다음 편을 틀었어요."); true }
            VoiceParser.Action.PAUSE -> { out.sendToTablet(target, "pause", mapOf("text" to "엄마 아빠가 잠깐 멈췄어요.")); say("$who 멈췄어요."); true }
            VoiceParser.Action.PLAY, VoiceParser.Action.YES -> startByVoice(target, who)
            VoiceParser.Action.VOLUME_DOWN -> { out.sendToTablet(target, "volume", mapOf("delta" to -25)); say("$who 소리를 줄였어요."); true }
            VoiceParser.Action.VOLUME_UP -> { out.sendToTablet(target, "volume", mapOf("delta" to 25)); say("$who 소리를 키웠어요."); true }
            VoiceParser.Action.REMAINING -> { say("목적지까지 ${REMAINING_MIN / 60}시간 ${REMAINING_MIN % 60}분 남았어요. 마지막 편까지 충분해요."); true }
            VoiceParser.Action.BATTERY -> { sayBattery(cmd.seat); true }
            VoiceParser.Action.NO -> { pendingConfirm = null; say("알겠어요."); true }
            VoiceParser.Action.UNKNOWN -> { say("다시 한 번 말씀해 주세요."); false }
        }
        if (ok) { voiceCount++; log("음성 \"$text\" → ${cmd.action.label} ($who)"); out.event("voice_" + cmd.action.name.lowercase(), text) }
        push()
    }

    /** "몇 프로 필요해?" → 지금 배터리와 도착까지 필요한 양을 답한다 */
    private fun sayBattery(seatId: String?) {
        val need = batteryNeeded()
        val list = when (seatId) {
            null -> seats.values.filter { it.batteryWarned }.ifEmpty { seats.values.filter { it.connected } }
            "all" -> seats.values.filter { it.connected }
            else -> listOfNotNull(seats[seatId])
        }.filter { it.battery != null }
        if (list.isEmpty()) { say("연결된 태블릿이 없어요."); return }
        say(list.joinToString(" ") { s ->
            val b = s.battery!!
            val base = "${s.name} 태블릿은 지금 ${b}%예요. 도착까지 ${need}%가 필요해요."
            when {
                b >= need -> "${s.name} 태블릿은 지금 ${b}%예요. 도착까지 ${need}%면 되니까 충분해요."
                s.charging -> "$base 지금 충전 중이에요."
                else -> base
            }
        })
    }

    private fun startByVoice(target: String, who: String): Boolean {
        val ids = if (target == "all") seats.keys.toList() else listOf(target)
        val blocked = ids.mapNotNull { seats[it] }.filter { it.mode == Mode.BOOSTER && it.belt != true }
        ids.mapNotNull { seats[it] }.filter { it !in blocked }.forEach { out.sendToTablet(it.id, "play") }
        pendingConfirm = null
        if (blocked.isNotEmpty()) say("${blocked.joinToString { it.name }}는 벨트를 매면 바로 나와요.")
        else say("$who 틀었어요.")
        return true
    }

    // ---------- 출력 ----------

    private fun say(text: String) { lastSpeech = text; out.speak(text) }

    private fun log(text: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
        events.addFirst(t to text)
        while (events.size > 6) events.removeLast()
    }

    fun push() {
        val o = JSONObject()
        o.put("gear", if (gear == Gear.DRIVE) "D" else "P")
        o.put("relay", relayConnected)
        o.put("auto", autoCount); o.put("voice", voiceCount); o.put("selfBuckled", selfBuckled)
        o.put("speech", lastSpeech); o.put("heard", lastHeard)
        o.put("pending", pendingConfirm ?: JSONObject.NULL)
        o.put("sources", JSONObject(signalSource as Map<*, *>))
        val arr = JSONArray()
        seats.values.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id); put("name", s.name); put("side", s.side)
                put("mode", if (s.mode == Mode.BOOSTER) "booster" else "carseat")
                put("belt", s.belt ?: JSONObject.NULL); put("occupied", s.occupied ?: JSONObject.NULL)
                put("door", s.doorOpen); put("connected", s.connected)
                put("battery", s.battery ?: JSONObject.NULL); put("charging", s.charging)
                put("title", s.title); put("state", s.tabletState); put("stickers", s.stickers)
                put("need", batteryNeeded())
            })
        }
        o.put("seats", arr)
        o.put("events", JSONArray(events.map { JSONObject().put("t", it.first).put("text", it.second) }))
        out.render(o)
    }
}
