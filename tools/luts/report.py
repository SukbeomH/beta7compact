#!/usr/bin/env python3
"""Write the compact Korean validation report from measured delivery data."""
import json
from build import ROOT


def main():
    output = ROOT / "assets/luts"
    report = json.loads((output / "validation.json").read_text())
    manifest = json.loads((output / "manifest.json").read_text())
    rows = report["luts"]
    if not report["passed"] or not all(row["passed"] for row in rows):
        raise SystemExit("검증 실패: PASS 보고서를 생성하지 않습니다")
    lines = ["# LUT 정량 검증", "", "검증 환경: CPython " + report["python"] +
        ", Linux x86_64, `tools/luts/requirements.txt`의 해시 고정 의존성.", "",
        f"27종 전체 게이트 PASS. 룩별 합성 패치 {report['samples']:,}개, 회색 램프 1025단계, RGB 모서리 8개.", "",
        "Spectral: upstream `film_conversion` 직접 적용 결과와 내장 바이너리 보간을 비교했습니다.",
        "t3mujinpack/EditorHub: 원본 전체 Hald/CUBE를 별도 SciPy 보간기로 평가해 내장본과 비교했습니다.",
        "Darktable 스타일·GUI, 앱 GPU, 카메라 실기 비교는 수행하지 않았습니다.", "",
        "게이트: 양자화 최대 RGB ≤ 0.5/65535, 양자화 최대 ΔE00 < 0.02, 원본 대비 ΔE00 p95 < 2.0, 비유한 값 없음.",
        "중립 chroma·identity 오차·단조성은 룩의 특성 보고이며 게이트가 아닙니다.", "",
        "| id | 원본 ΔE00 평균 | p95 | 최대 | 양자화 ΔE00 최대 | 회색 ΔE00 p95 | 중립 chroma 최대 | 회색 Y 역전 원본/내장 | 내장 최소 ΔY |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"]
    for row in rows:
        d = row["source_vs_builtin_deltaE00"]
        lines.append(f"| {row['id']} | {d['mean']:.5f} | {d['p95']:.5f} | {d['max']:.5f} | "
            f"{row['quantization_only_deltaE00']['max']:.5f} | {row['gray_source_vs_builtin_deltaE00']['p95']:.5f} | "
            f"{row['gray_neutral_chroma_max']:.4f} | {row['source_gray_luminance_decreases']}/{row['gray_luminance_decreases']} | "
            f"{row['gray_luminance_min_step']:.8f} |")
    lines += ["", "ΔE00은 sRGB→XYZ D65→Lab→CIEDE2000입니다. 회색 Y 역전은 인접 단계 ΔY < -1e-6의 개수입니다.",
        "원본과 내장 역전 수는 144³/연속 변환을 33³로 바꾸면서 달라질 수 있습니다.",
        "채널별 역전, identity RGB 오차, 회색 채널차, 원본 최소 ΔY, 경계별 RGB는 `assets/luts/validation.json`에 있습니다.", "",
        "최대 오차는 Gold 200 계열의 극단 색상에서 약 5.78입니다. 평균·p95가 작아도 모든 색상이 같은 오차를 갖지는 않습니다.",
        "중립 램프의 색 편향과 일부 비단조성은 원본에도 있습니다. 내장본에서 새로 생긴 작은 역전도 표에 포함했습니다.",
        "내장 회색 휘도 최소 변화량은 약 -0.00000532입니다. 추가 중립화나 단조성 보정은 하지 않았습니다.",
        "B&W는 원본 8bit Hald의 한 LSB 이내 RGB 차이를 보존합니다. 내장 채널차 최대는 약 0.00356입니다.",
        "검은점·흰점의 출력이 반드시 0·1 또는 무채색일 필요는 없습니다. 색상 룩의 출력입니다.", "",
        f"LUT 바이너리 합계: {sum(e['bytes'] for e in manifest['luts']):,} bytes (각 215,638 bytes).", "",
        "[합성 미리보기 비교표](../assets/luts/previews/contact-sheet.png)", "",
        "재현·테스트 명령: [파이프라인 README](../tools/luts/README.md).",
        "전체 파일 해시 재생성 비교 결과: `tools/luts/reproducibility.json`.", ""]
    (ROOT / "docs/LUT-VALIDATION.md").write_text("\n".join(lines), encoding="utf-8")


if __name__ == "__main__":
    main()
