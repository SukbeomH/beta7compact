# D1 디자인 리서치 — beta7compact

작성: 2026-11-16 · 범위: TASK_D1 §1(현황), §2(리서치) · 앱 코드 변경 없음

표기 규칙
- **[원문]** 페이지 본문을 직접 열어 확인한 내용(인용은 원문 그대로, 영어).
- **[스니펫]** 원문 사이트가 봇 차단(403/Cloudflare)이라 본문은 못 열고, 검색 결과 요약문에서만 확인한 내용. 사실로 쓰되 신뢰도 낮음.
- **미확인**: 확인하지 못한 내용. 추정으로만 사용.
- 타 앱 스크린샷·로고는 저장소에 넣지 않고 링크만 둔다.

---

## 1. 현황 파악 (0.3.0, `ui/*.kt` 기준)

스크린샷: 에뮬레이터·Paparazzi·Robolectric 없이는 Compose 렌더가 불가하고 외부 라이브러리 추가가 금지라 **생략**. 아래는 소스 읽기 결과.

### 1.1 화면·내비게이션 목록

| 화면 | 위치 | 진입 | 주요 내용 | 상태·오류 표시 |
|---|---|---|---|---|
| 홈(탭 0) | `MainScreen.kt` `page==0` | 하단 탭 `⌂ 홈` | USB 카드(연결 상태 텍스트, 권한 버튼), BLE 카드(스캔·페어링·위치 동기화 스위치·간격) | 화면 상단 공용 `error` 텍스트 + `닫기` 텍스트버튼 |
| 가져오기(탭 1) | `MainScreen.kt` `page==1` | `↓ 가져오기` | 진행 `n / m`, `취소`·`다시 스캔`·`전체 받기`·`선택 받기`·`대기열 재개` 텍스트버튼 5개, `GridCells.Adaptive(140.dp)` 사진 그리드 | WorkManager 오류를 빨간 텍스트로 인라인 |
| 라이브러리(탭 2) | `RecipeScreen.kt` `RecipeLibrary` | `▦ 라이브러리` | `레시피 코드 붙여넣기`, `선택 모드`, `선택 편집`, `저장 재개`, 사진 그리드 | 저장 진행/오류 인라인 텍스트 |
| 레시피 편집기 | `RecipeScreen.kt` `RecipeEditor` | 라이브러리 사진 탭(같은 탭 내부 상태 전환) | 미리보기 280dp 고정 높이, 길게 눌러 전/후, `저장` 버튼, 실행취소/다시, 탭 `기본·톤커브·HSL·효과`, LUT 카테고리 칩 + LUT 카드 행, 레시피 관리(이름·보관·공유/QR·기본 지정·목록) | `미리보기 처리 중…` 텍스트, 오류 빨간 텍스트 |
| 설정(탭 3) | `MainScreen.kt` `page==3` | `⚙ 설정` | 홈 카드 전부 + raw PTP 스위치, 진단 로그 공유, 전송 간격 슬라이더, 자동 레시피 스위치 | 공용 `error` |
| 대화상자 | `RecipeSharing.kt` | 버튼 | 레시피 코드 가져오기, 공유(텍스트·QR), LUT 필요 연결 | 대화상자 내부 텍스트 |

내비게이션: `NavigationBar` 4탭, 아이콘은 유니코드 문자(`⌂ ↓ ▦ ⚙`). 편집기는 라우트가 아니라 `editing` 상태라 **시스템 뒤로가기로 닫히지 않을 가능성**(`BackHandler` 사용 여부 미확인 — grep 결과 없음). 테마는 `lightColorScheme(primary=#315E51)` 고정 라이트.

### 1.2 사용성 문제 (근거 = 소스 위치)

