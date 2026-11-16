# TASK M5b: RAW(ARW) 직접 현상

먼저 읽기: docs/ARCHITECTURE.md, docs/M2-ENGINE.md, docs/RESULT_M2.md, docs/RESULT_M5A.md. 대상: Sony ILCE-7C(fw 2.03) ARW. 외부 라이브러리 추가 금지(LibRaw/dcraw/rawspeed 등 코드 복사·포팅 금지 — GPL/LGPL). 공개 사양·문서·논문만 근거로 직접 구현하고 출처를 docs/RAW-DEVELOP.md에 기록.

## 범위
1. ARW 파서(TIFF/EP 기반): IFD 순회(크기·오프셋 상한, 순환 방지), RAW IFD 찾기, 지원 압축 판별.
   - 지원: 비압축 14bit, 소니 손실 압축 RAW(32픽셀 블록, 11bit+7bit 델타·톤커브 — 공개 문서 근거). A7C에서 쓰는 나머지 형식은 "미지원(사유)"로 거부하고 내장 JPEG 경로로 대체 안내.
   - 블랙/화이트 레벨, CFA 패턴, 크롭(DefaultCrop 계열), 방향, 촬영 WB 계수(MakerNote/SR2; 암호화 영역은 공개 알고리즘 문서 근거만, 근거 불충분하면 회색 세계/프리셋 WB로 대체하고 기록).
2. 현상 파이프라인(CPU 참조 + GPU 또는 타일 병렬 CPU, 원해상도 24MP): 블랙 차감·정규화 → WB → 디모자이크(최소 AHD 또는 RCD/양선형+보정, 알고리즘·논문 출처 기록) → 카메라→XYZ→선형 sRGB 색 행렬(ILCE-7C 행렬의 출처·라이선스를 문서화; 출처가 재배포 불명확하면 사용자가 DCP/행렬을 가져오는 방식 + 근사 기본값 표시) → 하이라이트 클리핑 처리(복원 옵션) → 노출 → 출력 sRGB 감마 → 기존 M2/M5a 레시피 파이프라인 연결(16bit/float 중간 정밀도 유지).
3. UI: ARW 편집 시 "RAW 현상"/"내장 JPEG" 전환(기본 RAW, 미지원·실패 시 내장 JPEG 자동 + 한 줄 사유), 미리보기는 축소 현상(반해상도 디모자이크 등)으로 빠르게, 저장은 원해상도. RAW+JPEG 쌍에서 RAW 우선 옵션. 일괄 적용 지원.
4. 메모리/성능: 24MP 14bit 처리 메모리 상한(타일), Fold8 저장 시간 목표 문서화, OOM·손상 파일 한 줄 사유. 악성/손상 ARW로 크래시·무한루프·메모리 폭주 없음.

## 검증
- JVM: 합성 ARW(직접 생성한 TIFF 컨테이너 + 비압축/손실압축 인코더로 만든 알려진 패턴) 디코드 비트 일치, 손상·악성 입력(순환 IFD, 거대 오프셋, 잘린 스트립) 거부, 디모자이크 기준 패턴(평탄 색·가장자리) 오차 상한, 색 행렬 적용 기준값, WB 계수 파싱.
- 실기 ARW 표본은 저장소에 넣지 않는다(개인 사진). 대신 androidTest/디버그 화면에 "로컬 ARW 진단"(파일 선택 → 형식·크기·레벨·WB·처리시간만 표시, 픽셀·파일명 로그 금지)을 넣어 사용자가 실기에서 확인할 수 있게.
- ./gradlew assembleDebug test lint, 기존 마이그레이션 검사들, androidTest 빌드.
- versionCode 6 / 0.4.0. APK는 dist/ 빌드만.

## 경계
브랜치 feat/m5b-raw-develop(master 7a30334에서), 원자 커밋, 이 브랜치만 push. force·--no-verify·git add -A·APK 커밋 금지. 원본 보존·M1/M2/M5a 불변식 유지. 결과 docs/RESULT_M5B.md, 완료 시 M5B-READY @<sha8>. 근거 부족으로 막히는 항목은 BLOCKED로 명시하고 나머지는 완료.
