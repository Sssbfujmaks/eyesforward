#!/bin/bash
# 시연 시나리오 자동 실행 (녹화용). 주입 패널 버튼을 정해진 시간에 누르고, 부모 음성은 맥북 스피커로 재생한다.
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
CAR=${CAR:-emulator-5554}
VOICE=${PARENT_VOICE:-Yuna}
EVENTS="$(cd "$(dirname "$0")/.." && pwd)/server/events.jsonl"
tap() { $ADB -s $CAR shell input tap $1 $2; }
L_BUCKLE="2246 378"; L_UNBUCKLE="2428 378"; L_DOOR_OPEN="2246 439"; L_DOOR_CLOSE="2428 439"
GEAR_P="2246 713"; GEAR_D="2428 713"; MIC="1495 676"; RESET="2246 1203"
# 마이크 버튼 → 부모 음성 재생. 명령이 안 나가면(인식 실패) 한 번 더 말한다. (음성 결과만 있는 문장은 retry=no)
speak() {
  local before=$(wc -l < "$EVENTS")
  tap $MIC; sleep 1.6; say -v "$VOICE" "$1"
  [ "${2:-}" = "no-retry" ] && return
  sleep 4
  if [ "$(wc -l < "$EVENTS")" -eq "$before" ]; then tap $MIC; sleep 1.6; say -v "$VOICE" "$1"; fi
}

case "${1:-}" in
prepare)
  tap $GEAR_P; sleep 1; tap $L_UNBUCKLE; sleep 1; tap $RESET; sleep 1 ;;
run)
  sleep 2;  tap $L_BUCKLE          # 아이가 벨트를 맴 → 재생 + 스티커
  sleep 7;  tap $GEAR_D            # 출발
  sleep 6;  tap $L_UNBUCKLE        # 주행 중 벨트 해제 → 잠금 + 운전자 음성 알림
  sleep 8;  tap $L_BUCKLE          # 다시 맴 → 이어서 재생 + 스티커
  sleep 7;  speak "첫째 거 다음 편"
  sleep 4;  speak "첫째 소리 줄여"
  sleep 4;  tap $GEAR_P            # 휴게소 정차
  sleep 3;  tap $L_DOOR_OPEN       # 뒷문 열림 → 일시정지
  sleep 6;  tap $L_DOOR_CLOSE      # 닫힘 → 벨트 착용 중이면 이어서 재생
  sleep 6;  speak "얼마나 남았어?" no-retry
  sleep 8 ;;
*) echo "사용법: $0 prepare|run" ;;
esac
