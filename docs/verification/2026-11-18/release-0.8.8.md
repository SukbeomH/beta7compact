# 0.8.8 폰 사진·외부 이미지 추가 배포 검증

2026-11-18. versionName0.8.8 / versionCode21. 외부 사진 추가 기능을 포함해 빌드·테스트·서명을 확인하고 사용자 요청에 따라 작업 브랜치 푸시와 master 머지를 진행한다.

## 수정 파일

- `library/ExternalImageInput.kt`: 원본 바이트 제한 복사·SHA-256·형식/파일명 정책·공개/등록/정리 순서.
- `library/ExternalPhotoImporter.kt`: 시스템 선택 URI 읽기, 임시 사본·디코딩 확인·중복 비교, MediaStore/Room 등록·기존 사본 복구.
- `library/LibraryDb.kt`, `ui/MainScreen.kt`: 외부 행을 카메라 큐·완료 이력에서 분리. DB 스키마 변경 없음.
- `ui/PhotosScreen.kt`: 사진/파일 선택기·진행/중단/결과·외부 필터/배지·선택 메뉴·빈 목록 안내·실제 MIME 원본 열기.
- `ui/PhotoDetailScreen.kt`, `recipe/PhotoRenderer.kt`: 추가일 표시·원본 URI 복구 시 미리보기 갱신·EXIF 없는 형식 처리.
- `ExternalImageImportTest.kt`: 신규9개 테스트. 바이트 보존/해시·용량/읽기 오류·이름/형식·등록 순서·실패/취소·카메라 이력 분리.
- `app/build.gradle.kts`, README, CHANGELOG, [사용법·보관 정책](../../EXTERNAL_PHOTO_IMPORT.md), [개발 검증 기록](external-photos.md), 배포 로그와 이 문서.

원본 선택 URI에 쓰기/삭제하지 않고 별도 사본으로 편집한다. 기존 가져오기·진단·LUT/레시피·왜곡·테마·사진 저장은 유지하며 새 미디어 읽기 권한이나 외부 의존성을 추가하지 않았다. 선택 모드는 더보기/길게 누르기로 옮겨 사진 추가와의 툴바 밀집을 줄였다.

## 실행 명령

```sh
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew clean assembleDebug test lint --continue --no-daemon
python3 tools/check_m1_migration.py
python3 tools/check_m2_migration.py
python3 tools/verify_release_apk.py dist/beta7compact-0.8.8.apk \
  --apksigner .tools/android-sdk/build-tools/35.0.0/apksigner
.tools/android-sdk/build-tools/35.0.0/aapt dump badging dist/beta7compact-0.8.8.apk
shasum -a 256 dist/beta7compact-0.8.8.apk
git diff --check
```

[클린 빌드 로그](release-0.8.8-build.log) · [검사 결과](release-0.8.8-checks.txt). Git 공백 검사를 위해 로그의 후행 공백만 정리했다. 최초 개발 테스트의 타입 추론 오류와 수정은 [개발 기록](external-photos.md)에 구분해 남겼다.

## 미실행 검증과 남은 문제

사용자는0.8.7의 기존 실화면 구성에 걸리는 부분이 없다고 확인했다. 이번 외부 사진 추가 기능을 포함한0.8.8의 폰/카메라 실기 확인은 사용자 후속 진행이다. 이번에는 시스템 사진/파일 선택기, 실제 이미지·클라우드 제공자 읽기, Android 디코더/MediaStore와 재실행, 새 이미지의 편집·공유·저장·EXIF 방향·4테마/큰 글꼴/TalkBack을 실행하지 않았다. 자동 테스트는 순수 정책/스트림/등록 순서와 가짜 DAO 경계를 검증하며 실제 Android 입출력을 보증하지 않는다.

한 번에50장·파일당64MiB다. HEIC/HEIF·AVIF는 실제 기기 지원에 따르고, 고해상도 저장은 기존 메모리 제한을 받는다. 출력은JPEG/sRGB이며 투명도/애니메이션/HDR를 보존하지 않는다. 선택한 원본의 메타데이터는 별도 사본에 바이트 그대로 남으므로 원본 공유 시 사용자가 공개 범위를 판단한다.

MediaStore와Room은 하나의 원자적 트랜잭션이 아니어서 공개·등록 사이 강제 종료 시 연결되지 않은 사본이 남을 가능성이 있다. provider 오류 시 새 사본 정리 성공도 보장하지 않는다. 외부 사본은 캐시가 아닌 라이브러리 파일이며 저장 공간을 사용한다. 지속적인 작업 저널/자동 사본 삭제는 추가하지 않았다.

기존 α7C 원격 실험의 실기 미검증 상태는 유지한다. 이번에 카메라 명령·펌웨어 기능·프로파일 자동 보정을 추가하지 않았다.

## 배포 형식과 Git 통합

기존 인증서로 서명한 assembleDebug APK를 게시한다. Release variant 단위 테스트도 실행하지만 production release variant로 전환한 것은 아니다. 개인키·비밀번호·사용자 이미지 파일은 Git/릴리즈에 포함하지 않는다.

원격 master는 작업 브랜치의 조상이며 보호되지 않은 상태임을 확인했다. 로컬 master 작업 디렉터리도 읽기 검사에서 수정 사항이 없었다. 사용자 요청에 따라 작업 커밋을 푸시하고 master에 fast-forward로 통합한다. 기존 작업 브랜치의 이전 릴리즈 변경도 함께 master에 반영된다. 강제 푸시나 사용자 수정 폐기는 하지 않는다.

[GitHub v0.8.8 릴리즈](https://github.com/SukbeomH/beta7compact/releases/tag/v0.8.8) · [APK](https://github.com/SukbeomH/beta7compact/releases/download/v0.8.8/beta7compact-0.8.8.apk).

## 최종 검증 결과

- clean assembleDebug/test/lint 성공,2분12초.87개 작업 실행.
- Debug/Release 각각43개 suite·355개 테스트, 실패·오류·skip0. 기존346개와 신규9개.
- Room 마이그레이션 검사2개, diff check 통과.
- lint Fatal/Error0, Warning17, Information1.
- 패키지 dev.sukbeomh.beta7compact, 버전0.8.8(21), minSdk29/targetSdk35 확인.
- 기존 인증서 SHA-256 `eaf9514a8a00e3d029e98e772cf0185bfafd270645cb21bc057f198a2d9e23f8` 일치, APK 서명 검증 통과.
- APK SHA-256 `574510b0bc90619a93103a14accfc1503ad0f49e89d5c0bda6979c7fe7f1514a`.
