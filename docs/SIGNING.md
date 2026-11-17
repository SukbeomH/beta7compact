# 업데이트 서명키 관리

기존 0.4.0 설치본의 인증서 SHA-256:

`eaf9514a8a00e3d029e98e772cf0185bfafd270645cb21bc057f198a2d9e23f8`

인증서 지문은 공개 정보이며 개인키를 복원할 수 없다. APK에서도 개인키를 추출할 수 없다.

## 다른 PC에서 빌드

1. 기존 `~/.android/debug.keystore` 파일을 암호화된 보안 저장소에 백업한다. 키 파일과 비밀번호를 공개 저장소·릴리즈·로그에 넣지 않는다.
2. 다른 PC에 같은 파일을 안전하게 복원한다. 기존 Android debug 키가 있으면 덮어쓰지 않고 앱 전용 경로를 사용한다.
3. `./gradlew assembleDebug -Pbeta7compact.signingStore=/absolute/path/beta7compact.keystore`로 빌드한다. 경로는 개인 Gradle 설정에도 지정할 수 있다. 기본값은 사용자 홈의 `.android/debug.keystore`다.
4. 게시 전에 반드시 아래 검사를 실행한다. 실패하면 배포하지 않는다.

```sh
python3 tools/verify_release_apk.py app/build/outputs/apk/debug/app-debug.apk \
  --apksigner /absolute/path/android-sdk/build-tools/35.0.0/apksigner
```

현재 키는 Android debug 형식(별칭 androiddebugkey, 기본 비밀번호 android)이다. 비밀번호가 강한 보호 수단이 아니므로 키 파일 자체의 접근을 제한한다. 현재 Gradle 설정의 키 경로 옵션은 이 형식의 기존 키를 위한 것이며 임의의 전용 배포 키/비밀번호 설정을 지원한다는 뜻은 아니다.

## 분실·교체

키를 잃으면 같은 지문을 가진 새 키를 생성할 수 없다. 기존 사용자의 데이터를 유지한 업데이트 연속성을 보장하려면 원본 키를 보존해야 한다. 전용 배포 키로 바꾸는 작업은 서명 lineage와 지원 Android 버전, 기존 설치본의 실제 업데이트를 검증하는 별도 마이그레이션으로 진행한다. 이번 수정에서는 키를 교체하지 않는다.

## 0.5.0 사고와 수정

0.5.0은 worktree의 별도 `.android-user/debug.keystore`로 서명되어 기존 설치본과 달랐다. 0.5.1은 기존 설치본과 일치하는 사용자 키로 서명한다. 공개 키 검증 스크립트는 임의의 새 PC 키로 빌드한 APK가 정상 배포물로 오인되는 것을 막는다. APK 서명 유효성만으로 업데이트 호환성이 증명되는 것은 아니다.
