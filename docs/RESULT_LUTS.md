# LUT 세트 구축 결과

`docs/TASK_LUTS.md`, `docs/LUT-SOURCES.md` 범위의 내장 LUT 27종을 완성했습니다.
작업 기준 commit은 `20a9fe7`, 작업 브랜치는 `feat/luts`입니다. `app/`는 수정하지 않았습니다.

## 산출물

- `assets/luts/`: sRGB 입력→sRGB 출력 33³ RGB uint16 LE 바이너리 27개.
  컬러 네거 8 / 슬라이드 6 / 시네마 인화 3 / B&W 5 / 크리에이티브 5.
  바이너리 합계 **5,822,226 bytes**, 파일당 **215,638 bytes**.
- `assets/luts/manifest.json`: 고정 id·서술적 표시명·분류·저작자·SPDX·원본
  저장소/commit/파일/SHA256·생성 파라미터·변환 방법·산출물 SHA256.
- `assets/luts/NOTICE`: 두 MIT LICENSE와 저작권 고지, CC0 법문 원문.
- `assets/luts/previews/`: 직접 생성한 합성 이미지와 27종 썸네일, 비교표.
- `tools/luts/`: 프로젝트 내부 uv 가상환경용 버전·해시 고정 requirements,
  생성/변환·검증·보고서 스크립트와 8개 테스트. `.venv`, 외부 clone, `.cube` 중간본은 Git 제외.
  CC0 원본 5개는 파일별 권리 고지를 확인하고 gzip으로 보관했습니다.
- [형식·소비 계약](LUT-FORMAT.md), [실측 수치 보고서](LUT-VALIDATION.md),
  [재생성 명령](../tools/luts/README.md), `tools/luts/reproducibility.json`.

## 출처 원문 재확인

- Spectral Film LUT: `a15043f6fe2d365d0c359d22e0868076800916d9`, MIT, Jan Lohse, 15종.
- t3mujinpack: `0b421f3e25209ed78253d1724a29cc6255c5e7fe`, MIT, João Almeida, 7종.
- EditorHub Originals: 각 `.cube` 헤더의 EditorHub/CC0 선언, 카탈로그 항목과
  원본 URL·SHA256을 파일별 확인, 5종. Git commit은 없으므로 manifest에 null로 명시.

LICENSE 원문 사본은 `tools/luts/licenses/`, 해시는 `sources.lock.json`에 있습니다.
미확인 FreshLUTs 커뮤니티 파일과 제3자 권리가 섞인 Film-Luts 자료는 포함하지 않았습니다.
필름 상표명은 계열 설명이며 원본 필름의 재현 또는 상표권자의 보증을 주장하지 않습니다.

## 실행 검증

환경: Linux x86_64, CPython 3.12.14, uv 0.12.13. 아래 명령을 실제 실행했습니다.

| 검증 | 실행 결과 |
| --- | --- |
| `uv pip sync --python tools/luts/.venv/bin/python --require-hashes tools/luts/requirements.txt` | 17개 의존성 고정 설치 |
| `uv pip check --python tools/luts/.venv/bin/python` | 17개 호환성 PASS |
| `uv run --no-project --python tools/luts/.venv/bin/python tools/luts/build.py` | 27종 생성·품질 게이트 PASS |
| `uv run --no-project --python tools/luts/.venv/bin/python python -W error::ResourceWarning -m unittest discover -s tools/luts -v` | 8 tests PASS |
| `uv run --no-project --python tools/luts/.venv/bin/python tools/luts/verify.py` | 분류·헤더·길이·해시·썸네일·NOTICE·리포트 연결 PASS |
| `build.py --output tools/luts/.work/rebuild` 전체 재생성 후 모든 파일 SHA256 비교 | **59/59 일치**, 차이 0, 양쪽 무결성 오류 0 |

테스트에는 비대칭 RGB 축 순서, 끝점·clamp, 비선형 보간의 독립 구현 비교,
16bit Hald 보존, 잘못된 헤더 거부와 **손상된 LUT/누락된 썸네일 동시 검출**을 포함했습니다.
초기 검증에서 PNG 파일 핸들 ResourceWarning을 수정하고 경고를 오류로 처리해 재실행했습니다.
완전 무채색만 허용한 초기 B&W 검사는 원본 RGB 반올림 차이를 검출했습니다.
다섯 원본 전체 격자를 확인해 한 원본 LSB(1/255) 이내임을 입증한 후 원본 정밀도에 맞춰 검사했습니다.

룩별 **10,545개 합성 패치**, 회색 램프 1025단계, RGB 모서리 8개를 평가했습니다.
Spectral은 upstream 변환을 직접 적용한 결과와 비교하고, Hald/CUBE는 원본 전체 격자를
별도 SciPy 보간기로 평가했습니다. 원본 대비 ΔE00 평균은 0.00025~0.08706,
가장 큰 p95는 **0.39326**, 최대는 **5.77740**입니다.
uint16 양자화만의 최대 ΔE00은 **0.00361 미만**, 최대 RGB 오차는 **0.5/65535 이내**입니다.
0/1 모서리 출력은 모두 유한하고 [0,1] 범위이며 원본과의 최대 RGB 차이는 0.00000761 미만입니다.

## 남은 점·경계

앱 연동, 앱 GPU, Darktable 스타일/GUI, 카메라 실기와의 비교는 수행하지 않았습니다.
이 전달물은 CPU 합성 패치 기반의 LUT 생성·변환 검증입니다.
33³ 보간으로 극단 색상의 최대 색차가 평균보다 커질 수 있습니다.
회색에 룩 고유의 색 편향이 있으며 원본 또는 변환 과정에서 작은 휘도 역전이 있습니다.
내장 회색 최소 ΔY는 약 -0.00000532로, 보정 없이 보고했습니다.
모든 경계·회색 오차·단조성 수치를 `assets/luts/validation.json`에 남겼습니다.
실기 로그·개인 사진·자격증명·재배포 불명확 자료는 포함하지 않았습니다.

최종 완료 commit은 응답의 `LUTS-READY @<sha8>`로 식별합니다.
