# LUT 생성·변환 파이프라인

`docs/TASK_LUTS.md` 범위만 처리합니다. 앱 변경 없이 sRGB → sRGB 33³ LUT 27종을 생성합니다.
외부 소스는 원문 LICENSE를 읽고 고정했습니다. 필름 이름은 서술적인 계열명입니다.

## 재생성

저장소 루트에서 실행합니다. CPython **3.12.14**, uv **0.12.13**, Linux x86_64에서 검증했습니다.
다른 플랫폼은 수치 라이브러리에 따른 미세한 반올림 차이가 있을 수 있습니다.

```bash
uv venv --python 3.12.14 tools/luts/.venv
uv pip sync --python tools/luts/.venv/bin/python --require-hashes tools/luts/requirements.txt
uv run --no-project --python tools/luts/.venv/bin/python tools/luts/build.py
uv run --no-project --python tools/luts/.venv/bin/python python -W error::ResourceWarning -m unittest discover -s tools/luts -v
uv run --no-project --python tools/luts/.venv/bin/python tools/luts/verify.py
```

`requirements.txt`는 전이 의존성까지 버전과 배포 파일 SHA256을 고정합니다.
`requirements.in`은 잠금 갱신 입력이며, 재현 빌드에서 다시 resolve하지 않습니다.
Qt/GUI 의존성은 설치하지 않고 원본 headless 엔진을 그대로 import합니다.
부수적 전역 설치나 앱 의존성 변경은 없습니다.

최초 실행은 두 Git 저장소를 `.sources/`에 내려받고 `sources.lock.json`의 정확한
commit으로 checkout합니다. 기존 checkout의 HEAD·tracked 수정·LICENSE SHA256이
다르면 중단합니다. 생성 파라미터는 `build.py:PARAMETERS`와 manifest에 전부 기록합니다.
film 데이터의 기본 color_masking은 실제 값으로 기록합니다. 필터·oversampling 없음,
노출 보정 0, WB/관찰광 6500K, gray 0.18, 강도 1, 노이즈·그레인·할레이션 없음입니다.

- Spectral 15종: 네거+Endura 인화 8, 슬라이드 4, Vision3 250D+시네마 인화 3.
- t3mujinpack 7종: 슬라이드 2, B&W 5. Hald level 12 / 144³ / RGB 8bit 원본.
  Hald의 red-fastest 배열을 펼치고 **인코딩된 sRGB trilinear**로 33³ 리샘플합니다.
  PNG ICC는 sRGB이며 숫자를 LUT로 취급하고 ICC 재변환은 하지 않습니다.
- EditorHub Originals CC0 5종: 파일 헤더 각각에 작성자·CC0 선언이 있습니다.
  `sources/editorhub/*.cube.gz`는 원본 바이트를 mtime=0 gzip으로 보관합니다.
  `provenance.json`은 파일별 카탈로그 항목·헤더·URL·조회일·해시의 확인 기록입니다.
  원본 33³를 읽어 uint16으로 양자화합니다. 커뮤니티·Film-Luts 자료는 쓰지 않습니다.

## 출력과 검증

`assets/luts/`에 바이너리, manifest, NOTICE, validation, 합성 썸네일을 씁니다.
`.cube` 중간 산출물은 `.work/cubes/`에 생성하고 Git에서는 제외합니다.
형식 계약은 `docs/LUT-FORMAT.md`입니다. `--output PATH`로 별도 디렉터리에
전체 재생성하여 각 파일의 SHA256을 비교할 수 있습니다.

합성 패치: 17³ 정규 격자 + seed 7133 난수 4096 + 합성 이미지 표본 1536 = 10,545개.
별도로 회색 램프 1025단계와 RGB 8개 모서리를 평가합니다.

Spectral은 upstream `film_conversion`을 각 입력에 직접 적용한 결과와 비교합니다.
Hald/CUBE는 원본 전체 격자를 **별도 SciPy trilinear 구현**으로 적용해 비교합니다.
이는 원본 Hald 데이터의 수치 비교이며, Darktable 스타일 모듈 또는 GUI의 재현 검증은 아닙니다.
33³ 리샘플 오차와 uint16 양자화 오차를 별도 보고합니다. ΔE00은
colour-science의 sRGB→XYZ D65→Lab, CIEDE2000입니다.

품질 게이트는 양자화 RGB 최대 `0.5/65535`, 양자화 ΔE00 최대 0.02,
원본 대비 ΔE00 p95 < 2.0, 비유한 값 없음입니다. 회색의 원본 대비 색차,
중립색에서의 chroma·채널차·identity 오차·휘도 단조성·채널별 역전도 보고합니다.
룩 자체의 색 편향과 비단조성을 자동 보정하지 않습니다. 원본과 변환본의
회색 휘도 역전 수·최저 변화량을 함께 기록합니다. 최대 오차를 p95로 숨기지 않습니다.

`verify.py`는 다운로드 없이 27종 수량·분류, 헤더, 파일 길이, SHA256,
썸네일, B&W 채널 차이, 검증 리포트 연결과 NOTICE 원문 포함을 확인합니다.
B&W 원본 8bit Hald에는 최대 한 원본 LSB(1/255)의 RGB 반올림 차이가 있어
이를 보존합니다. 검사 한계는 `1/255 + 1/65535`이며 임의로 완전 무채색화하지 않습니다.
CPU 검증만 수행했으며 앱 GPU, 카메라 실기, 필름 원본 재현은 보증하지 않습니다.
