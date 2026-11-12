# Carlector 프로토타입

차량 앱은 일상 운행을 기록하고 적립 티켓을 표시합니다. 공모전 시연에서 휴대폰 앱의 뽑기와 도감은 독립 시연 데이터를 사용합니다. 이 저장소에는 차량 앱 소스가 포함됩니다. 두 앱의 계정·티켓 동기화는 후속 개발 범위입니다.

## 데스크톱에서 실행

1. Android Studio로 기존 Carlector 프로젝트를 엽니다. Gradle JDK 21과 Android SDK Platform 35를 설정하고 동기화합니다.
2. Device Manager에서 Pleos Connect API 34 AVD를 실행하고 홈 화면이 표시될 때까지 기다립니다.
3. 상단 기기 목록에서 Pleos AVD를 선택하고 `app`을 실행합니다. 권한 요청이 표시되면 속도와 주행거리 읽기를 허용합니다.
4. 화면에 표시되는 속도, 기어, UX 제한, 누적 계기판 거리를 확인합니다. Virtual Console에서 지원되는 신호를 바꾸고 앱 화면이 따라오는지 확인합니다.
5. APK는 Windows에서 `./gradlew.bat assembleDebug`로 빌드합니다. 출력은 `app/build/outputs/apk/debug/app-debug.apk`입니다.

## 차량 신호와 구현 상태

| 입력 | API | 상태 |
| --- | --- | --- |
| 속도 | AAOS `PERF_VEHICLE_SPEED` | Pleos API 34에서 0 및 54km/h 수신 확인 |
| 기어 | AAOS `GEAR_SELECTION` | Pleos API 34에서 D단과 P단 전환 수신 확인 |
| UX 제한 | AAOS `CarUxRestrictionsManager` | Pleos API 34에서 D단 정지 중 제한 및 P단에서 제한 해제 확인 |
| 누적 거리 | AAOS `PERF_ODOMETER`, Pleos Vehicle SDK Odometer | Pleos Connect API 34에서 AAOS 조회가 권한으로 제한되어 Pleos SDK로 조회. km에서 m로 변환 |
| 워셔액·엔진오일·브레이크액·변속기 오일 | Pleos Vehicle SDK Safety 2.3.2 | 테스트 이미지에서 네 항목 모두 `ERROR_NOT_SUPPORTED_PROPERTY`. 경고 해제 지급 로직은 모의 입력으로 검증 |
| 계기판 표시등·출력 제한 | Pleos Vehicle SDK Safety 2.3.2 | 안내용 조회 코드 연결. 티켓 지급에는 사용하지 않음 |

AAOS 모드는 자동차 시스템에서 자동으로 선택됩니다. AAOS 누적 거리를 읽지 못하면 Pleos Vehicle SDK Odometer의 `getCurrentDistanceDriven`을 사용합니다. 응답은 km이며 내부에서 m로 변환합니다. 거리 출처가 바뀌면 새 값부터 기준을 잡습니다. 필수 값을 조회하지 못하면 화면에 조회 불가가 표시되고 운행 티켓 지급이 보류됩니다.

Safety는 항목별 읽기 지원을 확인한 뒤 상태를 조회합니다. 권한 부족, 미지원, 조회 실패, 응답 지연, 오일 상태 오류는 확인 불가로 표시합니다. 데스크톱에서 `CarlectorSafety` Logcat 태그로 조회 실패 원인을 확인할 수 있습니다.

## 미션 정책

- 날짜별로 앱이 관측한 이동시간과 계기판 거리 변화만 합산합니다. **10분과 5km를 모두 충족**하고 **P단·속도 0·UX 제한 해제**가 확인되면 운행 티켓 1장을 적립합니다.
- 신호 누락, 역행하는 계기판 값, 비정상 거리 변화는 해당 구간을 집계에서 제외합니다. 앱 비실행 구간과 날짜가 바뀌는 샘플 구간도 제외합니다.
- 주차 확인 시, 주행 중 약 1분마다, 앱이 화면에서 내려갈 때 누적값을 저장합니다. 재실행 시 저장된 누적값을 불러오고 첫 샘플부터 새 기준을 잡습니다.
- 차량 관리 미션은 항목별 경고 발생을 저장하고, 같은 항목에서 경고 없음 상태를 10초 이상 간격으로 재확인한 뒤 주차가 확인됐을 때 적립합니다. 여러 항목이 해제돼도 관리 티켓은 하루 1장입니다.
- 현재 지급은 기존 기기 내부 저장으로 처리합니다. 미션별 하루 1회, 공통 일일 3장, 월요일 기준 주간 21장 상한을 적용합니다. 3번 담당의 공통 `reward-core` 모듈 연결은 후속 작업입니다.

## 시연 검증 결과

- **Pleos Connect v2.1.0 API 34** — Virtual Console에서 D단·54km/h와 연속 거리 값을 입력했습니다. 10분·5km를 충족한 뒤 D단 정지 시 0장, P단 전환 시 1장, 같은 날 다시 주차하거나 앱을 재실행해도 1장 유지까지 확인했습니다.
- **차량 관리** — 경고 발생·해제, 10초 재확인, 주차 후 하루 1회 적립을 Kotlin 모의 입력으로 검증했습니다. 해당 액량 경고 전환은 Pleos 테스트 이미지에서 재현하지 못했습니다.
- **후속 개발** — 주간 달성 횟수 화면, 공통 보상 모듈 연동, 제한속도 준수 보상이 남아 있습니다. 실제 구간단속 콜백 수신과 동일 구간 추적은 아직 검증하지 못했습니다.

## 모의 입력 확인

일반 Android 에뮬레이터에서 debug 앱을 실행하면 모의 차량 버튼이 표시됩니다. **D단 출발 → 1분 진행 10회 → D단 신호 대기 → P단** 순으로 운행 적립을 확인할 수 있습니다. 관리 적립은 **P단 → 워셔액 경고 발생 → 경고 없음 → 10초 후 재확인** 순으로 확인합니다. 모의 모드와 AAOS 모드의 티켓 저장 공간은 별도입니다.

Kotlin CLI로 미션 정책을 검증할 수 있습니다.

```powershell
kotlinc app/src/main/java/com/drivedex/prototype/MissionEngine.kt tools/MissionEngineCheck.kt -include-runtime -d mission-check.jar
java -jar mission-check.jar
```
