# 앞만봐 (Eyes Forward)

**아이가 벨트를 매야 영상이 켜지고, 풀면 멈춥니다.**
차량 신호로 뒷좌석 아이의 안전을 챙기고, 운전자는 뒤돌아보지 않고 앞만 보게 하는 Pleos Connect 패밀리 안전 앱입니다.

- 시연 영상 (2분): [media/앞만봐_시연.mp4](media/앞만봐_시연.mp4)
- APK: [apk/앞만봐_앞만봐_release.apk](apk/) (차량 앱), [apk/앞만봐_태블릿_release.apk](apk/) (태블릿 앱)

## 무엇을 하나요

| 안전 기능 | 차량 신호 | 동작 |
|---|---|---|
| ① 벨트 지키기 | 2열 벨트 | 아이가 스스로 매면 그 좌석 태블릿 재생 + 칭찬 스티커. 주행 중 풀면 그 좌석만 잠금 + 차가 운전자에게 음성 알림 |
| ② 승하차 안전 | 뒷문 × 벨트 | 문이 열려도 벨트를 맨 채면 계속 재생. 벨트를 풀면 하차로 보고 잠금, 문이 닫혀도 다시 매기 전엔 재생 안 함 |
| ③ 운전자 시선 지키기 | 기어 P/D, 음성 | 주행 중 운전자 화면은 카드·마이크만. "첫째 거 다음 편", "첫째 소리 줄여", "얼마나 남았어?" 같은 음성으로만 지휘 |
| ④ 장거리 대비 | 배터리 × 남은 시간 | 도착 전에 꺼질 것 같으면 차가 먼저 알림. "배터리 몇 프로 필요해?"라고 물으면 숫자로 답하고, 충전을 감지해 확인 |

카시트 좌석은 차량 벨트로 아이 상태를 알 수 없어서, 뒷문이 닫히면 차가 "둘째 카시트 벨트 채웠나요?"라고 묻고 부모가 "응" 해야 재생합니다.

## 구성

```
[차량 앱 · Pleos Connect]  ──WebSocket──  [중계 서버 · Node.js]  ──WebSocket──  [태블릿 앱 · 갤럭시탭]
 Vehicle SDK 신호 수신                     차량 1대 = 방 1개                       YouTube IFrame Player 재생
 규칙 엔진 (좌석 × 신호 × 모드)            좌석별로 명령 전달                       잠금·안내 화면, 칭찬 스티커
 Pleos STT / TTS 음성                                                              배터리·충전 상태 보고
```

| 폴더 | 내용 |
|---|---|
| `car-app/` | 차량 앱 (Kotlin). `RuleEngine.kt` 안전 규칙, `VehicleSignals.kt` Vehicle SDK 구독, `Voice.kt` Pleos STT/TTS, `VoiceParser.kt` 음성 명령 해석, `assets/car.html` 운전자 화면 + 시나리오 주입 패널 |
| `tablet-app/` | 태블릿 앱 (Kotlin). 중계 서버의 `tablet.html`을 띄우고 배터리 상태를 넘겨 줌 |
| `server/` | 중계 서버 (Node.js + ws). `public/tablet.html` 태블릿 화면 |
| `tools/` | 시연 녹화·편집 스크립트 |
| `apk/` | 릴리스 APK |

## 사용 SDK / API

- Pleos Vehicle SDK 2.3.2: `registerSeatBeltBuckledState`, `registerSeatOccupancy`, `registerDoorPosition` (2열 좌/우)
- 기어: AAOS `CarPropertyManager` `GEAR_SELECTION`
- Pleos SpeechToText 3.3.1 (ON_DEVICE, ko-KR), Pleos TextToSpeech 3.3.1
- 태블릿: YouTube IFrame Player API, Android `BatteryManager`

## 테스트 환경

- Pleos Connect 에뮬레이터 (AVD `Pleos_Connect_v2_1`, 2560×1440), macOS
- 갤럭시탭 실기기 (Android), 맥과 같은 Wi-Fi
- Node.js 18 이상, Android Studio (JDK 17), AGP 8.8, Kotlin 2.0

## 실행 방법

### 1. 중계 서버
```bash
cd server
npm install
node server.js          # 포트 8765. 시작하면 태블릿 접속 주소를 출력합니다
```

### 2. 차량 앱 (에뮬레이터)
에뮬레이터 음성 인식을 쓰려면 호스트 마이크를 켜서 실행합니다.
```bash
emulator -avd Pleos_Connect_v2_1 -allow-host-audio
adb emu avd hostmicon
adb install apk/앞만봐_앞만봐_release.apk
```
차량 앱은 `ws://10.0.2.2:8765`(에뮬레이터에서 본 맥)로 중계 서버에 접속합니다.

### 3. 태블릿 앱
```bash
adb -s <태블릿> install apk/앞만봐_태블릿_release.apk
```
처음 실행하면 서버 주소(맥의 IP:8765)와 좌석(첫째 = 2열 왼쪽 / 둘째 = 2열 오른쪽)을 정합니다. 화면을 길게 누르면 다시 설정할 수 있습니다.
앱 없이 브라우저로 `http://<맥 IP>:8765/tablet.html?seat=left`를 열어도 됩니다.

### 4. 시나리오 따라 하기 (시연 영상과 같은 순서)
차량 화면 오른쪽 **시나리오 주입 패널**은 시연용입니다. Virtual Console에 2열 벨트 카드가 없어서, Vehicle SDK 콜백과 같은 규칙 엔진 함수(`onBelt`, `onDoor`, `onGear`)를 버튼으로 호출합니다. 실차에서는 코드 변경 없이 Vehicle SDK 콜백으로 동작합니다.

1. 첫째 [벨트 매기] → 태블릿 재생 + 스티커, 배터리가 부족하면 차가 충전 안내
2. 마이크 → "배터리 몇 프로 필요해?" → 태블릿에 충전 케이블 연결
3. [D 주행] → 마이크 → "첫째 거 다음 편"
4. 첫째 [벨트 풀기] → 잠금 + 차 음성 알림 → [벨트 매기]
5. 마이크 → "첫째 소리 줄여"
6. [P 정차] → [뒷문 열기](계속 재생) → [벨트 풀기](하차, 잠금) → [뒷문 닫기](잠금 유지) → [벨트 매기](재생)
7. [D 주행] → 마이크 → "얼마나 남았어?"

### 소스에서 빌드
```bash
./gradlew :car-app:assembleRelease :tablet-app:assembleRelease
```
`keystore/eyesforward-demo.jks`는 시연용 서명 키입니다(실서비스용 아님).

## 확장 예정

- **아이가 잠들면 자동 일시정지:** 태블릿 전면 카메라로 눈 감김을 감지해 영상을 멈추고, 차가 "둘째가 잠들었어요"라고 알림 (얼굴 영상은 저장·전송하지 않고 태블릿 안에서만 처리)
- 도착 10분 전 "마지막 편이에요" → 도착하면 자연스럽게 종료 (NaviHelper)
- 방전이 예상되면 휴게소 경유지 제안 → "응" 한 마디로 추가
- 조향 변화가 잦은 구간은 멀미 방지를 위해 소리로 듣기
- 태블릿 QR 연결, Gleo AI 연동

## 안전·정책

- 차량 화면에서는 영상을 재생하지 않습니다(미러링 없음).
- 주행 중에는 카드와 음성만 쓰고, 좌석 설정은 정차 중에만 바꿀 수 있습니다.
- 카메라·위치 등 아이의 개인정보는 수집하지 않습니다.
