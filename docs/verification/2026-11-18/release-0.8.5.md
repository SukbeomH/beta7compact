# 0.8.5 수동 국소 보정 실험 배포 검증

2026-11-18. versionName0.8.5 / versionCode18.

## 수정 파일

- `perspective/LocalWarp.kt`: 설정·픽셀 예산 검증, 국소 역매핑·bilinear 보간, 행 단위 취소.
- `perspective/LocalWarpPhotoIO.kt`, `LocalWarpInput.kt`, `LocalWarpOutput.kt`: 입력 snapshot·크기/메모리 제한·sRGB JPEG/방향 처리·pending 출력·취소/실패 정리.
- `perspective/LocalWarpExperiment.kt`, `ui/SettingsScreen.kt`: 별도 실험 진입, JPEG 선택·영역 직접 지정·슬라이더·원본 비교·초기화·별도 저장 확인·취소.
- `perspective/LocalWarpTest.kt`, `LocalWarpInputTest.kt`, `LocalWarpOutputTest.kt`: 합성 픽셀8·입력복사4·출력/샘플링6개 테스트.
- README·CHANGELOG·버전 및 [실험 사용/평가/한계](../../LOCAL_WARP_EXPERIMENT.md), [사전 조사](../../PERSPECTIVE_FEASIBILITY_REVIEW.md).

## 실행 명령

```sh
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew assembleDebug test lint --continue --no-daemon
python3 tools/check_m1_migration.py
python3 tools/check_m2_migration.py
python3 tools/verify_release_apk.py dist/beta7compact-0.8.5.apk \
  --apksigner .tools/android-sdk/build-tools/35.0.0/apksigner
```

[전체 빌드 로그](release-0.8.5-build.log). 기능 통합 중 최초 컴파일 오류와 취소 테스트의 잘못된 기대 수정 이력은 [실험 문서](../../LOCAL_WARP_EXPERIMENT.md)에 보존했다.

## 미실행 검증과 남은 문제

연결 기기가 없어 Android 파일 선택창·Bitmap/EXIF 방향8종·MediaStore 실제 저장·취소·입력 복사/교체·폰 UI·전체 테마/글꼴/창 크기·TalkBack·실제 메모리 최대값/속도는 실행하지 않았다. 실제 인물 사진 품질 평가는 사용자 후속 진행 대상이다. 합성/모의 테스트가 실제 JPEG 입출력 성공이나 인물 개선을 입증하지 않는다. 기존 카메라 USB/AF/촬영도 이번에 실행하지 않았다.

보정은 수동2D 근사이며 배경/얼굴을 부자연스럽게 만들 수 있다. 출력은 최대2048px/sRGB/JPEG 재압축, 메타데이터 복사 없음, 기존 결과 DB 미연결이다. 임시 입력 파일24시간 초과 정리는 다른 창의 활성 사용을 추적하지 않으며 시스템 캐시 삭제 시 재선택이 필요할 수 있다. 네이티브 인코딩/디코딩 즉시 취소와 고장 난 provider에서의 정리 성공은 보장하지 않는다.

## 최종 결과

- assembleDebug/test/lint 성공,1분37초. Debug/Release 각각353개, 실패·오류·skip0.
- lint 오류0, 경고19개. 신규 플랫폼 ExifInterface 사용 권장 경고는 [실험 기록](../../LOCAL_WARP_EXPERIMENT.md)에 명시했다.
- 마이그레이션 검사2개와 diff check 통과.
- 패키지 dev.sukbeomh.beta7compact, 버전0.8.5(18).
- 기존 인증서 SHA-256 eaf9514a8a00e3d029e98e772cf0185bfafd270645cb21bc057f198a2d9e23f8 일치.
- APK SHA-256 `ecb604f38a7111c84a4da6d9fa60bd95e3cacdf3fe47c8920d111d1538183a51`.
