# 폰 사진·외부 이미지 추가 검증

2026-11-18 개발 당시 기록. 공개0.8.7을 기준으로 이 단계에서는 버전/릴리즈를 변경하지 않았다. 후속 [0.8.8 배포 검증](release-0.8.8.md)은 별도로 기록한다. 기능·수정 파일·보관 정책은 [외부 사진 추가 문서](../../EXTERNAL_PHOTO_IMPORT.md)에 정리했다.

## 실행한 검증

```sh
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew assembleDebug test lint --continue --no-daemon
python3 tools/check_m1_migration.py
python3 tools/check_m2_migration.py
python3 tools/verify_release_apk.py dist/beta7compact-external-photos-debug.apk \
  --apksigner .tools/android-sdk/build-tools/35.0.0/apksigner
git diff --check
```

[최종 빌드 로그](external-photos-build.log) · [검사 결과](external-photos-checks.txt).

- 최종 assembleDebug/test/lint 성공,1분4초.
- Debug/Release 각각43개 suite·355개 테스트. 실패·오류·skip0. 기존346개와 신규9개다.
- 신규 테스트: 원본 바이트 복사·내용 기반 키, 용량/빈 스트림/정체/취소, 알려진SHA-256, 실제 형식에 맞는 파일명, 공개/DB 등록 순서, 단계별 실패 정리, 등록 전/중 취소, 외부 행이 카메라 큐/저널에 섞이지 않는지 확인.
- Room 마이그레이션 검사2개 및 diff check 통과. DB 스키마 변경 없음.
- lint Fatal/Error0, Warning17, Information1.
- APK 서명 유효성과 기존 설치본 인증서 일치 확인. 기존 인증서 SHA-256 `eaf9514a8a00e3d029e98e772cf0185bfafd270645cb21bc057f198a2d9e23f8`.
- 개발 APK: `dist/beta7compact-external-photos-debug.apk`, 기본 버전0.8.7(20). 자동 업데이트용 새 릴리즈가 아니다.
- APK SHA-256 `832f6a8fab2b0ac65f3dbe7ebf55064910e497f1818e75018e8922f5901f2f40`.

최초 실행에서는 테스트의 제네릭 타입 추론으로 컴파일 오류가 발생했다. 테스트 대상 타입을명시하고 검증의 수치 타입도Long으로 맞춘 뒤 재실행해 통과했다. [최초 로그](external-photos-initial-build.log)를 최종 성공 로그와 구분한다. 이후 원본 URI 복구 시 미리보기 갱신, 빈 목록에서 불필요한 스크롤 대기를 피하는 처리를 반영한 상태로 최종 빌드했다.

## 미실행 항목과 남은 문제

시스템 사진/파일 선택기, 실제 폰카 JPEG·PNG·WebP·HEIC/AVIF, 클라우드 문서 제공자, MediaStore/Room의 실제 등록/복구, 선택 권한 만료·재실행, 새 원본의 편집·공유·JPEG 저장·EXIF 방향·테마/큰 글꼴 UI·TalkBack은 이번에 실기로 실행하지 않았다. 자동 테스트는 순수 정책/스트림/커밋 순서와 가짜 DAO 경계의 회귀 검사이며 실제 Android 입출력 성공을 보증하지 않는다.

지원되는 사진도 원해상도 저장은 기존 메모리 제한을 받는다. 편집 결과는JPEG이며 투명도/애니메이션/HDR 보존을 제공하지 않는다. MediaStore와Room 사이 강제 종료 시 연결되지 않은 사본이 남을 가능성과, 정리 도중 provider 오류가 발생하면 정리 성공을 보장하지 못하는 한계가 있다. 원본 선택 URI에는 쓰기/삭제를 하지 않는다.

새 릴리즈 게시·폰 설치·사용자 사진 열기/업로드·카메라 조작은 실행하지 않았다.
