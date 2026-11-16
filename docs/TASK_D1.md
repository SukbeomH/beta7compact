# TASK D1: UI/UX·디자인 리서치와 재구성 제안 (코드 구현 아님)

배경: beta7compact(Sony A7C ↔ Galaxy Z Fold8, Android 17) 0.3.0 기능은 동작하지만 화면 구성·디자인이 개발자용 수준. RAW 현상(M5b)은 보류, 디자인 재구성 먼저. 사용자는 1인(석범님), 낮은 마찰 UX 선호(입력보다 버튼), 한국어 UI. 핵심 사용 흐름: 촬영 후 USB로 연결 → 새 사진 가져오기 → 필름 레시피(LUT) 적용·보정 → 저장·공유. 원격 촬영·BLE 위치는 이후 기능(자리만 고려).

## 1. 현황 파악
- 소스(app/src/main/java/.../ui/*, MainScreen.kt, RecipeScreen.kt 등)를 읽고 현재 화면 목록·내비게이션·상태·오류 표시·버튼 배치를 표로 정리. 각 화면의 사용성 문제(탭 수, 정보 위계, 빈 상태/오류 상태, 폴더블 대응, 접근성)를 근거와 함께 기록.
- 가능하면 에뮬레이터/robolectric 없이도 Compose @Preview 렌더 또는 Paparazzi 같은 외부 의존 없이 할 수 있는 범위에서 스크린샷 확보(불가하면 생략하고 명시). 외부 라이브러리 추가 금지.

## 2. 리서치 (출처 URL 필수, 원문 확인한 것만 사실로 기재)
- 카메라 동반 앱: Sony Imaging Edge Mobile / Creators' App, Fujifilm XApp, Canon Camera Connect, Nikon SnapBridge, Ricoh GR/Image Sync — 연결·가져오기·원격 흐름, 사용자 리뷰에서 반복되는 불만(앱스토어/커뮤니티 리뷰 인용).
- 필름 레시피·보정 앱: VSCO, Lightroom Mobile, Darkroom, Snapseed, RNI Films, Dazz/Huji, Fujifilm 필름 시뮬레이션 레시피 커뮤니티 앱 — 프리셋 탐색, 전/후 비교, 강도 조절, 편집 도구 배치, 일괄 적용, 레시피 공유 UX.
- 플랫폼 가이드: Material 3(Material You, 동적 색상, 컴포넌트), Android 대형 화면·폴더블 가이드(canonical layouts: list-detail, supporting pane, window size classes, 힌지/테이블탑 자세), 접근성(터치 타깃 48dp, 대비), 사진 앱 다크 테마 관례, 예측형 뒤로가기, edge-to-edge.
- 사진 편집 UX 연구/아티클(NN/g 등)에서 슬라이더·프리셋·비파괴 편집 관련 근거.

## 3. 산출물
- docs/design/RESEARCH.md: 리서치 요약(앱별 패턴·장단점·인용), 우리 앱에 적용할 원칙 10개 이내.
- docs/design/IA.md: 재구성된 정보 구조 — 탭/내비게이션 제안(예: 카메라·라이브러리·레시피·설정), 화면 흐름도(mermaid), 각 화면의 목적·주요 동작·빈/오류/진행 상태, 폴더블 접힘/펼침/테이블탑 레이아웃.
- docs/design/DESIGN-SYSTEM.md: 색(다크 기본, 동적 색상 여부 근거), 타이포, 간격, 컴포넌트(사진 그리드, LUT 스트립, 슬라이더, 전후 비교, 진행/오류 배너), 모션, 아이콘(Material Symbols 라이선스 확인).
- docs/design/wireframes/: 주요 화면 6~10개 와이어프레임을 SVG(직접 작성) 또는 HTML 단일 파일로. 접힘/펼침 두 가지.
- docs/design/PLAN.md: 구현 단계 제안(D2 이후), 기존 기능 불변식(원본 보존, 카메라 쓰기 0 등) 영향 없음 확인, 결정이 필요한 질문 5개 이내(선택지와 권장안 포함).

## 경계
브랜치 docs/design-research(master 7a30334에서), 문서·와이어프레임만 커밋, 앱 코드 변경 금지. 원자 커밋, 이 브랜치만 push. force·--no-verify·git add -A·APK 커밋 금지. 타 앱 스크린샷·로고·저작물은 저장소에 넣지 않음(링크로만 인용). 완료 시 DESIGN-RESEARCH-READY @<sha8>.
