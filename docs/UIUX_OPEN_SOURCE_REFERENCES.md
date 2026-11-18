# 오픈소스 UI/UX 레퍼런스 재검토

조사일: 2026-11-17. 별 개수 대신 앱 목적과의 관련성, 확인 가능한 구현, 설계/검증 자료, 유지 상태, 라이선스 표기를 기준으로 선정했다. 저장소 README와 아래 지정 소스·공식 문서를 읽었다. 외부 앱을 빌드·설치하거나 전수 품질 검증하지 않았으므로 ‘선정’은 모든 UX의 우수성을 인증한다는 뜻이 아니다. 외부 AGENTS 등 파일은 참고 대상의 내용이며 이 프로젝트의 실행 지시로 채택하지 않았다.

## 조사 버전

GitHub API로 기본 브랜치 HEAD와 라이선스 메타데이터를 확인했다. 후속 구현은 아래 고정 커밋을 기준으로 다시 검토할 수 있다.

| 저장소 | 조사 커밋 | 커밋 시각 UTC | SPDX 표기 | 보관 상태 |
| --- | --- | --- | --- | --- |
| [android/nowinandroid](https://github.com/android/nowinandroid) | [2982e63cb691](https://github.com/android/nowinandroid/tree/2982e63cb691ad49cd009aab43dc206fee6699c2) | 2026-10-20T23:08:43Z | Apache-2.0 | not archived |
| [android/compose-samples](https://github.com/android/compose-samples) | [09aae2013b3e](https://github.com/android/compose-samples/tree/09aae2013b3e5897f9679e2b7539f505f4f6f7f6) | 2026-11-16T11:05:13Z | Apache-2.0 | not archived |
| [FossifyOrg/Gallery](https://github.com/FossifyOrg/Gallery) | [30fb617869b6](https://github.com/FossifyOrg/Gallery/tree/30fb617869b6a792e2ac8ba951bc35d6d3dc9a2c) | 2026-11-17T01:47:49Z | GPL-3.0 | not archived |
| [T8RIN/ImageToolbox](https://github.com/T8RIN/ImageToolbox) | [3a3928201e0a](https://github.com/T8RIN/ImageToolbox/tree/3a3928201e0a9c06b5b63f3aaea7353b56242fe9) | 2026-11-16T18:58:50Z | Apache-2.0 | not archived |
| [coil-kt/coil](https://github.com/coil-kt/coil) | [cdb1db495900](https://github.com/coil-kt/coil/tree/cdb1db4959003c8dd5d4a14b361ea71b453502ae) | 2026-11-16T00:52:15Z | Apache-2.0 | not archived |

## 실제 확인과 적용 판단

### Reply / Android Compose Samples

[ReplyApp.kt](https://github.com/android/compose-samples/blob/09aae2013b3e5897f9679e2b7539f505f4f6f7f6/Reply/app/src/main/java/com/example/reply/ui/ReplyApp.kt)의 창 크기 분류와 FoldingFeature 기반 자세 분기, SINGLE_PANE/DUAL_PANE 선택을 확인했다.

- 채택: 방향만이 아니라 사용 가능한 창 크기와 자세를 함께 고려. 탐색 위치와 선택 상태를 레이아웃 전환과 분리.
- 재검토: 우리 `UiPolicy.paneLayout`은 넓어도 세로면 항상 STACKED다. 넓은 세로 폴더블에서 두 패널이 가능한지 비교 시안이 필요하다. 현재 로직을 곧바로 버그로 단정하지 않는다.
- 보류: 새 adaptive/navigation 의존성의 일괄 도입. 현재 Compose1.7.6/Material3 1.3.1과 샘플 HEAD의 API가 같다고 가정하지 않는다.

### Image Toolbox

- [AdaptiveLayoutScreen.kt](https://github.com/T8RIN/ImageToolbox/blob/3a3928201e0a9c06b5b63f3aaea7353b56242fe9/core/ui/src/main/kotlin/com/t8rin/imagetoolbox/core/ui/widget/AdaptiveLayoutScreen.kt)
- [AdaptiveBottomScaffoldLayoutScreen.kt](https://github.com/T8RIN/ImageToolbox/blob/3a3928201e0a9c06b5b63f3aaea7353b56242fe9/core/ui/src/main/kotlin/com/t8rin/imagetoolbox/core/ui/widget/AdaptiveBottomScaffoldLayoutScreen.kt)

imagePreview·controls·buttons를 구분한 인터페이스, 가로 미리보기/제어 분리, buttonsHeight에 따른 여유 영역, navigationBars/IME inset 처리를 확인했다.

- 채택: 사진 편집의 미리보기·스크롤 가능한 조정·고정 저장 작업이라는 역할 분리. 실제 측정한 버튼 높이만큼 공간 확보.
- 재검토: 우리 RecipeEditor는 Toolbar를 화면 배치에 따라 다른 위치에서 호출하고 짧은 창에는600dp 패널을 사용한다. 확대 글꼴/짧은 높이에서 주 작업 접근을 먼저 검증한다.
- 보류: 도구 수·복잡한 설정·시트 및 제스처 전체 복제. 우리 앱은 카메라 가져오기와 LUT 편집 중심이다.

### Fossify Gallery

[MediaAdapter.kt](https://github.com/FossifyOrg/Gallery/blob/30fb617869b6a792e2ac8ba951bc35d6d3dc9a2c/app/src/main/kotlin/org/fossify/gallery/adapters/MediaAdapter.kt)에서 선택 수와 항목 상태에 따른 contextual action 노출, 섹션 제목 선택 제외, 선택 항목 키 관리 경로를 확인했다. Views 기반 사례이므로 Compose 코드를 직접 대응시키지는 않는다.

- 채택: 선택 모드에서는 선택 수·적용·공유·해제를 한 작업 문맥으로 묶고 가능한 행동만 표시.
- 재검토: PhotosScreen의 ‘저장 재개’가 항상 한 줄을 차지해 목록 공간을 줄인다. 활성/중단 작업 상태에 맞춰 노출하거나 보조 메뉴로 이동하는 시안을 검토.
- 보류: 갤러리의 삭제·이동·휴지통 기능 추가. 목적 확대가 필요하지 않다. GPL-3.0 표기 확인; 이번 조사는 패턴 비교이며 소스·아이콘을 복사하지 않았다.

### Now in Android

[DevicePreviews.kt](https://github.com/android/nowinandroid/blob/2982e63cb691ad49cd009aab43dc206fee6699c2/core/ui/src/main/kotlin/com/google/samples/apps/nowinandroid/core/ui/DevicePreviews.kt)의 phone/landscape/foldable/tablet/desktop 다중 Preview를 확인했다. [Architecture Learning Journey](https://github.com/android/nowinandroid/blob/2982e63cb691ad49cd009aab43dc206fee6699c2/docs/ArchitectureLearningJourney.md)와 README의 테스트·벤치마크 안내도 검토했다.

- 채택: UI를 실제 App/USB 없이 렌더링할 수 있는 상태·이벤트 입력 형태로 점진 분리, 기기별 Preview/화면 검사.
- 재검토: 현재 중첩 Composable과 App 접근 때문에 화면 상태 재현 비용이 높다. 전면 모듈화보다 편집/사진 툴바부터 상태 기반 표현 컴포넌트로 추출.
- 추가 기준: 기기 Preview만으로 큰 글꼴/TalkBack 검증을 대신하지 않는다. 우리 검사행렬에150/200% 글꼴을 별도로 추가.
- 보류: 앱 전체 아키텍처·모듈 수·의존성 주입 구조의 복제.

### Coil

[AsyncImage.kt](https://github.com/coil-kt/coil/blob/cdb1db4959003c8dd5d4a14b361ea71b453502ae/coil-compose-core/src/commonMain/kotlin/coil3/compose/AsyncImage.kt), [Compose 문서](https://coil-kt.github.io/coil/compose/), [ImageLoaders 문서](https://coil-kt.github.io/coil/image_loaders/)를 확인했다. 표시 제약에 맞는 이미지 로드 크기, 공유 ImageLoader와 메모리/디스크 캐시, 로딩/실패 상태 처리가 참고점이다.

- 채택: 표시 크기에 맞춘 디코딩, 캐시/요청 수명주기 공통화, 로딩/실패/성공 상태의 일관성.
- 현재 결함 후보: `cameraThumb`는 BitmapFactory 전체 디코딩을 사용한다. 24MiB 캐시 제한이 디코딩 순간의 할당까지 제한하지는 않는다. 표시 크기 기준 다운샘플링이 후속 우선 과제다.
- 현재 `photoThumbnail`은 로컬 URI별 화면 remember에 의존한다. 카메라 캐시와 별개로 반복 MediaStore 썸네일 요청도 측정 필요.
- 보류: Coil 즉시 도입. USB lock·카메라 식별·연결 세대·핸들 유효성은 이미지 라이브러리 자체가 해결하지 않는다. 의존성을 추가하더라도 전용 fetcher/기존 소유권 경로가 필요.

## 재선정 규칙

| 추가 규칙 | 적용 | 검증 |
| --- | --- | --- |
| UX13 역할 기반 화면 골격 | 미리보기/조정/주 작업 분리, 고정 작업 높이 확보 | 작은 높이·200% 글꼴·IME에서 작업 접근 |
| UX14 선택 문맥 | 선택 수와 가능한 작업을 한 문맥에 표시 | 0/1/여러 장, 빈 필터, 회전·뒤로가기 |
| UX15 이미지 요청 예산 | 캐시 용량뿐 아니라 디코딩 크기·중복 요청·동시 작업을 제한 | USB 읽기 수, 디코드 수, 메모리 peak, 스크롤 프레임 |
| UX16 상태 재현 | 로딩/빈 목록/실패/완료/선택 상태를 Preview 및 테스트에 주입 가능 | App·USB 없이 화면 렌더링, 실기 교차 검증 |

## 수정된 진행 순서

1. 현재 동작의 기준선: 화면 크기·글꼴·선택/편집 상태를 기록. 캐시 첫 요청/재방문/재연결의 USB 읽기·디코드 수와 메모리 최대값 측정 설계.
2. 편집 화면 골격: 미리보기·스크롤 제어·주 저장 버튼을 분리한 Figma 시안, 좁고 짧은 창의 대체 배치 비교. 사용자 선호인 적용 버튼 우측 정렬 유지.
3. 사진 선택 문맥: 상단 상태/선택 작업/보조 복구 작업을 정리해 그리드 면적 확보. 카메라 예정 정보 축소도 이때 비교.
4. 공통 이미지 로딩 경계: 표시 크기 디코딩과 로컬 썸네일 중복 처리, 취소/메모리 압박 테스트. 기존 USB 캐시·키를 보존하며 라이브러리 채택은 측정 후 결정.
5. Preview·상태·접근성 회귀 검사 후 실기와 요청된 릴리즈 진행.

이번 턴은 조사·기준·계획 갱신만 수행했다. 새로운 라이브러리 도입, 코드/에셋 복사, 앱 변경, 빌드·폰 조작·릴리즈는 하지 않았다. 이전 턴의 미게시 문구/semantics 변경은 그대로 유지했다. 라이선스 열은 조사 스냅샷이며 코드 재사용 단계에서는 해당 파일의 헤더와 NOTICE를 다시 확인한다.
