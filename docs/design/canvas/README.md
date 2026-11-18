# 화면 시안 캔버스

앱 0.8.8 코드(차콜 민트 테마)를 기준으로 그린 화면 시안의 원본입니다. Claude 디자인 캔버스에서 내보낸 파일이며, 사진 자리는 검은 상자와 확장자 글자로 대신합니다. 실기 사진·기기 정보는 들어 있지 않습니다.

화면을 브라우저에서 전환하고 테마와 배치를 비교하려면 [β7C Screen Studio 사용법](../../../sites/beta7c-screen-studio/README.md)을 참고하세요. 웹 시안은 별도로 만든 0.8.8 기반 재현이며 이 캔버스 파일을 실행하지 않습니다.

- `canvas.json`: 화면 목록과 캔버스 위 배치(`x`, `y`, `w`, `h`), 행 제목 메모
- `*.dc.html`: 화면 하나당 파일 하나. 캔버스의 런타임(`support.js`)이 있어야 그려지므로 브라우저에서 바로 열면 내용이 보이지 않습니다. 마크업과 인라인 스타일은 그대로 읽을 수 있습니다.

| 구분 | 화면 |
| --- | --- |
| 접힘 412 × 915dp | [카메라 연결됨](Main.dc.html) · [미연결](Camera-Disconnected.dc.html) · [사진 고르기](Camera-Pick.dc.html) · [사진](Photos.dc.html) · [선택 모드](Photos-Select.dc.html) · [더보기 메뉴](Photos-More.dc.html) · [사진 상세](Photo-Detail.dc.html) · [편집기](Editor.dc.html) · [세부 조정](Editor-Adjust.dc.html) · [톤커브](Editor-Curve.dc.html) · [왜곡](Editor-Distortion.dc.html) · [레시피](Recipes.dc.html) · [설정](Settings.dc.html) |
| 펼침 가로 1104 × 884dp | [카메라](Wide-Camera.dc.html) · [사진](Wide-Photos.dc.html) · [편집기](Wide-Editor.dc.html) · [레시피](Wide-Recipes.dc.html) |
| 펼침 세로 884 × 1104dp | [편집기](Tall-Editor.dc.html) · [사진 상세](Tall-Photo-Detail.dc.html) |
| 테이블탑 1104 × 884dp | [편집기](Tabletop-Editor.dc.html) |
| 아이콘 시안 | [필름](Film-Icons.dc.html) · [필름(ISO·이름)](Film-Icons-Text.dc.html) · [픽셀 UI 아이콘](Pixel-Icons.dc.html) |

화면은 코드와 대조해 그렸고 실기 화면과 픽셀 단위로 맞춘 것은 아닙니다. 설정 화면의 높이는 추정치입니다. 이전 SVG 와이어프레임은 [wireframes](../wireframes/)와 [wireframes-32](../wireframes-32/)에 남아 있습니다.
