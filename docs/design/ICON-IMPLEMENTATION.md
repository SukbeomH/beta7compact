# β7C Adaptive Icon 적용 기록

2026-11-17. 기존 앱의 카메라/사진 기능은 변경하지 않고 런처 아이콘 리소스만 교체했다.

## 원본과 적용 자산

- 원본: `icon-source/beta7c-reference.png`, 첨부 ZIP의 09번 시안을 그대로 보존.
- 원본 SHA-256: `d76705b700ca2518a9c7504617108027f2faac0543c504dc892d049135c7073e`.
- 적용 전경: `app/src/main/res/drawable-nodpi/beta7c_foreground.png`.
- 전경 SHA-256: `9bbdc895fbd5a92f9cfea45bdec6d31e4dc3a72ac971bdcb71c6828c6587ae86`.
- 두 파일 모두 1254 × 1254 RGBA PNG이며 알파 범위는 0–255.
- 흰색 배경은 `launcher_background` 색상 리소스로 분리했다.
- 기존 `ic_camera.xml`은 유지했다. 앱 ID·이름·내부 UI 아이콘은 변경하지 않았다.

내장 imagegen으로 원본의 카메라, β7C 표기, 주황 렌즈 링과 코드 칩을 유지하면서 회색 바디의 체크 무늬를 정리하고 투명 여백을 늘렸다. 생성 결과는 프로젝트에 복사했으며 원본은 덮어쓰지 않았다.

## 구성

`ic_launcher.xml`과 `ic_launcher_round.xml`은 같은 adaptive icon을 참조한다. 전경은 8dp inset과 `gravity=fill`로 배치한다. 최소 지원 Android 버전이 29이므로 API 26 이전용 밀도별 아이콘은 추가하지 않았다. 테마 아이콘용 monochrome 레이어는 이번 작업에서 제공하지 않는다.

## 확인한 범위

- PNG를 직접 열어 β7C, 카메라, 흰색 테두리, 코드 칩을 확인했다.
- Pillow로 치수·알파·경계·해시를 검사했다. 이미지 편집에는 Pillow를 사용하지 않았다.
- 108dp 전경에서 8dp inset을 적용했을 때 알파 16 이상인 모든 픽셀은 중심 반경 28.4dp 이내에 있다. 중심 66dp 원 안에 주요 그림이 들어간다.
- 알파 1–15의 매우 옅은 생성 잔여 픽셀은 반경 43.4dp까지 있다. 모든 비투명 픽셀이 안전 원 안에 든다고 주장하지 않는다. 전체 알파 경계는 `(89, 224, 1015, 1116)`이다.
- XML 파싱 및 참조 대상 존재를 검사했다.

빌드·에뮬레이터·실제 런처 표시 검증은 이 아이콘 작업에서 실행하지 않았다. 통합 빌드 결과는 프로젝트 전체 검증 보고서를 따른다. 작은 런처 크기에서 β7C 글자가 얼마나 읽히는지, 실제 원형·스쿼클·둥근 사각형 마스크 표시와 미세 알파 잔여물은 실기 확인 항목이다.

## 생성 프롬프트

Built-in imagegen, transparent_background=true, 원본 PNG를 편집 대상으로 전달:

> Edit this reference into a production Android adaptive icon foreground PNG, actual transparent alpha background. Preserve pixel camera appearance, orange lens ring, purple blue lens reflections, exact Greek lowercase beta text β7C and small </> chip. Clean up ragged outline and fake checker texture; camera body should be gray/black pixels. Preserve white pixel rounded square border with a very thin dark outline so visible on white. Everything outside and inside border except camera and chip must be transparent (no black background). Entire border and camera/chip grouped in centered square occupying only 42% of full square canvas width and height, generous fully transparent margin on all sides, for circular Android adaptive icon safe zone. No presentation labels, no shadows, no new text.

## 0.5.2: 프레임 제거·확대 (2026-11-17)

사용자가 기존 런처 표시를 확인한 뒤 외곽 테두리 제거와 카메라 확대를 요청했다. 위 설명·해시는 초기 0.5.0 자산 이력이다. 0.5.2는 같은 PNG 경로를 새 자산으로 교체하고 inset을 8dp에서 4dp로 줄였다. β7C 표기, 주황색 렌즈 링, 파랑·보라 반사와 코드 칩을 유지했다. 외곽 프레임은 없다.

내장 imagegen 편집 프롬프트: 기존 전경의 흰색 둥근 사각형 프레임 전체를 제거하고 카메라와 오른쪽 아래 코드 칩만 유지. 정확한 β7C 문자와 </> 기호, 회색·검정 픽셀 스타일을 보존. 카메라·칩을 중앙에 확대하고 실제 투명 RGBA 배경 사용. 크롭·새 프레임·체커보드·그림자 금지.

시각 확인 및 Pillow 읽기 전용 측정: 1254×1254 RGBA, alpha 0..255. 4dp inset(100dp 전경)에서 alpha≥16 픽셀 최대 중심 반경 약31.26dp로 33dp 안전 원 안에 들어간다. 아주 옅은 alpha1..15 잔여물은 밖에 있으므로 모든 비투명 픽셀의 안전 영역 준수를 주장하지 않는다. 원형을 포함해 안전 원을 포함하는 기본 마스크에서 주요 그림은 잘리지 않는 배치다. 실제 런처 소형 가독성과 OEM 마스크 검증은 미실행이다. 이미지 편집은 imagegen만 사용했고 Pillow로 이미지를 변경하지 않았다.
