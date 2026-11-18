# 0.8.9 Sony 기종 경고와 카메라 호환성 조사 배포 검증

2026-11-18. versionName `0.8.9`, versionCode `22`. Sony의 다른 기종에서 미검증 경고와 실행 확인 후 PC Remote 실험을 시도할 수 있도록 모델명 제한을 완화했다. Sony 제조사와 필수 명령 검사, 촬영 조건 검사와 종료 처리는 유지했다. Sony 외 기종은 [공식 자료에 근거한 검증 후보](../../CAMERA_MODEL_CANDIDATES.md)로 정리했으며 연결 기능은 추가하지 않았다.

## 검증 결과

- 클린 `assembleDebug test lint` 성공. Debug와 Release 단위 테스트 각각 43개 스위트, 357개 테스트 통과. 실패·오류·건너뜀 0.
- Sony α7C II 모의 응답을 이용한 원격 세션 테스트는 모델 제한 변경 전 실패했고 변경 후 통과했다. 다른 Sony 모델에서 촬영 조건이 맞지 않을 때 버튼 명령을 보내지 않는 모의 테스트도 통과했다. 모의 응답은 실기 호환성의 근거가 아니다.
- lint 오류 0, 경고 7. Room 마이그레이션 v1/2/3→v4 검사와 Git 공백 검사 통과.
- 패키지 `dev.sukbeomh.beta7compact`, 최소 API 29, 대상 API 35, versionCode `22`, versionName `0.8.9` 확인.
- APK v2 서명 검증 통과. 인증서 SHA-256 `eaf9514a8a00e3d029e98e772cf0185bfafd270645cb21bc057f198a2d9e23f8`으로 기존 설치본과 일치.
- APK SHA-256 `68ada4d5bf217551b172b534d88b9ba84dd5bc6da8336e8c03d85cc39870f54e`.

```sh
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew clean assembleDebug test lint --continue --no-daemon --offline
python3 tools/check_m1_migration.py
python3 tools/check_m2_migration.py
python3 tools/verify_release_apk.py dist/beta7compact-0.8.9.apk --apksigner .tools/android-sdk/build-tools/35.0.0/apksigner
.tools/android-sdk/build-tools/35.0.0/aapt dump badging dist/beta7compact-0.8.9.apk
shasum -a 256 dist/beta7compact-0.8.9.apk
git diff --check
```

[클린 빌드 로그](release-0.8.9-build.log). 로그의 후행 공백만 제거했다. 배포 APK는 기존 키로 서명한 `assembleDebug` 산출물이다. Release 단위 테스트는 별도로 실행했으나 release variant APK를 생성한 것은 아니다.

## 미실행 검증

사용자 요청에 따라 실제 카메라 및 폰 연결, 카메라 명령 전송, AF·촬영과 촬영 사진 저장, 다른 기종의 MTP/PTP 전송 및 업데이트 설치는 실행하지 않았다. 기존 원형 α7C를 포함한 PC Remote와 촬영은 실기 검증 전이다. 다른 모델에서는 기존 Sony 프로토콜과 촬영 조건이 호환되지 않을 수 있다. 원격 세션을 시작하면 카메라 모드가 바뀔 수 있다.

[GitHub 릴리즈](https://github.com/SukbeomH/beta7compact/releases/tag/v0.8.9) · [APK](https://github.com/SukbeomH/beta7compact/releases/download/v0.8.9/beta7compact-0.8.9.apk)
