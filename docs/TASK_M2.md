# TASK M2: 레시피 엔진 (LUT 우선)

먼저 읽기: docs/ARCHITECTURE.md, docs/LUT-SOURCES.md, docs/LUT-FORMAT.md, assets/luts/manifest.json, docs/RESULT_012.md.
결정: LUT 우선 → 편집기(톤커브·HSL)는 M5. BLE는 보류(건드리지 말 것).

## 범위
1. 엔진(GPU, OpenGL ES 3.0 셰이더 또는 동등): 입력 디코드 → (선택) 입력 변환 → 3D LUT(사면체 또는 삼선형 보간, 문서화) + 강도(0~100%) → WB(온도/틴트) → 노출(EV) → 그레인(크기·강도, 휘도 의존, 시드 고정 재현 가능) → 비네트 → 출력 sRGB.
   - 내장 LUT 27종(assets/luts 바이너리, LUT-FORMAT.md 준수)과 사용자 `.cube` 가져오기(SAF, 크기 2~65, DOMAIN_MIN/MAX, TITLE; 잘못된 파일은 한 줄 사유로 거부). 가져온 LUT는 앱 내부 저장소에 같은 바이너리 형식으로 저장.
   - 입력 변환 옵션: "S-Log3/S-Gamut3.Cine JPEG" → 소니 공개 수식으로 선형화 + 색역 변환 → sRGB/Rec.709 디스플레이 공간 (수식·행렬 출처 URL을 docs/SLOG3.md에 기록, 소니 LUT 파일 사용 금지). 기본은 꺼짐.
   - CPU 참조 구현(Kotlin, JVM 테스트용)과 GPU 결과 일치 검증 경로. 
2. 레시피 = 직렬화 가능한 파라미터 묶음(JSON: lutId, strength, inputTransform, wb, ev, grain, vignette, version). Room: 레시피 저장·목록·즐겨찾기, 원본 사진 ↔ 결과 연결(결과 URI, 레시피 JSON 스냅샷, 생성 시각).
3. UI: 라이브러리 사진 탭 → 편집 화면(축소본 실시간 미리보기, LUT 썸네일 가로 목록(분류별), 슬라이더, 전/후 비교 길게 누르기), [저장] → 원해상도 처리 후 새 JPEG(DCIM/beta7compact/Recipes/, 품질 95, 원본 EXIF 복사하되 방향 정규화, Software 태그 기록). 원본은 절대 수정·덮어쓰기 금지.
   - 여러 장 선택 → 같은 레시피 일괄 적용(WorkManager, 진행률).
   - 가져오기 후 "기본 레시피 자동 적용" 설정(기본 꺼짐).
   - RAW(ARW): M2에서는 카메라 내장 미리보기 JPEG 추출 후 적용만 지원(풀 RAW 현상은 M5), 화면에 그 사실 표시.
4. 성능: Fold8 기준 24MP 저장 수 초 이내 목표, 메모리 상한(타일 처리 또는 다운샘플 없는 원해상도 처리 방안 문서화), OOM 시 한 줄 사유.

## 검증
- JVM: .cube 파서(정상/비정상), LUT 보간 vs 기준(ΔE), 레시피 직렬화 왕복, S-Log3 수식 기준값, 그레인 시드 재현성.
- 계측(가능하면 Robolectric/JVM GL 대체 불가 시 CPU 참조로): GPU-CPU 일치 테스트 코드 포함(기기에서 실행 가능한 androidTest).
- ./gradlew assembleDebug test lint, tools/check_m1_migration.py 및 새 마이그레이션 검사.
- versionCode 4 / 0.2.0, APK는 dist/ 빌드만(커밋 금지).

## 경계
브랜치 feat/m2-recipe-engine(master a41be3f에서), 원자 커밋, 이 브랜치만 push. force·--no-verify·git add -A·APK 커밋 금지. M1 불변식(원본 보존, 중복·해시 규칙, 카메라 쓰기 0, 로그 개인정보 0) 유지. 외부 라이브러리 추가 금지(AndroidX·플랫폼 API만). 결과 docs/RESULT_M2.md, 완료 시 M2-READY @<sha8>.
