# S-Log3 / S-Gamut3.Cine JPEG 입력 변환

M2 기본값은 `NONE`입니다. 로그로 촬영한 JPEG에만 사용자가 켭니다.
일반 sRGB JPEG, 이미 변환된 JPEG, S-Gamut3(비 Cine), 비디오 limited-range를 자동 판별하지 않습니다.
Sony LUT/Look Profile 파일을 내려받거나 앱에 넣지 않았습니다.

## 근거

- Sony, [Technical Summary for S-Gamut3.Cine/S-Gamut3/S-Log3, Appendix pp.6–7](https://pro.sony/s3/cms-static-content/uploadfile/06/1237494271406.pdf): 역 S-Log3 수식, full-range, 색도/행렬.
- Sony, [일본어 Technical Summary V1.0](https://www.sony.jp/ls-camera/knowledge/pdf/TechnicalSummary_for_S-Gamut3Cine_S-Gamut3_S-Log3_V1_00.pdf): 동일 기술 자료.
- 출력 전달함수: [W3C CSS Color 4 sample conversion code](https://www.w3.org/TR/css-color-4/#color-conversion-code), sRGB.

JPEG의 8bit 채널을 `v=channel/255`로 읽고 full-range 10bit 정규화 코드에 대응시킵니다.
법정 범위 64–940 재확장이나 JPEG에 없는 정밀도 복원은 하지 않습니다.
Sony 문서의 저조도 역수식 인쇄 오자 `out +`는 앞의 순방향 선형식의 대수적 역으로 해석했습니다.

## 처리

`v >= 171.2102946929/1023`이면 선형 반사율은
`10^((1023*v-420)/261.5)*0.19-0.01`입니다.
그 아래는 `(1023*v-95)*0.01125/(171.2102946929-95)`입니다.
검사 기준은 black `95/1023 → 0`, grey `420/1023 → 0.18`, toe `171.2102946929/1023 → 0.01125`와 white 반사율 `0.9`입니다.

S-Gamut3.Cine 원색 xy = R(0.766,0.275), G(0.225,0.800), B(0.089,-0.087),
D65(0.3127,0.3290). 각 원색을 `(x/y,1,(1-x-y)/y)` 열로 만들고 D65에 맞춰 열을 스케일합니다.
이 RGB→XYZ 행렬에 sRGB/BT.709 원색 R(.64,.33), G(.30,.60), B(.15,.06)의
동일한 RGB→XYZ 행렬의 역을 왼쪽에서 곱했습니다. 두 공간의 백색이 같아 색순응은 없습니다.
계산한 **선형 S-Gamut3.Cine → 선형 sRGB** 행렬은 다음과 같습니다.

```text
 1.626947410 -0.540138539 -0.086808871
-0.178515527  1.417940927 -0.239425400
-0.044436115 -0.195919966  1.240356081
```

행렬을 곱한 후 `l<=0.0031308`은 `12.92*l`, 그 밖에는
`1.055*l^(1/2.4)-0.055`로 sRGB 인코딩하고 [0,1]로 제한합니다.
그 다음 크리에이티브 LUT를 적용합니다. RGB 원색은 Rec.709와 같으며 전달함수는 sRGB입니다.
톤매핑/하이라이트 복구/추가 film look은 없습니다. 범위 밖 색과 하이라이트는 클립됩니다.
CPU `CpuEngine`과 GPU `GpuEngine`이 같은 상수·분기·순서를 사용합니다.
JVM의 수식 기준값 검증과 기기의 GPU/CPU 비교 코드는 실기 색 재현 검증과 별개입니다.
