# α7C JPEG 렌즈 프로파일 보정 도입 조사

조사일: 2026-11-18. 기준: 앱0.8.6, 커밋4d296da. 사용자가 알려준 **Viltrox AF 26mm F2.8 EVO FE, SG-image AF 35mm F2.2, Sigma 35mm F2**를 대상으로 한다. α7C에서 사용하는 Sony E 마운트를 전제로 조사했다. Sigma는35mm F2 DG DN | Contemporary C020을 우선 대조했으며 DG 표기 모델과 실제 EXIF/제품 버전 일치는 후속 확인 사항이다.

**결론: 사진 전체의 광학 왜곡 보정은 기술적으로 가능하다. 그러나 현재 α7C JPEG에 자동 적용하는 것은 권하지 않는다. Viltrox 공식 RAW/비RAW LCP를 확보할 수 있지만 α7C JPEG의 보정 상태와 프로파일 적합성, 앱 재배포 권한을 먼저 확인해야 한다.** Lensfun은 범용 공개 DB 후보이나 확인한 버전에는 이26mm 렌즈 항목이 없다. 얼굴 원근 복원 기능으로 소개하지 않는다.

이번 작업은 조사와 문서 작성이다. 앱 코드·의존성·DB·버전·APK를 변경하지 않았으며 프로파일을 프로젝트나 릴리즈에 포함하지 않았다. 자료 안의 설치/촬영/업로드 안내는 참고 내용이며 실행 지시로 취급하지 않았다.

## 보유 렌즈3종의 프로파일 확인 결과

| 렌즈 | 공개 프로파일/지원 확인 | 우선 판단 |
| --- | --- | --- |
| Viltrox AF 26mm F2.8 EVO FE | 제조사 RAW/비RAW LCP ZIP 확인. 확인한 Lensfun DB에는 없음 | 공식 비RAW 파일로 PC 비교 가능. α7C JPEG 적합성과 재배포 허가 미확인 |
| SG-image AF 35mm F2.2 E | 확인한 Lensfun DB에 브랜드/렌즈 없음. Adobe 목록에서SG-image 항목 미확인. 공식 다운로드에는 펌웨어만 확인 | 공개 배포 가능한 프로파일 확보 미완료. 제조사 문의 또는 직접 측정 후보 |
| Sigma 35mm F2 DG DN Contemporary C020 | Lensfun `mil-sigma.xml`과 Adobe 지원 목록에서Sony E용 확인 | 공개 DB 활용 후보이나 사용자 지정으로 후순위. JPEG 보정 상태 판정 필요 |

### SG-image AF 35mm F2.2