| # | 문제 | 근거 | 영향 |
|---|---|---|---|
| P1 | 핵심 흐름(연결→가져오기→편집→저장)이 탭 3개에 흩어짐. 가져온 뒤 편집으로 가려면 탭 전환 + 사진 탭 = 최소 3탭 | 탭 1 그리드와 탭 2 그리드가 별개 | 마찰 증가 |
| P2 | 홈과 설정이 같은 카드를 중복 표시(`page==0 \|\| page==3`) | MainScreen.kt:126 | 홈의 목적 불명확 |
| P3 | 가져오기 화면 동작 5개가 모두 같은 위계의 `TextButton` — 주 동작(새 사진 받기)이 구분 안 됨 | MainScreen.kt:235–241 | 정보 위계 부재 |
| P4 | 오류가 화면 상단 텍스트 한 줄, 스크롤 영역 밖이면 안 보임. 복구 동작(재시도·설정 열기) 없음 | MainScreen.kt:125, RecipeScreen.kt:62,173 | 오류 회복 어려움 |
| P5 | 빈 상태 부재: 카메라 미연결·사진 0장일 때 그리드가 그냥 비어 있음 | 그리드 분기에 empty 처리 없음 | 다음 할 일 안내 없음 |
| P6 | 편집기 미리보기가 고정 280dp — 접힘 화면에서 사진이 작고, 펼침 화면에선 공간 낭비 | RecipeScreen.kt:168 | 사진 앱 본질 약화 |
| P7 | 패널이 한 열 세로 스크롤에 슬라이더 최대 ~14개 나열(효과 탭) | ColorEditor.kt:53–85 | 탐색 피로 |
| P8 | 폴더블 대응이 `maxWidth>=700.dp` 2분할 하나뿐. 창 크기 클래스·힌지·테이블탑 미반영, 하단 탭은 펼침에서도 그대로 | RecipeScreen.kt:217 | 큰 화면 비활용 |
| P9 | 접근성: 아이콘이 유니코드 문자라 의미 전달·크기 불균일, 슬라이더 값이 라벨 텍스트에만 있음, 더블탭 초기화는 발견 어려움 | MainScreen.kt:119, ColorEditor.kt:43,88 | TalkBack·발견성 |
| P10 | 라이트 고정 테마 — 사진 색 판단에 불리, 다크 모드 무시 | MainScreen.kt:115 | 색 판단 왜곡 |
| P11 | 레시피 관리(보관·공유·기본 지정)가 편집 패널 하단에 섞임 → 편집과 관리 개념 혼재 | RecipeSharing.kt `RecipeManager` | 개념 모델 혼란 |
| P12 | 진행 상태가 화면마다 다른 텍스트 형식(`진행 n / m`, `레시피 저장 n / m`) | MainScreen.kt:234, RecipeScreen.kt:58,175 | 일관성 |

---

## 2. 카메라 동반 앱

| 앱 | 확인된 사실 | 출처 |
|---|---|---|
| Sony Creators' App | 계속 연결 유지 시 원격·전송에 즉시 접근 가능하나, 카메라가 켜져 있는 동안 폰이 다른 Wi‑Fi에 못 붙는다는 트레이드오프 지적. RAW 전송은 "Image Size for Importing" 옵션을 바꿔야 함 **[스니펫]** | https://www.dpreview.com/articles/sony-creators-app-tested-how-well-does-it-actually-work/ |
| Sony Imaging Edge Mobile | 사용자 불만: "80% of the time, it straight up does not connect… disconnect abruptly before transfer" **[스니펫]** | https://reddit.com/r/SonyAlpha/comments/1akhv1c/does_sony_imaging_edge_mobile_app_just_suck_for |
| Fujifilm XApp | 구 앱은 "unreliable connections, outdated user interface"로 평판이 나빴음. XApp은 첫 실행에 사진 라이브러리·위치 등 권한을 한꺼번에 요구 **[원문]** | https://www.cined.com/fujifilm-xapp-review-finally-a-good-camera-companion-app/ |
| Fujifilm XApp (App Store 리뷰) | "still not reconnecting Bluetooth while in the background … The only way to reconnect is to … bring the app to the foreground" **[원문]** | https://apps.apple.com/us/app/fujifilm-xapp/id1586089681?platform=ipad&see-all=reviews |
| Canon Camera Connect | 기능: Wi‑Fi로 이미지 전송·저장, 라이브뷰 원격 촬영, BT 페어링 상태에서 Wi‑Fi 전환, BT 원격 릴리즈 **[원문]**. 리뷰 제목 "Yet another app ruined with 'updates'" — 예전엔 Wi‑Fi 켜고 앱 열면 바로 됐다는 회고 **[원문]** | https://apps.apple.com/us/app/canon-camera-connect/id944097177 |
| Nikon SnapBridge | BT 연결은 일반 BT 기기 페어링만큼 단순하다는 평 **[스니펫]**. 위치 데이터가 안 붙는다는 Play 리뷰 **[스니펫]**. 배터리 소모 불만(포럼) **[스니펫]** | https://www.dpreview.com/articles/1864426516/nikons-snapbridge-app-tested-how-well-does-it-actually-work · https://snapbridge.nikon.com/googleplay.html · https://www.nikoncafe.com/threads/snapbridge-is-this-really-the-best-nikon-can-offer-us.351933/ |
| Ricoh Image Sync / GR World | "simple, reliable, does what I need"(Image Sync 옹호) vs 사진 ~2000장 이후 GR World 문제 언급, 사용자가 직접 대체 앱 제작 **[스니펫]** | https://reddit.com/r/ricohGR/comments/1ntly9r/since_the_gr_world_app_came_out_why_do_you_prefer · https://reddit.com/r/ricohGR/comments/1okmscr/yet_another_ricoh_gr_app |

