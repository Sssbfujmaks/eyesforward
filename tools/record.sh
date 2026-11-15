#!/bin/bash
# 시연 녹화: 차량 에뮬레이터 화면 + 태블릿 화면 + 맥북 마이크를 동시에 녹화한다.
#   tools/record.sh start   # 녹화 시작
#   tools/record.sh stop    # 녹화 끝, recordings/<시각>/ 에 car.mp4, tablet.mp4, audio.m4a 저장
# 환경 변수: CAR(기본 emulator-5554), TAB(태블릿 adb 주소), MIC(맥 오디오 장치 번호, 기본: MacBook 내장 마이크)
set -u
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
CAR=${CAR:-emulator-5554}
TAB=${TAB:-$($ADB devices | awk 'NR>1 && $2=="device" && $1!~/^emulator/ {print $1; exit}')}
# 블루투스 기기를 끄고 켜면 장치 번호가 바뀌므로, 이름으로 맥북 내장 마이크 번호를 찾는다
MIC=${MIC:-$(/opt/homebrew/bin/ffmpeg -f avfoundation -list_devices true -i "" 2>&1 | sed -n '/audio devices/,$p' | grep -m1 "MacBook" | sed -E 's/.*\[([0-9]+)\].*/\1/')}
MIC=${MIC:-0}
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STATE="$ROOT/recordings/.current"

case "${1:-}" in
start)
  OUT="$ROOT/recordings/$(date +%Y%m%d_%H%M%S)"; mkdir -p "$OUT"; echo "$OUT" > "$STATE"
  $ADB -s "$CAR" shell rm -f /sdcard/ef_car.mp4; $ADB -s "$TAB" shell rm -f /sdcard/ef_tab.mp4
  python3 -c 'import time;print(int(time.time()*1000))' > "$OUT/start_ms"
  $ADB -s "$CAR" shell screenrecord --size 1920x1080 --bit-rate 8000000 --time-limit 180 /sdcard/ef_car.mp4 >/dev/null 2>&1 &
  $ADB -s "$TAB" shell screenrecord --size 1280x768 --bit-rate 8000000 --time-limit 180 /sdcard/ef_tab.mp4 >/dev/null 2>&1 &
  # 마이크 녹음은 macOS 터미널 앱에서 실행한다 (터미널이 마이크 권한을 받는다)
  cat > "$OUT/audio.command" <<CMD
#!/bin/bash
echo "앞만봐 시연 녹음 중… (녹화가 끝나면 이 창은 저절로 멈춥니다)"
echo \$\$ > "$OUT/audio.pid"
exec /opt/homebrew/bin/ffmpeg -hide_banner -loglevel error -f avfoundation -i ":$MIC" -ac 1 -ar 48000 -c:a aac "$OUT/audio.m4a"
CMD
  chmod +x "$OUT/audio.command"; open -a Terminal "$OUT/audio.command"
  echo "녹화 시작: $OUT (태블릿 $TAB, 최대 3분)"
  ;;
stop)
  OUT=$(cat "$STATE")
  $ADB -s "$CAR" shell pkill -INT screenrecord; $ADB -s "$TAB" shell pkill -INT screenrecord
  [ -f "$OUT/audio.pid" ] && kill -INT "$(cat "$OUT/audio.pid")" 2>/dev/null
  sleep 3
  $ADB -s "$CAR" pull /sdcard/ef_car.mp4 "$OUT/car.mp4" >/dev/null && $ADB -s "$TAB" pull /sdcard/ef_tab.mp4 "$OUT/tablet.mp4" >/dev/null
  echo "녹화 끝: $OUT"; ls -la "$OUT"
  ;;
*) echo "사용법: $0 start|stop"; exit 1 ;;
esac