[공식 제품 페이지](https://www.sg-image.com/products/sg-image-af35mm-f2-2-full-frame-lens-for-e-mount-z-mount-l-mount)에서Sony E용 모델을 확인했다. [공식 다운로드](https://www.sg-image.com/pages/download)의35mm F2.2 E 항목은펌웨어 Ver1.06이며 LCP/왜곡 계수 파일로 표시되지 않는다. 다운로드하거나 카메라/렌즈에 설치하지 않았다.

동일 Lensfun 고정 커밋의 XML56개를 maker/model의 `sg-image`, `sg image`, `sgimage`로 대조했으며 항목을 찾지 못했다. 조회한 Adobe 렌즈 목록에도SG-image 표기는 없었다. 이는 조사한 공식 공급원/DB에서 미확인이라는 뜻이며 모든 개인 포크·커뮤니티 파일까지 존재하지 않는다는 주장과 다르다. 아직 계수나 파일을 확보하지 않았으므로 앱에 동봉할 라이선스도 확보되지 않았다. Sigma35mm와 초점거리가 같다는 이유로 Sigma 계수를 사용하지 않는다.

### Sigma 35mm F2

[Lensfun `mil-sigma.xml`](https://github.com/lensfun/lensfun/blob/37238fcef563196fb798f6151cb9023968b26c40/data/db/mil-sigma.xml)에 `35mm F2 DG DN | Contemporary 020`이 있다. 마운트는Sony E/Leica L, cropfactor1.0이며35mm의 `ptlens` 왜곡 데이터와 `poly3` 횡색수차 데이터가 있다. 이 항목에는비네팅 데이터가 없다. 필요한 항목별 지원 여부를 구분해야 한다.

[Adobe 지원 목록](https://helpx.adobe.com/camera-raw/desktop/dng-and-file-formats/supported-lenses.html)은 `SIGMA 35mm F2 DG DN C020`의Sony FE와 `SIGMA 35mm F2 DG C020`의Sony FE를 각각 기재한다. Adobe에서 지원한다는 사실이 해당 파일의 앱 재배포 허가나 JPEG용 프로파일의 존재/적합성을 보증하지는 않는다.

[Sigma의 DG DN 제품 페이지](https://www.sigmaphoto.com/35mm-f2-dg-dn-c)는후속 DG 표기 제품을 별도로 연결한다. 사용자의 간단한 모델명만으로 모든 표기/버전의 프로파일을 동일하게 자동 매칭하지 않는다. 실제 렌즈/EXIF와 마운트·크롭을 대조한다. Lensfun 계수의배포 조건은CC BY-SA3.0이며 DB 일부만 추출·변환해도 출처/라이선스와 변경 이력 관리가 필요하다.

사용자 지정에 따라 **Viltrox26mm FE의 α7C JPEG 검증을 최우선**으로 한다. Sigma와 SG-image는 후순위다. [카메라 설정·샘플 촬영·중복 보정 해석](VILTROX26_VALIDATION.md)과 [공식 LCP 앱 사용·재배포 조건 추가 조사](VILTROX26_PROFILE_RIGHTS.md)를 작성했다. 세 렌즈의 왜곡 곡선·보정 상태를 공유하지 않는다.

## Viltrox 26mm용 프로파일을 실제로 찾은 곳

[Viltrox 공식 FE 다운로드 페이지](https://viltrox.com/pages/af-56-1-7-xf)의 표시 제목은 **AF 26/2.8 EVO FE**다. URL 경로에56/1.7이 남아 있지만 페이지 제목과 다운로드 항목은26/2.8 FE로 확인했다. Document Download 아래 **AF 26/2.8 EVO FE Lens Correction Profiles → ZIP**을 선택한다. Nikon Z용 파일과 혼동하지 않는다.

[확인한 공식 ZIP](https://cdn.shopify.com/s/files/1/0104/0380/7298/files/26f2.8FE_Lens_Correction_Profiles.zip?v=1792983630)을 임시 경로에 내려받아 ZIP 목록과 XML을 읽었다. 파일 크기280,366bytes, SHA-256 `e2def191a0c83b11e8f8f4fe098e5603d6bfa455b8541b71161fe61f992d6c4e`. 라이선스 미확인 파일이므로 저장소에는 복사하지 않았다.

| ZIP 안 파일 | 확인한 메타데이터 | 적용 판단 |
| --- | --- | --- |
| `Viltrox 26mm F2.8 FE - RAW.lcp` | CameraRawProfile=True, Model/UniqueCameraModel=ILCE-7M5, SensorFormatFactor=1, ImageWidth×ImageLength=5477×3651 | 미현상 RAW를 대상으로 한 후보. JPEG에 그대로 적용하지 않음 |
| `Viltrox 26mm F2.8 FE.lcp` | CameraRawProfile=False, Model/CameraPrettyName=ILCE-7RM5, SensorFormatFactor=1, ImageWidth×ImageLength=6240×4160 | 비RAW 후보. α7C JPEG의 카메라 보정 상태/크롭과 일치한다고 확인된 것은 아님 |
| `镜头配置文件使用说明.pdf` | Adobe 사용자 LensProfiles 폴더에 배치하고 Lightroom/Photoshop에서 선택하는 설명 | 사용자 설치 안내이며 앱 배포 허가 문서는 아님 |

두 LCP는 PerspectiveModel, RadialDistortParam1/2, 색수차 모델, VignetteModel을 포함한다. 각각 한 개의 CameraProfiles 항목에서 촬영 조건을 기록하고 있다. 보정 데이터가 있다는 사실과 α7C·다른 거리/조리개에서 충분한 품질을 갖는다는 사실은 구분한다. 위 ImageWidth/Length는 파일에 기록된 값이며 카메라의 최대 해상도라고 해석하지 않는다. CameraRawProfile=False는 비RAW용이라는 구분이지, 보정 전/후 JPEG를 모두 구분해 준다는 뜻이 아니다.

[Viltrox 제품 설명](https://viltrox.com/products/af-26mm-f2-8-fe)은 카메라 내 보정과 함께 사용할 때 왜곡이 줄어든다고 안내한다. 따라서 이 렌즈의 α7C JPEG도 이미 보정됐을 가능성을 검토해야 한다. 제품 설명만으로 사용자의 α7C 펌웨어·렌즈 펌웨어·촬영 설정에서 실제 적용됐다고 확정하지 않는다.

조회한 [Adobe 지원 렌즈 목록](https://helpx.adobe.com/camera-raw/desktop/dng-and-file-formats/supported-lenses.html)의 Viltrox 구역에서는26mm F2.8 항목을 찾지 못했다. 이는 제조사 제공 LCP가 없다는 뜻도, 수동 설치가 불가능하다는 뜻도 아니다. 목록·제조사 배포·프로그램 자동 인식은 별도다.

## 프로파일을 구할 수 있는 공급원

| 공급원 | 얻는 것 | 배포/사용 조건과 한계 |
| --- | --- | --- |
| [Lensfun DB](https://github.com/lensfun/lensfun/tree/master/data/db), [지원 렌즈 목록](https://lensfun.github.io/lenslist/) | XML 보정 계수, 카메라/마운트/크롭 정보 | DB는CC BY-SA3.0. 출처/라이선스/변경 고지 및 파생 데이터의 동일 조건 필요. 렌즈별 왜곡/TCA/비네팅 지원이 다름 |
| Viltrox 등 제조사 공식 지원 페이지 | 특정 렌즈용 LCP, 때로 RAW/비RAW 구분 | 무료 다운로드와 앱 내 복사·변환·재배포 권한은 별개. 이 ZIP은 별도 허가 확인 필요 |
| Adobe Camera Raw/Lightroom/DNG Converter의 프로파일 | 광범위한 LCP 또는 내장 지원 | 설치 제품의 이용 약관을 따름. 공개 라이선스 DB로 취급하지 않음. 앱에서 파일 추출/동봉 권한을 이번에 확보하지 않음 |
| [Lensfun 캘리브레이션 안내](https://lensfun.github.io/calibration/)·업로드 서비스 | 직접 촬영한 자료로 새 XML 프로파일 생성·기여 | 관리자는 적합한 RAW 업로드를 권장. 사진 외부 업로드는 별도 사용자 선택 사항이며 이번에는 실행하지 않음 |
| OpenCV를 이용한 직접 측정 | 카메라 행렬과 왜곡 계수 | 해당 앱·렌즈·크롭·보정 상태에 맞는 자체 데이터 생성 가능. 촬영/검증 비용 필요. 공개 측정 모델과 라이선스 명시 권장 |
| 커뮤니티 LCP·리뷰·포럼 | 수동 보정 팁·개인 프로파일 | 작성자·측정 조건·사용/재배포 허용 확인 필요. 다운로드 가능하다고 공개 라이선스로 가정하지 않음 |

리뷰의 “배럴 왜곡 몇%”나 Lightroom 슬라이더 값은 독립적인 프로파일이 아니다. 투영식·좌표 정규화·크롭·측정 조건 없이 이 값을 LCP/Lensfun/OpenCV 계수로 대입할 수 없다. 초점거리가 비슷한 타사26/28mm 프로파일도 대용하지 않는다. 제조사 펌웨어 파일은 보정 프로파일 공급원으로 취급하거나 추출하지 않는다.

### Lensfun DB 실물 확인

GitHub master의 확인 커밋은 `37238fcef563196fb798f6151cb9023968b26c40`이다. 해당 tarball의 `data/db/*.xml` **56개**를 메모리에서 XML로 읽고 maker/model을 대조했다. Viltrox 항목19개가 `misc.xml`에 있었지만26mm 항목은 없었다. 검색 결과가 안 보인다는 수준이 아니라 이 스냅샷 내 대조 결과이며, 미병합 PR·개인 포크·향후 갱신까지 없다고 단정하지 않는다.

`mil-sony.xml`에는ILCE-7C, ILCE-7CM2, ILCE-7CR의 바디/크롭 정보와 FE28–60mm f/4–5.6 등의 렌즈 계수가 있다. FE28–60은28/35/40/50/60mm 왜곡 데이터가 있었지만 사용자 Viltrox26mm에 적용할 수 있는 근거는 아니다. 바디가 등록됐다는 사실이 모든 렌즈를 보정해 준다는 뜻은 아니다.

## 활용 가능한 오픈소스와 라이선스

| 구성요소 | 확인한 조건 | 현재 앱에서의 선택 |
| --- | --- | --- |
| Lensfun 라이브러리 | LGPLv3, C/C++·CMake·GLib 의존 | NDK/JNI·ABI·GLib 패키징 및 재빌드/재링크 가능성 검토 필요. 데스크톱 C++ 라이브러리이지 현재 Gradle에 붙여 검증된 Android 패키지가 아님 |
| Lensfun XML DB | CC BY-SA3.0, 코드 라이선스와 별도 | 데이터만 사용하고 독립 엔진을 작성하는 방식도 후보. 변환한 데이터에도 원본 권리가 사라지지 않음 |
| Lensfun 앱/변환 도구 | 저장소 apps/는GPLv3가 기본, 개별 파일 별도 조건 확인 필요 | LCP→XML 변환이 권리 문제나 모델 호환성을 자동 해결하지 않음. APK에 CLI를 동봉하지 않음 |
| OpenCV4.5.0 이상 | Apache2.0 | 캘리브레이션·undistort/remap의 기준 구현 후보. 라이선스/저작권/해당NOTICE·의존성 고지 유지. 프로파일 DB는 제공하지 않음 |
| RawTherapee | 저장소 LICENSE는GPLv3 | PC에서 LCP/Lensfun 비교 검증에 유용. 앱에 엔진 코드를 포팅하면 결합/파생물의GPL 의무 검토 필요 |
| ExifTool | Perl 자체와 같은 조건: Perl Artistic License 또는GPL | PC 메타데이터 조사 도구로 적합. 이번 Android APK에 Perl 런타임/파서를 넣는 선택은 권하지 않음 |

[LGPLv3](https://github.com/lensfun/lensfun/blob/37238fcef563196fb798f6151cb9023968b26c40/COPYING.LESSER)는 고지·라이선스 동봉 외에도 수정된 라이브러리와 재결합/재링크할 수 있는 경로와 관련 소스/자료, 적용되는 경우 설치 정보를 요구한다. Android에서 `.so`로 동적 링크했다는 이유만으로 모두 충족한다고 판단하지 않는다. 반대로LGPL이라는 이유만으로 앱 전체의 소스 공개가 무조건 요구된다고 단정하지 않는다.

[CC BY-SA3.0](https://creativecommons.org/licenses/by-sa/3.0/) 데이터는 원출처/저작자·라이선스와 수정 이력을 관리하고 파생 DB를 동일 조건으로 제공하는 구성이 적합하다. 계수 DB 이용만으로 앱 코드 전체나 처리한 개인 사진까지 동일 라이선스가 된다고 보지 않는다. 배포 시 실제 결합물과 권리 범위를 확인한다.

직접 엔진을 구현하더라도 Lensfun 소스를 번역/복사하면 해당 코드의LGPL 검토가 남는다. 데이터만 쓰려면 공개된 수학 모델을 이해하고 독립적으로 구현·검증해야 한다. OpenCV의Brown–Conrady 계수 배열과 Lensfun의ptlens/poly3/poly5, Adobe LCP 좌표 모델은 서로 같지 않으므로 계수를 그대로 대입하지 않는다.

### Viltrox·Adobe LCP 권리 판단

확인한 Viltrox ZIP에는 두 LCP와 사용 설명 PDF가 있고 별도 LICENSE/README는 없다. LCP 텍스트에서copyright/license/redistribution 허용 문구는 찾지 못했다. 최초 PDF 텍스트 추출에는 글꼴 깨짐이 있었으며, 후속 조사에서3페이지를 렌더링해 시각 확인했다. 내용은 Adobe 폴더 설치와 사용 안내이며 재배포 허가 조항은 확인되지 않았다. [추가 조사](VILTROX26_PROFILE_RIGHTS.md).

[Viltrox 사이트 약관](https://viltrox.com/pages/terms-of-service)은 서비스 복제/재판매 등에 서면 허가 조건을 둔다. 이 일반 약관만으로 LCP의 정확한 권리 범위를 확정하지 않지만 APK 동봉 허가로 사용할 수도 없다. 따라서 **제조사에 Android 앱에서의 파일/변환 데이터 재배포·수정·상업적 배포 허용 범위를 확인하기 전에는 번들에 넣지 않는다.** 이번에 연락을 보내거나 허가를 요청하지 않았다.

사용자가 직접 확보한 LCP를 로컬로 선택하는 방식은 재배포를 줄이는 후보이나, 파일 자체의 이용 조건과 앱에서 해석할 수 있는 모델 범위를 별도로 충족해야 한다. “사용자 가져오기면 모든 라이선스 문제가 해결된다”는 결론은 아니다. Adobe DNG Converter 제공 파일도 동일하게 공개 프로파일로 재배포하지 않는다. [Lensfun의 LCP 안내](https://lensfun.github.io/manual/latest/lensfun-convert-lcp.html) 역시Adobe 배포물의 라이선스를 확인하도록 명시한다.

## α7C JPEG의 중복 보정을 방지할 수 있는 범위

[Sony α7C Lens Comp.](https://helpguide.sony.net/ilc/2020/v1/en/contents/TP1000153444.html)는 지원 렌즈의 왜곡/주변부/색수차 보정을 제공하며 렌즈에 따라 왜곡 보정이Auto로 고정될 수 있다고 안내한다. [Sony 지원 문서](https://www.sony.com/electronics/support/e-mount-body-ilce-7-series/articles/00018031)는RAW+JPEG에서JPEG에 보정이 적용되지만RAW 자체는 보정되지 않는다고 설명한다. 내장 JPEG와 RAW 원본 픽셀을 혼동하지 않는다.

현재 JPEG 한 장만으로 모든 처리 이력의 유무를 완벽히 증명할 수 없다. EXIF가 삭제되거나 다른 편집기가 보정 후 태그를 유지/수정할 수 있다. 따라서 **확인할 수 없으면 자동 적용하지 않는 정책**이 필요하다. 왜곡·색수차·주변부 밝기는 각각 다른 상태로 관리한다.

| 증거/상태 | 해석 | 권장 동작 |
| --- | --- | --- |
| 검증한 원본 JPEG에서 보정 적용 신호가 확인됨 | 해당 항목이 이미 처리됨 | 해당 항목의 프로파일 재적용 차단 |
| 앱 자신의 해당 결과 이력/프로파일ID·버전·입력 해시가 확인됨 | 앱에서 이미 적용 | 같은 결과에 반복 적용 차단, 변경은 원본에서 다시 렌더 |
| 카메라의DistortionCorrectionSetting=Auto만 확인 | 설정 상태이지 실제 픽셀 적용 증거는 아님 | 자동 보정 제외 또는 추가 증거 확인 |
| DistortionCorrParamsPresent/계수만 확인 | 데이터 존재이지 적용 완료 표시는 아님 | 적용 여부와 분리, 자동 적용하지 않음 |
| 원본·렌즈·모드별로 검증한 미보정 상태와 정확한 프로파일이 일치 | 적용 후보 | 사용자가 비교 후 명시적으로 켜는 방식부터 검토 |
| 태그 없음/충돌/편집된 JPEG/출처 불명 | 알 수 없음 | 기본 꺼짐. 미보정으로 간주하지 않음 |
| 비RAW LCP이지만 카메라 보정 상태/크롭이 불명 | 프로파일과 입력이 맞는지 불명 | CameraRawProfile=False만으로 허용하지 않음 |

ExifTool의 [Sony.pm](https://github.com/exiftool/exiftool/blob/master/lib/Image/ExifTool/Sony.pm)에는 `DistortionCorrectionSetting`, `DistortionCorrection`(None/Applied), `DistortionCorrParamsPresent`, `DistortionCorrParams` 정의가 있다. 다만 여러 암호화/모델별 테이블에 흩어져 있고 일부 테이블의 유효 모델 목록에7C가 명시되지 않는다. **태그 정의를 발견한 것과 α7C+Viltrox 원본에서 그 태그를 신뢰할 수 있는 것은 별개다.** 실제 원본으로 판독 위치/출력값/보정On·Off와의 대응을 확인하기 전에는 자동 판정기로 채택하지 않는다. 태그 미출력을Off로 처리하지 않는다.

카메라의 현재 메뉴를 읽더라도 이미 촬영한 사진의 설정 이력을 보증하지 못한다. 외형상 직선이 보인다는 판단만으로 렌즈 보정 완료라고 확정할 수도 없다. 전체 JPEG를 자동 역보정해 RAW용 프로파일을 적용하는 방법은 카메라 크롭·보간으로 잃은 정보를 복원하지 못하므로 권하지 않는다.

외부 앱이 이력/태그를 제거한 사진까지 반복 적용을 완벽히 막는다고 보장하지 않는다. 앱 내부에서는 원본 입력과 결과를 연결하고, 출력에 가능한 이력을 기록하되 읽히지 않으면unknown으로 돌아가야 한다. `Software=beta7compact`만으로 렌즈 보정 적용을 주장하지 않는다.

## 현재 앱 구조와 충돌하는 부분

- `recipe/PhotoRenderer.kt`는 JPEG를sRGB로 디코딩하고 EXIF 방향을 처리한다. ARW는 센서 RAW 현상 대신**내장 JPEG 추출**을 사용한다. 그러므로ARW를 골랐다는 이유로RAW LCP을 적용하면 안 된다. 추출 JPEG의 보정 상태와 메타데이터를 따로 다뤄야 한다.
- 현재는 `android.media.ExifInterface`로 방향/태그 복사를 처리하고Sony 보정 상태 전용 파서는 없다. AndroidX EXIF로 바꾸는 것만으로Sony 암호화 MakerNotes 해석이 해결된다고 가정하지 않는다.
- 출력은MakerNote와 구조 태그를 제외하고 기본 EXIF를 복사하며, 실패하면 앱이 만든 최소 태그만 남기는 경로가 있다. 출력 JPEG의 태그만으로 보정 이력을 추론할 수 없으므로 별도 provenance가 필요하다.
- 내보내기는512×512 입력 타일을 읽고GPU 색 변환 후 같은 위치에 그린다. 기하 변환은 출력 타일이 다른 입력 타일의 픽셀을 요구할 수 있어 현재 타일 shader에 수식만 추가하면 경계/검정 영역/누락 문제가 생긴다. 역매핑에 따른 입력 범위·보간 여유 픽셀·캐시·메모리·취소를 재설계해야 한다.
- `Recipe` v1/v2는사진 간 공유하는 색/LUT 설정이다. 렌즈 모델·촬영 모드·크롭·중복 보정 상태는 사진별이다. LUT 레시피에 계수를 무조건 저장해 다른 렌즈 사진에 일괄 적용하지 않는다.
- JPEG 색수차/비네팅까지 함께 켜지 않는다. 카메라의 색/톤/음영 처리와LUT·예술적 비네트가 겹칠 수 있어 첫 검증 범위는**광학적 기하 왜곡만**으로 제한하는 편이 적합하다. RawTherapee 문서도 비RAW의 LCP 지원을기하 왜곡으로 제한하며 이는 해당 프로그램의 지원 범위이지 모든 JPEG 보정의 원천적 불가능을 의미하지 않는다.

검토할 처리 순서는 “원본·메타데이터 확보 → 보정 상태 판정 → 검증된 모델의 역매핑/크롭 → 기존 색·LUT 편집 → JPEG 한 번 저장 → 결과 이력”이다. 렌즈의native 센서 좌표에서 계산하고 EXIF 회전을 결합하거나, 정립 좌표로 변환한 계수/광학 중심을 사용하는 방식 중 하나를 정해 미리보기/원해상도 저장을 일치시켜야 한다. 이번에는 구현하지 않았다.

## 권장 진행 순서와 중단 조건

1. **사용자 렌즈의 촬영 조건 확인:** α7C·Viltrox 26mm FE의 펌웨어, 풀프레임/APS-C·종횡비·디지털 줌·왜곡 보정 메뉴를 기록한다. 이번에는 펌웨어 업데이트나 카메라 설정 변경을 실행하지 않는다.
2. **PC에서 원본 검증:** 가능한 경우 카메라 보정 Off/Auto의 RAW+JPEG와 직선이 주변부까지 있는 장면을 확보한다. 같은 위치·거리·조리개에서 비교하며 보정 Off가 불가능하면 해당 사실을 기록한다. 미지원 메뉴를 우회하지 않는다.
3. **프로파일 적합성 비교:** Viltrox 비RAW LCP은 JPEG에, RAW LCP은 미현상 RAW에 비교 적용한다. JPEG 무보정/비RAW 프로파일 결과와 RAW 무보정/RAW 프로파일 결과를 구분한다. 이미 보정된 JPEG에는 기존 보정 재적용을 자동으로 권하지 않는다. 얼굴 개선보다 배경 직선·크롭·모서리 디테일·남는 왜곡을 먼저 본다.
4. **권리 경로 확정:** Viltrox 재배포 허가, 사용자가 직접 확보하는 LCP 입력, 자체 측정한 공개 프로파일 중 선택한다. 범용 확장은 Lensfun DB의 라이선스 준수와 지원 목록을 별도로 정한다.
5. **그때 제한된 도입 판단:** 미보정 증거와 프로파일 적합성이 확인된 사진만 기본 꺼짐의 수동 활성화 대상으로 삼는다. 별도 사진 상태·원본 재렌더·부정확한 모델 거부·오류/취소·실기 크롭/저장 검증 후 확대한다.

최우선은 앱 엔진 작성이 아니라 **α7C+Viltrox 26mm의 JPEG에 실제 남는 광학 왜곡이 있는지와 공식 비RAW LCP가 이를 개선하는지 확인하는 것**이다. 카메라 JPEG가 이미 충분히 보정됐다면 앱에서 추가 보정할 가치가 낮다. 검증 후에도 얼굴이나 배경의 자연스러움이 나빠지면 해당 프로파일 도입을 중단한다. 근거리 얼굴 복원이나85mm 재현은 성공 기준에 넣지 않는다.

## 실행한 조사와 미실행 검증

실행: 현재 앱 관련 Kotlin/DB/Gradle 읽기, 공식 웹 문서 조회, 세 렌즈의 공급원/Adobe 목록 대조, Lensfun 고정 커밋의 DB XML56개 대조, 제조사 ZIP 다운로드·목록·SHA-256·LCP XML 필드 확인, 동봉 PDF 텍스트 추출, 라이선스 원문/저장소 확인. PDF 일부 글자는 깨졌으므로 완전한 문서 판독을 주장하지 않는다. ExifTool 웹사이트403은 GitHub 소스와 README로 보완했고 GNU 웹페이지 타임아웃은 Lensfun의 COPYING.LESSER로 보완했다.

미실행: 사용자 사진 EXIF/보정 상태 판독, 원본 사진/RAW 열기, Lightroom/RawTherapee/OpenCV/Lensfun 실제 보정, 프로파일 변환, 픽셀 오차·인물 품질·처리 시간 측정, Android/NDK 빌드·자동 테스트·폰/카메라 실기, 권리자 연락·허가 확보. 새로운 코드가 없어 앱 빌드/테스트를 반복하지 않았다.0.8.6 배포 검증과 이번 조사를 구별한다.

## 주요 출처

- Viltrox 공식 FE 다운로드와 제품 페이지: 본문 직접 링크. ZIP 자료는 임시 경로에서만 읽었고 저장소/릴리즈에 포함하지 않았다.
- [Lensfun README와 라이선스](https://github.com/lensfun/lensfun/blob/37238fcef563196fb798f6151cb9023968b26c40/README.md), [고정 DB](https://github.com/lensfun/lensfun/tree/37238fcef563196fb798f6151cb9023968b26c40/data/db), [모델 API](https://lensfun.github.io/manual/latest/lensfun_8h.html), [캘리브레이션](https://lensfun.github.io/calibration/), [LCP 변환의 제약](https://lensfun.github.io/manual/latest/lensfun-convert-lcp.html).
- [Adobe 프로파일 지원/RAW와 JPEG](https://helpx.adobe.com/x-productkb/multi/lens-profile-support.html), [Adobe 보정 설명](https://helpx.adobe.com/camera-raw/desktop/using/correct-lens-distortions-camera-raw.html).
- [OpenCV 라이선스](https://opencv.org/license/), [캘리브레이션·undistort API](https://docs.opencv.org/4.13.0/d9/d0c/group__calib3d.html).
- [RawTherapee Lens Geometry](https://rawpedia.pixls.us/lens--geometry/), [LICENSE](https://github.com/RawTherapee/RawTherapee/blob/dev/LICENSE).
- [ExifTool Sony.pm](https://github.com/exiftool/exiftool/blob/master/lib/Image/ExifTool/Sony.pm), [ExifTool README](https://github.com/exiftool/exiftool/blob/master/README).
- [기존 실험 종료와 기초 조사](LENS_CORRECTION_RESEARCH.md).
