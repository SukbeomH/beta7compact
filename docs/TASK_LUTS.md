# TASK: beta7compact 내장 LUT 세트 구축 (사용자 승인 2026-11-14 "진행")

조사 요약: docs/LUT-SOURCES.md (이 브랜치에 추가할 것, 원본 /home/agent/camera-recipe-app/docs/LUT-SOURCES.md).

## 범위 (앱 코드 아님 — 병렬로 M1 세션이 app/ 을 작업 중이므로 app/ 수정 금지)
- tools/luts/: 재현 가능한 생성·변환 파이프라인(Python, 프로젝트 내부 uv venv tools/luts/.venv, 전역 설치 금지, 의존성은 requirements 고정·해시 기록).
- 출처를 직접 clone/다운로드해 **LICENSE 원문을 읽고** 재확인. 불명확하면 제외하고 기록.
  1. Spectral Film LUT(JanLohse/spectral_film_lut, MIT): 생성기로 sRGB 입력→sRGB 출력 33³ .cube 생성(네거+인화 조합, 슬라이드, B&W, 시네마 인화). 생성 파라미터 고정.
  2. t3mujinpack(MIT): Hald CLUT PNG → 33³ .cube 변환(보간 방법 명시).
  3. CC0 크리에이티브: 파일별 출처 확인 가능한 것만.
- 초기 세트 20~30종: 컬러 네거 8, 슬라이드 6, 시네마 인화 3, B&W 5, 크리에이티브 5. 이름은 서술적("Portra 400 계열" 식), 원본 재현 주장 금지.
- 산출물 assets/luts/: (a) 앱 내장용 16bit 바이너리 또는 16bit PNG Hald(형식 사양 docs/LUT-FORMAT.md), (b) 원본 .cube는 저장소 크기 고려해 생성 스크립트로 재생성 가능하면 커밋 제외 가능, (c) manifest.json(id, 표시이름, 분류, 출처 repo·커밋 SHA·파일, 라이선스 SPDX, 저작자, 변환 방법, sha256), (d) NOTICE(각 라이선스 원문·저작권 고지).
- 검증: 중립 회색 램프 보존 오차, 0/1 경계, 단조성 리포트; 원본 도구 적용 vs 변환본 적용 ΔE(CIEDE2000) 평균·p95 리포트(샘플 이미지는 직접 생성한 합성 패치). 미리보기 썸네일 생성(합성 이미지).
## 경계
브랜치 feat/luts(현재 체크아웃) 원자 커밋, 이 브랜치만 push. master push·PR·merge·force·--no-verify·git add -A 금지. 실기 로그·개인 사진 커밋 금지. 재배포 불가 자료 커밋 금지. 완료 시 docs/RESULT_LUTS.md 작성 후 LUTS-READY @<sha8>.
