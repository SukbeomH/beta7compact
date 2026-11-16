# D1 구현 계획 제안 (D2 이후)

전제: UI 계층만 변경. `core/`, `usb/`, `ble/`, `library/`, `recipe/`, `transfer/` 로직·DB 스키마·레시피 JSON 형식은 건드리지 않는다.

## 1. 단계

| 단계 | 내용 | 변경 범위 | 완료 기준 |
|---|---|---|---|
| D2 테마·토큰 | 다크 중립 colorScheme, 타이포, Material Symbols 벡터 ~20개, edge-to-edge 인셋 | `ui/theme/*` 신규, `MainScreen.kt` 테마 교체 | 기존 화면이 새 테마로 동작, 기능 회귀 없음 |
| D3 내비 재구성 | 4탭(카메라·사진·레시피·설정), 편집기를 별도 목적지로, `BackHandler`/예측형 뒤로 | `MainScreen.kt` 분해 → `CameraScreen`, `PhotosScreen`, `RecipesScreen`, `SettingsScreen` | 홈·설정 중복 제거, 뒤로가기로 편집기 닫힘 |
| D4 공용 상태 컴포넌트 | 작업 배너·오류 배너·빈 상태·연결 칩 | `ui/components/*` | 모든 진행/오류가 공용 컴포넌트 경유 |
| D5 편집기 | 미리보기 가변 높이, LUT 스트립+강도, 세부 조정 시트, 슬라이더 값/초기화 버튼 | `RecipeScreen.kt`, `ColorEditor.kt` | 같은 Recipe 값이 동일하게 저장(스냅샷 비교) |
| D6 적응형 | 창 크기 클래스(600/840dp), list-detail·supporting pane, 레일, 테이블탑 | 각 화면 | Fold8 커버·내부 화면 수동 점검표 통과 |
| D7 레시피 탭 | `RecipeManager`를 탭으로 이동, 기본 레시피 카드, 샘플 썸네일 | `RecipeSharing.kt` | 공유/QR/가져오기 기존 테스트 통과 |
| D8 접근성 점검 | 48dp, contentDescription, TalkBack 순서, 대비 측정 | 전체 | 체크리스트 기록 |

각 단계는 독립 PR·원자 커밋, 단계마다 기존 단위 테스트(`./gradlew test`) 통과 확인.

## 2. 불변식 영향 확인

| 불변식(기존 문서) | 영향 | 근거 |
|---|---|---|
| 원본 보존·결과 별도 저장(ARCHITECTURE.md §5, M2-ENGINE.md) | 없음 — 저장 경로는 `RecipeWorker.start` 그대로 호출 | 버튼 위치만 변경 |
| 카메라 쓰기 0 / 게시 원본 삭제 없음(RESULT_M1_FIX3·4) | 없음 — USB는 기존 `scanUsb`/`ImportWorker` 호출만 | UI가 새 PTP 명령을 만들지 않음 |
| GPS 좌표 화면·로그·DB 비기록(MainScreen 안내문) | 없음 — 위치 UI는 비활성 자리만 | 표시 값 추가 없음 |
| 레시피 JSON v2·공유 코드 형식(M5A-SHARING.md) | 없음 | 직렬화 미변경 |
| 권한은 사용자 동작 시점에 요청 | 유지 — 연결 칩의 해결 버튼이 같은 요청 호출 | |
| Room 스키마 v4 | 없음. 단 "편집됨" 필터는 기존 결과 테이블 조회로 해결, 불가하면 필터 보류 | **미확인**: 현재 DAO에 사진별 결과 존재 조회가 있는지 D3에서 확인 |

## 3. 결정이 필요한 질문

| # | 질문 | 선택지 | 권장안 |
|---|---|---|---|
| Q1 | 테마 | A 항상 다크 · B 시스템 따름(편집기만 항상 다크) · C 시스템 따름 | **A** — 사진 판단 일관성, 구현 단순 |
| Q2 | 앱 시작 탭 | A USB 연결 시 카메라, 아니면 사진 · B 항상 카메라 · C 마지막 탭 | **A** — 핵심 흐름 최단 |
| Q3 | 아이콘·적응형 구현 | A 아이콘 XML 복사 + 직접 `BoxWithConstraints`/WindowManager 판정(새 의존성 0) · B `material3-adaptive`·`adaptive-navigation-suite` 의존성 추가 | **A**로 시작, 테이블탑 품질이 부족하면 B 재검토(의존성 추가는 별도 승인) |
| Q4 | 가져오기 후 동작 | A 완료 스낵바 "보기"만 · B 자동으로 사진 탭 이동 + 방금 가져온 사진 선택 · C 기본 레시피 자동 적용(현재 설정 유지) 후 결과 보기 | **A** + 설정의 자동 레시피 유지 — 의도치 않은 화면 전환 방지 |
| Q5 | 레시피 탭 범위 | A 보관 레시피만 · B 내장 LUT 카탈로그도 탐색(샘플 사진) | **A** 먼저(D7), B는 샘플 이미지 출처 정리 후 |
