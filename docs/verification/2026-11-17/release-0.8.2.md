# 0.8.2 가로 사진 선택·썸네일 캐시 배포 검증

2026-11-17. versionName 0.8.2 / versionCode 15.

## 수정 파일과 동작

- `ui/PhotosScreen.kt`: 다중 선택 전체 폭 그리드, 가로 adaptive 열 수, 우측 하단 적용 버튼 고정. 일반 탐색 패널 240~360dp 제한.
- `cache/ThumbnailCache.kt`: 24MiB LRU 메모리 캐시, 연결 세대 검사, 갱신 목록에서 삭제된 항목 제거.
- `cache/DiskThumbnailCache.kt`: 앱 전용 128MiB 캐시, 항목당 4MiB, 최근 사용 시각과 오래된 항목 제거, 해시 파일명.
- `App.kt`, `MainActivity.kt`, `ui/MainScreen.kt`: 캐시 연결, Activity 시작·USB 연결 변경 시 메모리 초기화, 스캔/썸네일에 연결 세대 검사, 재연결 디스크 조회. 실패 응답은 저장하지 않음.
- `ThumbnailCacheTest.kt`, `DiskThumbnailCacheTest.kt`: 재사용·LRU·항목 한도·세대 무효화·디스크 재생성 후 조회·카메라 범위 분리 검증.
- `README.md`, `CHANGELOG.md`, `docs/CAMERA_THUMBNAIL_CACHE.md`, 앱 버전 및 검증 로그.

## 실행한 검증

```sh
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew assembleDebug test lint --continue --no-daemon
python3 tools/check_m1_migration.py
python3 tools/check_m2_migration.py
python3 tools/verify_release_apk.py dist/beta7compact-0.8.2.apk \
  --apksigner .tools/android-sdk/build-tools/35.0.0/apksigner
```

[전체 빌드 로그](release-0.8.2-build.log). [캐시 설계와 한계](../../CAMERA_THUMBNAIL_CACHE.md).

## 미실행 검증

실제 폰 가로/폴더블 배치·선택·버튼 조작·카메라 재연결·속도 측정·장시간 메모리 사용량·업데이트 설치는 수행하지 않았다. 실제 α7C AF·촬영·카드 저장도 사용자 후속 확인 대상이다. 단위 테스트의 재연결 검증은 캐시 인스턴스 재생성과 연결 세대 변경을 모의한 것이며 실제 USB 테스트가 아니다.

## 결과

- 빌드·lint 성공, 1분40초. Debug/Release 각각326개, 실패·오류·skip0.
- lint 오류0, 경고18개. 마이그레이션 검사2개 통과.
- 기존 설치본 인증서 일치. 패키지 dev.sukbeomh.beta7compact, 버전0.8.2(15).
- APK SHA-256 `fbf792751ff60afc0b1879f076c1ea4bdd88ae0d7fcd0ab3b488823d7be3e396`.