**반복 불만 패턴**: ① 무선 연결 불안정·재연결 실패 ② 업데이트로 단순 흐름이 복잡해짐 ③ 권한 일괄 요구 ④ 대량 사진에서 성능 저하.
**우리 앱 함의**: USB 유선이 이미 장점 — "꽂으면 바로 새 사진" 한 동작을 최우선 노출. 연결 상태는 문장이 아닌 상태 칩+다음 행동 1개로. 권한은 쓰는 순간에만 요청(현재 구현 방식 유지).

## 3. 필름 레시피·보정 앱

| 앱 | 패턴 | 출처 |
|---|---|---|
| VSCO | 이미지를 길게 눌러 전/후 비교 **[스니펫]**. 프리셋은 짧은 코드명(A1 등)으로 식별 **[스니펫]** | https://support.vsco.co/en/articles/12698503-how-to-use-the-before-after-feature · https://support.vsco.co/en/articles/12698476-using-and-managing-presets |
| Lightroom Mobile | 적용된 프리셋 썸네일을 한 번 더 탭하면 Amount 슬라이더가 나타나 강도 조절 **[스니펫, 커뮤니티]**. 공식 도움말은 403으로 본문 **미확인** | https://www.reddit.com/r/Lightroom/comments/1c2vypu/is_it_possible_to_decrease_the_preset_intensity/ · https://helpx.adobe.com/lightroom/mobile/work-with-presets/apply-presets.html |
| Darkroom | "Edit one photo until it's right, copy its adjustments, then paste them onto the rest of your selection — and when you paste, you choose which adjustments carry over". 선택하면 하단에 batch actions bar 등장 **[원문]** | https://darkroom.co/help/manage/batch-actions |
| Snapseed | 도구·필터 도움말 존재, 세부 UI(전/후 버튼 등)는 본문 추출 실패로 **미확인** | https://support.google.com/snapseed/answer/6155517 |
| RNI Films | 실제 슬라이드·네거티브를 디지털화해 프로파일 제작했다는 설명 **[스니펫]** | https://apps.apple.com/us/app/rni-films/id1017098672 |
| Dazz Cam | 카메라 룩이 우하단에 모여 있고 탭하면 카메라 프리셋 목록 **[스니펫]** | https://www.hindustantimes.com/technology/why-i-use-this-iphone-camera-app-to-take-imperfect-photos-101769513513199.html |
| Fuji X Weekly | 400+ 레시피, 알파벳/시간순 정렬, 레시피마다 샘플 이미지와 호환 카메라 목록, 앱→카메라 레시피 전송 기능 **[원문]**. 필터·즐겨찾기는 유료(Patron) **[원문]** | https://fujixweekly.com/app/ · https://play.google.com/store/apps/details?hl=en&id=com.fujixweekly.FujiXWeekly |

**공통 패턴**: 프리셋=썸네일 가로 스트립, 선택 후 강도 1개 슬라이더, 길게 눌러 전/후, "한 장 맞추고 나머지에 붙여넣기", 레시피는 샘플 사진이 대표 이미지.

## 4. 플랫폼 가이드

