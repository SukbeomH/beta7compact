# 0.8.3 편집·가로2열·작업 버튼 배포 검증

2026-11-17. versionName0.8.3 / versionCode16.

## 수정 파일

- `ui/RecipeScreen.kt`: Scaffold 기반 고정 저장 작업, 조정 영역 스크롤, 미리보기 남은 공간 배분, 가로2열, 테이블탑 중앙 경계 보완, 상태 변경 알림, 저장 바 배경/여백.
- `ui/PhotosScreen.kt`, `ui/MainScreen.kt`: 사진 탭 제목을 목록 열로 이동, 오른쪽 패널 상단 공간 활용, 적용 버튼 형태·우측 정렬·하단 여백. 다중 선택 전체 폭 유지.
- `ui/CameraScreen.kt`: 실험/예정 기능 문구, 가져오기 버튼 하단 여백.
- `ui/UiPolicy.kt`, `EditorLayoutPolicyTest.kt`: 짧은 창·큰 글꼴·가용 본문 공간의 미리보기 배분 검사5개.
- `UIUX_DESIGN.md`, `docs/UIUX_AUDIT.md`, `docs/UIUX_OPEN_SOURCE_REFERENCES.md`, `docs/EDITOR_STRUCTURE_STAGE1.md`: 공식/오픈소스 근거·설계·실제 검증과 한계.
- README·CHANGELOG·앱 버전 및 빌드/서명 기록.

## 실행 명령

```sh
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew assembleDebug test lint --continue --no-daemon
python3 tools/check_m1_migration.py
python3 tools/check_m2_migration.py
python3 tools/verify_release_apk.py dist/beta7compact-0.8.3.apk \
  --apksigner .tools/android-sdk/build-tools/35.0.0/apksigner
```

[배포 빌드 로그](release-0.8.3-build.log). 세부 중간 검증은 [1단계 기록](../../EDITOR_STRUCTURE_STAGE1.md)에 있다.

## 실기 및 한계

이전 개발 빌드를 연결된 SM-F971N에 업데이트 설치해 펼친 가로·세로, 저장 버튼 고정·조정 스크롤, 글꼴200%의 버튼 표시, 원본 비교 토글·레시피 저장 창 열기/닫기·사진 포커스 변경·다중 선택 전환을 확인했다. 별도 배경 제거와 여백 수정 후에도 개발 빌드 설치 성공을 확인했다. 글꼴0.9와 자동 회전free를 원상 복구했다. 개인 사진이 포함된 캡처/원시 UI계층은 Git에 추가하지 않았다.

사진/레시피 실제 저장·cube 입출력·TalkBack·물리 힌지·매우 짧은 분할 창·모든 테마/크기 조합은 미검증이다. 카메라 미연결로 CameraPicker 실제 버튼·USB 목록/캐시·AF·촬영·카드 저장은 실행하지 않았다. 새 레이아웃 단위 테스트는 헤더/푸터를 제외한 본문 높이를 검사하므로 전체 접근성 인증이 아니다.

이번0.8.3 배포 APK 자체는 별도 폰 설치하지 않는다. 개발 빌드 실기 관찰과 최종 버전 빌드/테스트/서명 검증을 구분한다.

## 최종 결과

- assembleDebug/test/lint 성공. Debug/Release 각각331개, 실패·오류·skip0.
- lint 오류0, 경고18개. 마이그레이션 검사2개와 diff check 통과.
- 패키지 dev.sukbeomh.beta7compact, 버전0.8.3(16).
- 기존 인증서 SHA-256 eaf9514a8a00e3d029e98e772cf0185bfafd270645cb21bc057f198a2d9e23f8 일치.
- APK SHA-256 `d67e7a07db91de061d110e018a8c43a944096a28ce61d2dc194136a08b387278`.
