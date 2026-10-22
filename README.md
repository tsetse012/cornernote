# 코너노트 (CornerNote)

Pleos Connect(Android Automotive) 앱. 주행을 측정해 **코너마다 왜 거칠었는지 원인을 짚고 처방을 준다.**
주행 중에는 화면 요소를 최소화하고, 정차하면 리포트 카드를 띄운다.

- 패키지: `ai.pleos.coach`
- 스펙 정본: `~/Claude/research/pleos-app-contest-2026/orchestration/APP_SPEC.md`
- 기존 프로브 앱(`ai.test.pleosprobe`)은 참고용으로 그대로 남아 있다(런처 비노출).

---

## 1. 실행법

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

# 빌드
/tmp/gradle-8.9/bin/gradle :app:assembleDebug --no-daemon

# 단위 테스트
/tmp/gradle-8.9/bin/gradle :app:testDebugUnitTest --no-daemon

# 설치·실행
$ANDROID_HOME/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
$ANDROID_HOME/platform-tools/adb shell am start -n ai.pleos.coach/.MainActivity
```

### 위치 권한 (에뮬레이터)

`pleos.car.permission.FUSED_LOCATION` 은 `dangerous` 인데 **에뮬레이터의 권한 승인 UI 가 크래시**한다.
그래서 앱은 **권한을 요청하지 않는다.** 이미 있으면 쓰고, 없으면 가상 GPS 재생 모드로 떨어진다
(화면에 "위치 없이 측정 중" 배지가 뜬다 — 숨기지 않는다).

에뮬레이터에서 실위치까지 보고 싶으면 수동으로 부여한다:

```bash
adb shell pm grant --user 10 ai.pleos.coach pleos.car.permission.FUSED_LOCATION
adb shell pm grant --user 10 ai.pleos.coach android.car.permission.CAR_SPEED
adb emu geo fix 127.0276 37.4979
```

> **주의**: `pm grant` 는 `--user 10` 을 붙여야 한다. 앱이 User 10 에서 돌기 때문에
> `--user 0` 에 부여하면 `granted=false` 로 남는다(실측).
> `android.car.permission.CAR_SPEED` 도 `dangerous` 라 같이 부여해야 속도가 읽힌다.

### 데모 재생 모드

시작 화면에서 **데모 재생** 버튼. 콘솔 주입 없이 앱 안에서 60초 프로파일을 재생한다
(정속 → 늦은 감속 코너 → 정상 코너 → 급조향 코너). 화면에 "데모 재생 중" 배지가 뜬다.
끝나면 자동으로 리포트가 열린다.

---

## 2. 구조

```
app/src/main/java/ai/pleos/coach/
├─ core/        # 순수 Java. Android 의존성 0. 전부 단위 테스트 대상
│  ├─ SgDerivative.java        # Savitzky-Golay 1차 미분 (polyorder=1, 균일/비균일 dt)
│  ├─ AccelEstimator.java      # 속도 → 가속도 (창 45샘플 @45.6Hz = 1.0초)
│  ├─ SteerRateEstimator.java  # 조향각 → δ̇ (시간 창 1.28초)
│  ├─ Metrics.java             # 4지표 계산 (조향 부드러움·감속 집중도·전환 지연)
│  ├─ EventDetector.java       # 지속시간 + 비대칭 해제 디바운스
│  ├─ CornerSegmenter.java     # 조향 기반 코너 구간 검출
│  ├─ CauseLabeler.java        # 원인 라벨 5종 + 처방 문구
│  ├─ Efficiency.java          # 전비 (ΔSOC × 용량 ÷ 거리)
│  └─ Samples.java             # 링버퍼·타임스탬프 정렬·dt 상한
├─ vehicle/     # Pleos Vehicle SDK 래퍼 (속도·조향·브레이크·기어·에너지·UX제한)
├─ location/    # FusedLocation 래퍼 + 시간정렬 + 가상 GPS 폴백
├─ voice/       # TTS (ON_DEVICE) + 하루 2회·10분 간격 제한
├─ ui/          # 주행 중 간편 화면 / 정차 리포트 카드
├─ demo/        # 재생 모드 프로파일
├─ CoachSession.java  # 신호 파이프라인 (데모와 실주행이 같은 경로)
└─ MainActivity.java
```

**데모와 실주행은 같은 파이프라인**(`CoachSession`)을 쓴다. 데모만 다른 프로파일을 넣는다.

---

## 3. 신호처리

| 신호 | 방식 | 근거 |
|---|---|---|
| 가속도 | SG 1차 미분, 창 45샘플(1.0초), 중심창 **0.48초 지연** | 1 km/h 양자화에서 단순차분은 ±13.2 m/s² 스파이크. 1초 회귀는 오차 7% |
| 조향율 | SG 1차 미분, **시간 창 1.28초** | 1초 미만은 1° 양자화 잡음 ±7.8 °/s |
| 조향 부드러움 | `v²·|δ̇|/L`, L=2.7, v는 최소 5 m/s | `v·|δ̇|` 는 속도항이 하나 부족. `|δ̇|` 단독은 저속 회전교차로를 최악으로 채점 |
| 전환 지연 | 브레이크 false 시각 → 조향 5° 초과 시각 | 불확도 ±100ms (브레이크 77ms + 조향 128ms) |
| 전비 | ΔSOC% / 100 × 용량Wh / 1000 → 거리 ÷ 에너지 | `getInstantaneousEvEfficiency` 는 에뮬레이터 미지원 |

**타임스탬프**: 차량 신호는 `SystemClock.elapsedRealtime()`, 위치는 벽시계.
앱 시작 시 `offset = wallNow - elapsedNow` 1회 계산, ±1초 넘게 흔들리면 재계산.
`dt` 상한 1.0초 — 프레임 드랍이 기울기를 폭발시키지 않게(넘으면 창을 비운다).

**저크(2차 미분)는 계산하지 않는다.** 1 km/h 양자화에서 SG 2차 미분 잡음(2.23 m/s³)이
승차감 임계(0.3~0.9 m/s³)보다 크다 — 잡음 측정기가 된다.

### 구현 중 발견한 두 가지 (스펙에 없던 실측)

1. **조향 콜백은 값이 변할 때만 발화한다.** 그래서 "샘플 10개" 창을 쓰면 조향이 잠시 멈춘 동안
   창이 몇 초로 늘어나 기울기가 희석된다(실측: 조향 부드러움 지수가 0으로 나옴).
   → 창을 **시간**으로 자르고, 최소 샘플 2개·최소 간격 0.9초로 판정한다.
2. **코너 시작은 실제 브레이크보다 늦다.** 조향이 15°를 0.5초 지속한 시점이라 그만큼 지연된다.
   → 감속 집중도는 코너 시작에서 뒤로 밀지 않고, 코너 근처의 **속도 최소점**을 끝으로 잡고
   시작은 그 구간의 **마지막 브레이크 밟기**로 잡는다.

---

## 4. 지표 4개

1. **가속도 프로파일** — 평균 절대값·95퍼센타일 + 감속 집중도(마지막 1/3 구간 감속량 / 전체).
   감속 이벤트 임계 −2.5 m/s², 해제 −2.0 m/s².
2. **조향 부드러움** — `v²·|δ̇|/L` (m/s³). 속도 구간별(0-30 / 30-60 / 60-90 / 90+)로 나눠 보고.
3. **브레이크-조향 전환 지연** — 짧을수록 좋다. 0.1초 미만은 "측정 불가".
4. **전비** — km/kWh.

이벤트 판정은 공통으로 **0.5초 지속**을 요구한다. 1 km/h 양자화가 만드는 단발 스파이크를
프레임 수로 세면 제동 1회가 이벤트 5개가 된다.

---

## 5. 코너 검출 + 원인 라벨

- 조향 |15°| 0.5초 지속 → 코너 시작 / |5°| 0.5초 지속 → 종료
- 코너 사이 최소 간격 1.5초, 2초 미만 코너는 버린다
- 위치 라벨: 코너 중앙 시각의 가장 가까운 위치 샘플(1Hz) → "북동쪽으로 도는 코너"
  (역지오코딩은 하지 않는다 — 네트워크·API 없음)
- 위치가 없으면 **"코너 1·2·3"** 상대 표기 + "위치 없이 측정 중" 배지

| 원인 라벨 | 판정 조건 | 처방 |
|---|---|---|
| 늦은 감속 | 감속 집중도 > 0.7 | 코너 2초 전에 감속을 시작하세요 |
| 전환 지연 큼 | 전환 지연 > 0.8초 | 브레이크를 조금 남긴 채로 꺾으세요 |
| 조향 급함 | 조향 부드러움 상위 10% | 핸들을 두 번에 나눠서 드세요 |
| 탈출 가속 급함 | 코너 후반 가속도 상위 10% | 코너를 나오면서 천천히 밟으세요 |
| 감속 부족 | 코너 진입 속도 > 그 운전자의 코너 평균 | 코너 진입 전에 속도를 더 줄이세요 |

리포트에는 **거친 코너 상위 3개만**. 색(초록/노랑/주황) + 한 줄 라벨.
**점수 0~100 은 표시하지 않는다** — 사건 단위 언어("코너 3곳")를 쓴다.

---

## 6. 검증 범위

### 단위 테스트 — 33개 전부 통과

`app/src/test/java/ai/pleos/coach/`

| 파일 | 개수 | 확인 내용 |
|---|---|---|
| `core/SgDerivativeTest` | 2 | 선형 램프 기울기 정확 복원(균일·비균일 dt) |
| `core/AccelEstimatorTest` | 4 | 양자화 계단에서 램프 오차 < 15%, 정속 잡음 < 0.1, 프레임드랍 시 창 리셋, 지연 0.48초 |
| `core/SteerRateEstimatorTest` | 2 | 1° 양자화에서 부호·크기 |
| `core/EventDetectorTest` | 3 | 단발 스파이크 무시, 1초 지속 = 1개, 히스테리시스 |
| `core/CauseLabelerTest` | 5 | 늦은 감속·전환 지연·급조향 라벨 + 처방 문구 |
| `core/EfficiencyTest` | 3 | ΔSOC 10%·77.4kWh·5km 기대값, 단위 정규화, NaN |
| `core/CornerSegmenterTest` | 3 | 코너 2개 검출, 2초 미만 폐기, 0.5초 미만 스파이크 무시 |
| `core/SamplesTest` | 3 | dt 상한 1.0, 벽시계→단조시계 정렬, 링버퍼 |
| `DemoReplayTest` | 8 | 데모 60초 재생 → 코너 3곳, 4지표 전부 비영(非零), 거리 측정, 코너1=늦은 감속, 코너2=양호, 코너3=급조향, 처방 1개 |

`DemoReplayTest` 는 에뮬레이터 없이 **실제 파이프라인**을 그대로 돌린다 — 회귀 방지용.

스크린샷: `shots/01_start.png` · `02_demo_driving_early.png` · `03_demo_driving_corners.png` ·
`04_demo_report.png` · `05_demo_report_prescription.png` · `06_live_driving_corner.png` · `07_live_report.png`

### 에뮬레이터 실측

- **데모 재생 모드**: 코너 3곳 검출 + 리포트 렌더 (스크린샷 `shots/01~05`)
- **실주행(콘솔 주입)**: 속도 45.6Hz 폴링·조향·브레이크·기어·위치 모두 읽힘, 코너 2곳 검출
- **logcat 4지표 실제 값** (데모 기준):

```
METRIC1 accel meanAbs=0.312 m/s2 p95=1.601 m/s2 decelEvents=1
METRIC2 steerIndex mean=1.466 m/s3 bands={0-30=3.43, 30-60=0.92}
METRIC3 transitionDelay=0.627 s samples=3
METRIC4 kmPerKwh=0.834 energy=0.774 kWh dSoc=1% dist=0.646 km reliable=false
REPORT corners=3 durationMs=60004 demo=true location=false
CORNER #1 peakSteer=25 entry=36.0 kph steerIdx=14.93 conc=0.78 delay=1.08 labels=늦은 감속|전환 지연 큼|탈출 가속 급함|감속 부족 severity=BAD
CORNER #2 peakSteer=-20 entry=30.0 kph steerIdx=6.52 conc=NaN delay=0.65 labels=- severity=GOOD
CORNER #3 peakSteer=45 entry=26.0 kph steerIdx=15.73 conc=NaN delay=0.16 labels=조향 급함 severity=WARN
tts spoke: 이번 주행에 코너 두 곳이 거칠었어요. 다음 코너는 조금 일찍 감속해 보세요.
```

- **실주행 기준** (콘솔 주입):

```
METRIC1 accel meanAbs=0.767 m/s2 p95=5.195 m/s2 decelEvents=3
METRIC2 steerIndex mean=12.340 m/s3 bands={0-30=7.87, 30-60=21.20}
METRIC3 transitionDelay=3.081 s samples=1
METRIC4 kmPerKwh=NaN energy=NaN kWh dSoc=NaN% dist=NaN km reliable=false
REPORT corners=2 durationMs=40116 demo=false location=true
CORNER #1 peakSteer=-28 entry=26.0 kph steerIdx=9.47 conc=0.00 delay=3.08 labels=전환 지연 큼 severity=WARN
CORNER #2 peakSteer=45 entry=30.0 kph steerIdx=21.20 conc=NaN delay=NaN labels=조향 급함|감속 부족 severity=BAD
```

- **TTS**: `tts ready (ON_DEVICE)` → `tts spoke: 이번 주행에 코너 두 곳이 거칠었어요...`

---

## 7. 알려진 한계

1. **전비는 이 데모에서 "표본 부족"으로 표시된다.** SOC 가 1% 해상도라 0.65km 주행으로는
   ΔSOC 가 1%p 밖에 안 움직인다(양자화 오차가 값을 지배). 2km 이상 주행해야 평가할 수 있고,
   그 전에는 숫자를 숨기지 않되 **"표본 부족"을 명시**한다. 실차에서는 정상 동작할 것으로 보지만
   **실차 미검증**이다.
2. **`getCurrentDistanceDriven` 이 에뮬레이터에서 5.0 km 고정값**을 준다(주행해도 안 변함).
   그래서 위 실주행 로그의 `dist=5.000 km` 는 실제 주행거리가 아니다. 실차 확인 필요.
3. **브레이크가 boolean** 이라 압력 곡선을 볼 수 없다. 전환 지연 불확도 ±100ms 는 이 한계에서 온다.
4. **조향 콜백이 값이 변할 때만 발화**한다. 정지 조향(핸들을 잡고 있는 상태)은 조향율 0 으로 본다.
5. **에뮬레이터에서는 위치 권한을 UI 로 승인할 수 없다**(승인 화면이 크래시). 데모는 가상 GPS 로 돈다.
   실차에서는 동작하지만 **실차 미검증**.
6. **UX 제한(`uxrestriction`)은 획득·리스너 등록까지 확인했으나, 실제 제한 상태 전환은
   에뮬레이터에서 재현하지 못했다.** 기어를 D 로 올리는 주입 경로가 콘솔에 없어서다
   (`speed` 만으로는 `isRequiresDistractionOptimization()` 이 false 로 남았다).
   코드 경로는 살아 있고, 제한 상태면 리포트 진입을 막고 대기 화면을 띄운다.
7. **실차 미검증**: 모든 수치는 에뮬레이터 주입값 기준이다. 실차 CAN 해상도·위치 정확도·
   터널/지하주차장 거동은 확인하지 못했다.
8. **데모 프로파일은 1 km/h·1° 계단**이다. 플랫폼 양자화를 그대로 흉내 낸 것이라
   계단이 보이는 게 정상이다(숨기지 않는다).

---

## 8. 하지 않은 것 (스펙 §12)

- 저크(2차 미분) 계산 — 안 함
- "횡저크"·"trail braking"·"ISO 2631-1" 용어 — 안 씀 (`grep` 0건)
- 점수 0~100 크게 표시 — 안 함 (사건 단위 언어만)
- 리더보드·순위·타인 비교 — 안 함
- 권한 초기화를 try/catch 밖에 두기 — 모든 SDK 초기화가 신호별 개별 try/catch
- `drivingstate` 서비스로 UX 제한 조회 — 안 함 (`uxrestriction` 사용)
- `getInstantaneousEvEfficiency` — 안 씀 (`grep` 0건)