| 주제 | 확인된 사실 | 출처 |
|---|---|---|
| 창 크기 클래스 | Compact < 600dp(세로 폰 99.96%), Medium 600–840dp("most large unfolded inner displays in portrait"), Expanded 840–1200dp **[원문]** | https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes |
| 표준 레이아웃 | list-detail(`ListDetailPaneScaffold`), supporting pane("work well on expanded-width displays … in landscape"), feed **[원문]** | https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts |
| 폴더블·테이블탑 | Material 3 Adaptive 라이브러리가 fold/hinge/posture API 제공, 힌지를 자연스러운 구분선으로 사용, tabletop = 힌지 수평·반 접힘 **[원문]** | https://developer.android.com/develop/ui/compose/layouts/adaptive/foldables/make-your-app-fold-aware |
| 동적 색상 | Android 12+에서 배경화면 기반 톤 팔레트 생성 **[원문]** | https://developer.android.com/develop/ui/views/theming/dynamic-colors |
| M3 Expressive | Compose가 M3 Expressive 지원, Android 16 시각 스타일과 맞춤 **[원문]** | https://developer.android.com/develop/ui/compose/designsystems/material3 |
| 터치 타깃 | 최소 48dp **[원문]** | https://developer.android.com/develop/ui/compose/accessibility/api-defaults |
| edge-to-edge | targetSdk 35+ & Android 15+에서 강제 **[원문]** | https://developer.android.com/develop/ui/views/layout/edge-to-edge |
| 예측형 뒤로가기 | 뒤로 스와이프 중 목적지 미리보기, 전체 화면 표면은 제스처 진행에 따라 축소 **[원문]** | https://developer.android.com/design/ui/mobile/guides/patterns/predictive-back |
| 아이콘 | Material Symbols = Apache License 2.0 **[원문]** | https://developers.google.com/fonts/docs/material_symbols |
| 사진 앱 다크 테마 관례 | 공식 문서로 "사진 앱은 다크가 표준"이라는 규정은 **미확인**. 위 앱들의 다크 UI 경향은 관찰 수준 | — |

## 5. 편집 UX 연구

- NN/g "Slider Design: Rules of Thumb": "If picking an exact value is important … choose an alternate UI element", 터치에선 손가락이 라벨을 가리므로 라벨을 슬라이더 아래에 두지 말 것 **[원문]** — https://www.nngroup.com/articles/gui-slider-controls/
- NN/g "Sliders, Knobs, and Matrices": 연속 파라미터는 Exploration(전 범위 빠른 탐색)과 Precision의 균형, 선형 슬라이더는 "precise value is less likely to be important"일 때 적합, 기본값 제공 **[원문]** — https://www.nngroup.com/articles/sliders-knobs/
- 비파괴 편집 관련 NN/g 문헌은 **미확인**. 우리 앱은 이미 원본 보존·결과 별도 저장(docs/M2-ENGINE.md).

---

## 6. 우리 앱에 적용할 원칙 (9개)

1. **한 화면 한 주 동작** — 각 화면에 FilledButton 1개(주 동작), 나머지는 보조. (P3; NN/g 위계, Canon 리뷰의 "단순했던 흐름" 회고)
2. **연결은 상태+다음 행동 하나** — 문장 대신 상태 칩(연결됨/권한 필요/MTP 설정 필요)과 해결 버튼 1개. (Sony/Fuji 연결 불만)
3. **새 사진 우선** — 카메라 연결 시 "새 사진 N장 가져오기"가 기본값, 전체/선택은 보조. (P1)
4. **사진이 주인공** — 다크 기본 표면, 미리보기는 남는 공간 전부. 크롬은 최소화. (P6, P10)
5. **레시피는 스트립 + 강도 하나** — LUT 썸네일 가로 스트립, 선택한 썸네일 재탭 시 강도 슬라이더. 세부 슬라이더는 접어둔 "세부 조정"으로. (Lightroom/VSCO 패턴, P7)
6. **한 장 맞추고 여러 장에** — 선택 → 레시피 적용 → 일괄 저장. 붙여넣을 항목 선택은 후순위. (Darkroom)
7. **창 크기에 맞춘 표준 레이아웃** — 접힘=단일 패널+하단 탭, 펼침=내비 레일+list-detail/supporting pane, 테이블탑=상단 미리보기·하단 컨트롤. (Android 가이드)
8. **오류는 회복 동작과 함께** — 오류 배너에 원인 1줄 + 버튼(재시도/설정 열기/로그 공유). 진행은 하나의 공용 진행 바 형식. (P4, P12)
9. **접근성 기본값** — 48dp 타깃, Material Symbols 아이콘+라벨, 슬라이더 값은 손가락에 가리지 않는 위치에 숫자 표시, 숨은 제스처(더블탭 초기화)에는 보이는 버튼 병행. (P9, NN/g)
