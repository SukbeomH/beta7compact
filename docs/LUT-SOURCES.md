# beta7compact 내장 LUT 조사 (2026-11-14)

전제: 저장소가 public이고 앱 배포 가능성 → 재배포 허용 라이선스만 내장. 라이선스는 각 출처의 LICENSE 원문으로 다시 확인 후 NOTICE에 기록(아래는 조사 요약, GRKU docs/lut-sources.md·LUTr OPEN_SOURCE_LUT_COLLECTIONS.md 교차).

## LUT 종류 (앱 관점)
- 입력 색공간: **크리에이티브(sRGB/Rec.709 입력)** — A7C JPEG에 바로 적용. **기술 LUT(S-Log3→709 등)** — 로그 영상/사진용, JPEG에는 부적합.
- 차원: 1D(톤 커브만) / **3D(색 전체, .cube 17·33·65)**. 사진용 기본 33³.
- 파일 형식: `.cube`(텍스트, 표준), Hald CLUT(PNG/TIFF 이미지에 3D LUT를 펼친 것, 무손실 변환 가능), `.3dl`.
- 용량: 33³ .cube 텍스트 ≈ 0.9MB → 앱 내부는 16bit 바이너리/PNG Hald로 압축 저장(≈100~250KB), 사용자 가져오기/내보내기는 .cube.

## 후보
| 출처 | 내용 | 라이선스 | 판단 |
|---|---|---|---|
| Spectral Film LUT (JanLohse/spectral_film_lut) | 생성기 → .cube. 네거·리버설·인화 약 75종(Portra/Ektar/Gold/Superia/Velvia/Provia/Kodachrome/Tri-X 등), 입력 공간 선택 | MIT | **1순위** — sRGB 입력 33³로 직접 생성 |
| t3mujinpack (t3mujin) | 51종 필름 Hald CLUT(sRGB), B&W 포함 | MIT | **2순위** — Hald→.cube 변환 |
| EditorHub Originals / FreshLUTs 커뮤니티 | 크리에이티브 룩 | CC0 | 3순위 — 개별 파일 출처 확인 후 |
| Striped Purple | 크리에이티브 49종 .cube | MIT | 선택 |
| spektrafilm | 필름+인화 프로파일/LUT | CC BY-SA 4.0 (저작자·URL·변경 표기 필수, 동일조건) | 선택 — 고지 의무 큼 |
| Natron HaldCLUT | 347 Hald | CC BY-SA 4.0 | 보류(출처 혼재 확인 필요) |

## 제외
- G'MIC Film-Luts(MIT 표기지만 제3자 소유 고지), RawTherapee 필름 시뮬(출처 불명확), cedeber/hald-clut(재라이선스 근거 없음).
- 상업/재배포 금지: RocketStock, PremiumBeat, VSCO, Dehancer, FilmBox, Mastin, DxO 등.
- Sony Look Profile(S-Log3): 재배포 조건 미확인 + JPEG 입력에 부적합 → 내장 안 함(사용자 가져오기로만).
- 필름 이름은 상표 — "Portra 400 계열" 등 서술적 사용, 원본 재현 주장 금지.

## 제안 구성 (초기 내장 세트 20~30종)
- 컬러 네거 8, 슬라이드 6, 시네마 인화 3, B&W 5, 크리에이티브 5
- 빌드: `tools/luts/`에 생성·변환 스크립트(재현 가능), 결과물 + `assets/luts/manifest.json`(이름, 분류, 출처, 라이선스, 원본 URL·커밋, 변환 방법) + `NOTICE`
- 검증: 데스크톱 기준 적용 결과와 앱 GPU 결과 ΔE 비교, 중립 회색 램프 보존 확인
