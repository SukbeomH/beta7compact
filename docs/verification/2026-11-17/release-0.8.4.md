# 0.8.4 사진 선택 작업 배포 검증

2026-11-17. versionName0.8.4 / versionCode17.

## 수정 파일

- `ui/PhotosScreen.kt`: 선택 수/필터 밖 수·종료·공유·적용의 고정 작업줄. 저장 재개 더보기 이동, 선택 중 뒤로가기 종료.
- `ui/PhotoSelectionPolicy.kt`: 유효한 선택 중복 제거·순서 보존·삭제 항목 제외·필터 밖 집계.
- `PhotoSelectionPolicyTest.kt`:0장·삭제·필터 변화·중복/순서4개 회귀 테스트.
- `docs/PHOTO_SELECTION_STAGE2.md`, `docs/UIUX_AUDIT.md`: 병렬 구현/독립 검토·실기 결과·한계.
- 버전·README·CHANGELOG 및 빌드/검증 기록.

## 실행 명령

```sh
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew assembleDebug test lint --continue --no-daemon
python3 tools/check_m1_migration.py
python3 tools/check_m2_migration.py
python3 tools/verify_release_apk.py dist/beta7compact-0.8.4.apk \
  --apksigner .tools/android-sdk/build-tools/35.0.0/apksigner
```

[전체 빌드 로그](release-0.8.4-build.log). [단계별 실기 기록](../../PHOTO_SELECTION_STAGE2.md).

## 실행한 실기와 미실행 범위

이전 개발 빌드를 SM-F971N에 업데이트 설치해0장/2장 선택, 필터 밖 선택 유지·빈 필터, 공유 선택창 이미지2개 전달/취소, 더보기의 재개 항목,2장 편집 진입과 뒤로가기 선택 종료, 펼친 가로 글꼴200%의 버튼 표시를 확인했다. 글꼴0.9·회전free로 복구했다. 개인 사진과 공유 선택창의 연락처·원시 UI계층은 Git에 추가하지 않았다.

최종0.8.4 APK의 폰 설치·실제 공유 전송·JPEG/레시피 저장·저장 재개 작업·TalkBack·세로200%·접힌 폰·짧은 분할 창·전체 테마 및 실제 카메라 USB/AF/촬영은 이번 배포 검증에서 실행하지 않았다. 이전 개발 빌드 관찰과 최종 버전의 빌드·테스트·서명 검증을 구분한다.

## 최종 결과

- assembleDebug/test/lint 성공,1분37초. Debug/Release 각각335개, 실패·오류·skip0.
- lint 오류0, 경고18개. 마이그레이션 검사2개 및 diff check 통과.
- 패키지 dev.sukbeomh.beta7compact, 버전0.8.4(17).
- 기존 인증서 SHA-256 eaf9514a8a00e3d029e98e772cf0185bfafd270645cb21bc057f198a2d9e23f8 일치.
- APK SHA-256 `73183e1f44af456a6fceb87b5878c67213d090d95e184210d58c90d17a1d374e`.
