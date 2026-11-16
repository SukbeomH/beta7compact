# D1 디자인 시스템 제안

Material 3(Compose `material3` 1.3.1, 이미 의존성에 있음) 위에 얹는 최소 토큰·컴포넌트. 새 라이브러리 없이 구현 가능한 범위로 한정. 단, 아이콘·적응형 스캐폴드는 PLAN.md 결정 Q3 참고.

## 1. 색

- **다크 기본**: 사진 색 판단을 방해하지 않도록 중립 회색 표면. 시스템이 라이트여도 편집기·미리보기 영역은 항상 다크(결정 Q1).
- **동적 색상: 크롬에만 선택 적용 안 함(권장)** — 근거: 동적 색상은 배경화면에서 색조를 뽑아 표면 톤까지 물들인다(Android 12+, [원문](https://developer.android.com/develop/ui/views/theming/dynamic-colors)). 색 보정 앱에서 표면에 색조가 끼면 판단을 왜곡하므로 **표면은 중립 고정, 강조색만** 고정 브랜드색 사용.

| 토큰 | 값 | 용도 |
|---|---|---|
| `surface` | #121212 | 앱 배경 |
| `surfaceContainer` | #1E1E1E | 카드, 시트 |
| `surfaceContainerHigh` | #2A2A2A | 선택 칩, 앱바 스크롤 시 |
| `outline` | #3D3D3D | 구분선, 비선택 테두리 |
| `onSurface` | #E8E8E8 | 본문(대비 #121212 대비 약 15:1) |
| `onSurfaceVariant` | #A8A8A8 | 보조 텍스트(약 8:1) |
| `primary` | #8FD0B8 (기존 #315E51의 밝은 톤) | 주 버튼, 선택 테두리, 슬라이더 |
| `onPrimary` | #00382B | 주 버튼 텍스트 |
| `error` | #FFB4AB / `errorContainer` #93000A | 오류 배너 |
| `warning`(커스텀) | #F2C46B | RAW·LUT 누락 배지 |
| 미리보기 매트 | #000000 | 사진 뒤 배경(테두리 없이) |

대비 수치는 sRGB 상대 휘도로 계산한 근사치이며 구현 시 검증 필요.

## 2. 타이포 (M3 기본 스케일 + 한글)

| 역할 | M3 스타일 | 크기 | 용도 |
|---|---|---|---|
| 화면 제목 | titleLarge | 22sp | 앱바 |
| 섹션 | titleSmall | 14sp / Medium | 카드 제목, 탭 |
| 본문 | bodyMedium | 14sp | 설명 |
| 값 | labelLarge + tabular figures | 14sp | 슬라이더 숫자, `n/m` |
| 배지 | labelSmall | 11sp | RAW, 쌍, 편집됨 |

폰트는 시스템 기본(Galaxy의 경우 One UI 폰트) 유지 — 번들 폰트 추가 없음.

## 3. 간격·형태

- 4dp 그리드: 4 / 8 / 12 / 16 / 24.  화면 여백 16dp(접힘), 24dp(펼침).
- 모서리: 카드 12dp, 썸네일 8dp, 사진 그리드 셀 2dp(사진 최대 노출), 시트 상단 28dp.
- 터치 타깃 ≥ 48dp(아이콘 버튼 48, 칩 높이 32 + 상하 패딩).

## 4. 컴포넌트

| 컴포넌트 | 명세 |
|---|---|
| **연결 상태 칩** | 아이콘 + 1단어(연결됨/연결 중/권한 필요/MTP 필요/미연결). 색: 연결됨 primary, 문제 warning. 칩 옆 해결 버튼 1개 |
| **사진 그리드** | 정사각 셀, 간격 2dp, 배지 오버레이(좌하: RAW/쌍, 우상: 선택 체크 원 24dp, 우하: 편집됨 점). 선택 모드는 길게 누르기 진입 + 앱바 `선택` 버튼 병행 |
| **LUT 스트립** | 72×72dp 썸네일 + 2줄 이름, 선택 시 2dp primary 테두리 + 체크. 같은 썸네일 재탭 = 강도 슬라이더 포커스. 첫 항목은 "원본" |
| **강도 슬라이더** | 0–100% 표기, 값 숫자는 슬라이더 **위 오른쪽**(손가락에 가리지 않게, NN/g), 기본값 100 눈금, 초기화 아이콘 버튼 |
| **세부 슬라이더 행** | 라벨 · 값 · 초기화 아이콘 한 줄 + 슬라이더. 중앙 0 값은 중앙 눈금 |
| **전/후 비교** | 미리보기 길게 누르기 = 원본(현재 유지) + 앱바 `compare` 아이콘 토글(접근성용). 펼침에서는 분할 슬라이더 옵션(후순위) |
| **작업 배너** | 높이 64dp, 아이콘 · 제목 · `n/m` · LinearProgressIndicator · 텍스트 버튼(취소/재개). 화면 상단 고정, 여러 작업은 스택 최대 2 |
| **오류 배너** | errorContainer 배경, 원인 1줄 + 버튼 최대 2개, 닫기 아이콘. 일시 오류는 스낵바, 지속 오류는 배너 |
| **빈 상태** | 아이콘 48dp + 제목 1줄 + 설명 1–2줄 + 버튼 1개 |
| **하단 동작 바** | 선택 모드·가져오기 선택에서 고정, 주 버튼 FilledButton 전폭 |

## 5. 모션

- 기본 M3 easing(emphasized) 사용, 지속시간 200–300ms. 편집 미리보기 갱신은 크로스페이드 150ms(깜빡임 방지).
- 예측형 뒤로가기: 편집기 → 사진 복귀 시 표면 축소 미리보기(가이드 [원문](https://developer.android.com/design/ui/mobile/guides/patterns/predictive-back)).
- 시스템 "애니메이션 제거" 설정 존중.

## 6. 아이콘

- **Material Symbols (Rounded, weight 400)** — Apache License 2.0 ([원문](https://developers.google.com/fonts/docs/material_symbols)). 설정 › 정보에 고지.
- 도입 방식: 필요한 아이콘만 벡터 드로어블(XML)로 복사(라이브러리 추가 없음, 결정 Q3 권장안).
- 사용 목록(예정): `photo_camera, photo_library, palette, settings, usb, download, check_circle, compare, undo, redo, tune, star, share, qr_code_2, error, refresh, close, raw_on, restart_alt`.

### 2026-11-16 픽셀 아이콘 전환

- **UI 아이콘 15종은 자체 제작 픽셀 아이콘**(`res/drawable/pixel_*.xml`)으로 바꿨다: 내비 탭 4종(카메라·사진·레시피·설정)과 뒤로, 다시 스캔, 초기화, USB, 선택됨, 세부 조정, 원본 보기, 실행 취소, 다시 실행, 즐겨찾기, 공유. 18 × 18 격자, 선 굵기 2칸, **표시 크기 18dp**(한 칸 1dp, `BetaTokens.pixelIcon`). 터치 타깃은 48dp 그대로.
- Material Symbols는 `download, qr_code_2, error, close, raw_on` 5종만 남는다(24dp). 고지 문서의 사용 목록도 이 5종이다.
- **필름 아이콘**(`ui/components/FilmIcon.kt`): 실제 필름을 본뜬 내장 LUT 22종은 색 차트 썸네일 대신 32 × 32 픽셀 파트로네에 이름과 ISO를 3 × 5 픽셀 글자로 넣어 그린다. 크리에이티브·사용자 LUT는 기존 썸네일을 유지한다. 제조사 로고나 서체는 쓰지 않는다.
- **레시피 카드**: 적용 사진이 없으면 LUT의 필름 아이콘을, 필름이 없는 LUT나 LUT 미연결이면 무채색 파트로네를 보인다. LUT 선택 외에 조정이 있는 레시피(`isAdjusted`)는 썸네일 오른쪽 아래에 세부 조정 배지를 겹친다.

## 7. edge-to-edge

targetSdk 35+ 기기에서 강제([원문](https://developer.android.com/develop/ui/views/layout/edge-to-edge)). 사진 미리보기는 상태바 아래까지, 컨트롤은 `WindowInsets.safeDrawing` 패딩.
