# 0.8.11 Wi-Fi 사진 가져오기 배포 검증

2026-11-19. versionName `0.8.11`, versionCode `24`. Wi-Fi를 통한 카메라 사진 가져오기, Wi-Fi 원격 관찰·촬영 실험과 설정 화면 변경을 포함한다. 가져온 설정 변경에서 빠진 BLE 위치 연동, 로컬 파일 분석, PC Remote 진단·촬영·Wi-Fi 진단의 진입점은 접고 펼치는 **위치 연동 · 카메라 실험** 영역에 복구했다.

## 실행한 검증

- 클린 `assembleDebug test lint` 성공. Debug와 Release 단위 테스트 각각 46개 스위트, 396개 테스트 통과. 실패·오류·건너뜀 0.
- lint 오류 0, 경고 7. Room 마이그레이션 v1/2/3→v4 검사와 Git 공백 검사 통과.
- APK 패키지 `dev.sukbeomh.beta7compact`, 최소 API 29, 대상 API 35, versionCode `24`, versionName `0.8.11` 확인.
- APK 서명 검증 통과. 인증서 SHA-256 `eaf9514a8a00e3d029e98e772cf0185bfafd270645cb21bc057f198a2d9e23f8`으로 기존 설치본과 일치.
- 연결된 Samsung SM-F971N의 0.8.10 서명을 확인한 뒤 `adb install -r`로 업데이트했다. 설치된 패키지는 0.8.11, versionCode 24이고 설치된 APK의 해시와 서명은 배포 파일과 일치한다.
- 폰 화면의 **카메라 → Wi-Fi로 사진 가져오기** 버튼과 대화상자, 목록 읽기·주소 직접 입력 표시를 확인했다. 설정의 접힌 영역을 펼친 뒤 BLE 위치 연동, 펌웨어 파일 분석, PC Remote 진단, AF·단발 촬영, Wi-Fi 연결 진단 진입점이 표시되는 것을 확인했다. 카메라 연결과 목록 읽기는 실행하지 않았다.
- APK SHA-256 `1f9b275938a5fefc2b3dc1962257de3a7606a2d67057c30b74f6db81f71465bf`.

```sh
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew clean assembleDebug test lint --continue --no-daemon --offline
python3 tools/check_m1_migration.py
python3 tools/check_m2_migration.py
python3 tools/verify_release_apk.py dist/beta7compact-0.8.11.apk --apksigner .tools/android-sdk/build-tools/35.0.0/apksigner
.tools/android-sdk/build-tools/35.0.0/aapt dump badging dist/beta7compact-0.8.11.apk
shasum -a 256 dist/beta7compact-0.8.11.apk
.tools/android-sdk/platform-tools/adb install -r dist/beta7compact-0.8.11.apk
git diff --check
```

[클린 빌드 로그](release-0.8.11-build.log). 로그의 후행 공백만 제거했다. 배포 APK는 기존 키로 서명한 `assembleDebug` 산출물이다. Release 단위 테스트는 실행했으나 release variant APK를 생성하지 않았다.

## 미실행 검증

실제 α7C의 Wi-Fi 목록·섬네일·사진 다운로드, RAW의 카메라 생성 JPEG 변환, Wi-Fi 원격 관찰·AF·단발 촬영, BLE 위치 전송 및 USB 원격 촬영은 실행하지 않았다. 다른 모델의 호환성도 확인하지 않았다. 폰 화면 진입점과 모의 통신 결과는 카메라 기능 성공을 뜻하지 않는다.

[GitHub 릴리즈](https://github.com/SukbeomH/beta7compact/releases/tag/v0.8.11) · [APK](https://github.com/SukbeomH/beta7compact/releases/download/v0.8.11/beta7compact-0.8.11.apk)
