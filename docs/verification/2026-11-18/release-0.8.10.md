# 0.8.10 BLE·원격·Wi-Fi 진단 배포 검증

2026-11-18. versionName `0.8.10`, versionCode `23`. 0.8.9 이후 반영된 BLE 위치 연동, USB 원격 실험의 제어 우선순위 처리, Wi-Fi PTP/IP 연결 진단과 QR 텍스트 입력을 배포한다. 기능별 실기 절차와 남은 과제는 [인계 문서](../../HANDOFF.md)를 따른다.

## 실행한 검증

- 클린 `assembleDebug test lint` 성공. Debug와 Release 단위 테스트 각각 45개 스위트, 385개 테스트 통과. 실패·오류·건너뜀 0.
- lint 오류 0, 경고 7. Room 마이그레이션 v1/2/3→v4 검사와 Git 공백 검사 통과.
- APK 패키지 `dev.sukbeomh.beta7compact`, 최소 API 29, 대상 API 35, versionCode `23`, versionName `0.8.10` 확인.
- APK 서명 검증 통과. 인증서 SHA-256 `eaf9514a8a00e3d029e98e772cf0185bfafd270645cb21bc057f198a2d9e23f8`으로 기존 설치본과 일치.
- 연결된 Samsung SM-F971N에 설치된 0.8.9의 서명을 먼저 확인한 뒤 `adb install -r`로 업데이트했다. 설치된 패키지는 0.8.10, versionCode 23이며 앱 프로세스와 주 Activity 실행을 확인했다. 설치된 APK의 해시와 서명은 배포 파일과 일치했다.
- APK SHA-256 `105224fc6cac984ce52aec19c9d99a24cd98aa577da0789bb5e4d5e7a1cebe24`.

```sh
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew clean assembleDebug test lint --continue --no-daemon --offline
python3 tools/check_m1_migration.py
python3 tools/check_m2_migration.py
python3 tools/verify_release_apk.py dist/beta7compact-0.8.10.apk --apksigner .tools/android-sdk/build-tools/35.0.0/apksigner
.tools/android-sdk/build-tools/35.0.0/aapt dump badging dist/beta7compact-0.8.10.apk
shasum -a 256 dist/beta7compact-0.8.10.apk
.tools/android-sdk/platform-tools/adb install -r dist/beta7compact-0.8.10.apk
git diff --check
```

[클린 빌드 로그](release-0.8.10-build.log). 로그의 후행 공백만 제거했다. 배포 APK는 기존 키로 서명한 `assembleDebug` 산출물이다. Release 단위 테스트는 별도로 실행했으나 release variant APK를 생성한 것은 아니다.

## 미실행 검증

실제 α7C와 BLE 페어링·위치 전송, USB PC Remote·AF·단발 촬영, 카메라 Wi-Fi 연결 진단은 실행하지 않았다. 앱 프로세스와 Activity 실행 확인은 화면의 모든 기능이나 라이브 카메라 호환성 검증을 의미하지 않는다. 제어 우선순위 쓰기와 되돌림, BLE 서비스 특성은 카메라 실기 확인이 필요하다.

[GitHub 릴리즈](https://github.com/SukbeomH/beta7compact/releases/tag/v0.8.10) · [APK](https://github.com/SukbeomH/beta7compact/releases/download/v0.8.10/beta7compact-0.8.10.apk)
