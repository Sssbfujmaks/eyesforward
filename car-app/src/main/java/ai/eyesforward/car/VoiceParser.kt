package ai.eyesforward.car

/**
 * 음성 인식 결과(텍스트)를 '좌석 × 동작'으로 해석한다.
 * Pleos SpeechToText SDK는 텍스트만 돌려주므로 명령 해석은 앱이 직접 한다.
 * 예) "둘째 거 다음 편" → (right, NEXT), "둘 다 멈춰" → (all, PAUSE), "응, 틀어줘" → (null, YES)
 */
object VoiceParser {

    enum class Action(val label: String) {
        NEXT("다음 편"), PAUSE("멈춤"), PLAY("재생"), VOLUME_DOWN("소리 줄임"), VOLUME_UP("소리 키움"),
        REMAINING("남은 시간"), BATTERY("배터리"), YES("확인"), NO("취소"), UNKNOWN("알 수 없음")
    }

    data class Command(val seat: String?, val action: Action)

    fun parse(raw: String): Command {
        // "첫째꺼/둘째거"의 '꺼'를 '꺼줘(끄기)'로 오해하지 않도록 소유격을 먼저 지운다
        val t = raw.replace(" ", "").replace(",", "").replace(".", "")
            .replace(Regex("(첫째|둘째|큰애|작은애|막내|동생|형|누나|언니|오빠)(꺼|거|것)"), "$1")
        val seat = when {
            listOf("둘다", "모두", "전부", "다같이", "애들").any { it in t } -> "all"
            listOf("첫", "책과", "어째", "처째", "청째", "큰애", "왼쪽", "형", "누나", "언니", "오빠").any { it in t } -> "left"
            listOf("둘", "들째", "툴째", "작은애", "오른쪽", "막내", "동생").any { it in t } -> "right"
            else -> null
        }
        val action = when {
            listOf("다음", "넘겨", "다른거", "바꿔").any { it in t } -> Action.NEXT
            listOf("몇프로", "몇퍼", "배터리", "밧데리", "필요", "프로", "퍼센트", "충전얼마", "몇%").any { it in t } -> Action.BATTERY
            listOf("얼마나남", "언제도착", "남은시간", "몇분", "얼마남").any { it in t } -> Action.REMAINING
            listOf("소리줄", "작게", "볼륨줄", "볼륨낮", "조용", "줄여", "낮춰", "너무커").any { it in t } -> Action.VOLUME_DOWN
            listOf("소리키", "크게", "볼륨키", "볼륨높", "키워", "높여", "안들려").any { it in t } -> Action.VOLUME_UP
            listOf("멈춰", "정지", "꺼", "그만", "스톱").any { it in t } -> Action.PAUSE
            listOf("틀어", "켜", "재생", "시작", "보여").any { it in t } -> Action.PLAY
            listOf("맸", "맷", "멨", "채웠", "채웟", "했어", "완료", "오케이").any { it in t } -> Action.YES
            listOf("아니", "됐어", "취소").any { it in t } -> Action.NO
            listOf("응", "네", "어", "그래", "채웠", "맞아", "좋아").any { t.startsWith(it) || t == it } -> Action.YES
            else -> Action.UNKNOWN
        }
        return Command(seat, action)
    }
}
