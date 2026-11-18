# Sony 오프라인 파서 1단계 검증

2026-11-17. 현재 작업 트리 기준. 상세 구현과 형식 출처는 [오프라인 파서](../../SONY_OFFLINE_PARSERS.md).

## 변경 파일

- 신규 production: `remote/SonyExtendedInfoParser.kt`, `remote/SonyPropertyParser.kt`, `remote/SonyEventParser.kt`.
- 신규 JVM 테스트: `SonyExtendedInfoParserTest.kt`, `SonyPropertyParserTest.kt`, `SonyEventParserTest.kt`.
- 문서: `docs/SONY_OFFLINE_PARSERS.md`, `docs/REMOTE_CONTROL_SCOPE.md`의 진행 상태, 이 검증 기록과 빌드 로그.
- 이전 조사에서 작성된 `docs/SONY_SUPPORT_RESEARCH.md` 변경과 `REMOTE_CONTROL_SCOPE.md` 본문도 작업 트리에 유지했다.

확장정보·속성·이벤트를 서브에이전트 3개에 분리했고 통합 빌드는 주 작업자만 수행했다. 기존 USB·UI·Gradle·버전·서명 설정은 수정하지 않았다.

## 실행 명령

```sh
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew assembleDebug test lint --continue --no-daemon
```

JDK 21, Gradle Wrapper 8.9, Android SDK 35 / Build Tools 35.0.0. 출력은 [빌드 로그](sony-offline-parsers-build.log)에 보관한다.

## 미실행 및 제한

- Android 기기 테스트·폰 설치·α7C 통신·패킷 캡처·원격 촬영/설정 변경은 수행하지 않았다.
- fixture는 합성 데이터이며 α7C에서 확보한 바이트가 아니다.
- 형식 파싱 통과는 모델/펌웨어 호환성, 실제 쓰기 가능 여부, 촬영 완료의 증명이 아니다.
- 문자열·배열·128비트 및 새로운 secondary enum 등의 형식은 지원하지 않는다.
- 기존 앱 런타임에서 새 파서를 호출하지 않는다. 실제 연결 협상은 후속 단계다.
- 버전은 0.6.0/10을 유지한다. 이번 APK는 빌드 검증 산출물이며 새 업데이트 릴리즈로 게시하지 않는다.

## 실행 결과

- `assembleDebug test lint --continue --no-daemon`: 성공, 1분 36초, 86 tasks.
- Debug/Release 각각 228개 테스트 통과. 실패·오류·skip 0. 기존 196개에 신규 32개(확장정보 10, 속성 13, 이벤트 9)를 추가했다. 같은 테스트의 두 변형 실행이므로 456개 고유 테스트로 계산하지 않는다. [집계](sony-offline-parsers-tests.txt)
- lint: 오류 0, 경고 17. 신규 파일 지적 0개. 기존 kapt/의존성 관련 경고는 빌드 로그에 남아 있다.
- `git diff --check` 통과. 새 파서 호출 지점을 검색해 기존 앱 통신/UI 경로와 연결되지 않았음을 확인했다.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. 설치·배포·서명 호환 재검증은 이번 범위에서 수행하지 않았다.
