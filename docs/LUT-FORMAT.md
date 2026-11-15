# 내장 LUT 형식: B7LT v1

이 문서는 LUT 산출물의 소비 계약입니다. 내장 LUT 생성 작업에서는 앱 코드를 변경하지 않았습니다.
M2 사용자 LUT 확장은 마지막 절에 기록합니다.
27종의 표시 이름·분류·출처·라이선스·경로·SHA256은 `assets/luts/manifest.json`에 있습니다.
필름 이름은 룩의 계열을 설명하며 필름 원본의 정확한 재현이나 상표권자의 보증을 뜻하지 않습니다.

## 색상과 보간

- 입력·출력은 모두 **감마가 인코딩된 sRGB, D65, RGB [0,1]**입니다.
- sRGB와 Rec.709의 원색 좌표는 같지만 전달함수는 다릅니다. 생성기의 출력 gamut은
  `Rec. 709`, 출력 gamma는 **sRGB**로 명시했습니다. Rec.709 gamma나 로그 입력이 아닙니다.
- 33×33×33 균일 격자. 각 축 입력은 `i / 32`, i=0..32입니다.
- 입력을 [0,1]로 clamp한 다음 **인코딩된 sRGB 값에서 trilinear 보간**합니다.
  LUT를 적용하기 전에 linear sRGB로 변환하지 않습니다. NaN/Inf는 거부합니다.
- 출력 채널은 uint16 / 65535.0으로 복원합니다. intensity 1의 전체 룩입니다.
  강도 조절을 지원한다면 같은 sRGB 영역에서 원본과 보간합니다.
- RGB만 변환합니다. 알파는 소비자가 보존합니다. premultiplied alpha 픽셀은
  unpremultiply 후 LUT를 적용하고 다시 premultiply해야 합니다.
- GPU의 sRGB 텍스처 자동 디코딩을 사용하면 이 계약과 달라집니다. 정규화된 정수
  텍스처 또는 선형 형식에 인코딩 값을 그대로 올리고 texel 중앙을 맞춰야 합니다.
  3D 텍스처 정규화 좌표는 `(clamp(rgb,0,1)*32 + 0.5)/33`입니다.

## 바이너리 레이아웃

각 `.lut16` 파일은 압축되지 않은 **215,638 bytes**입니다. 전역 정수는 little endian입니다.

| offset | bytes | 의미 |
| --- | ---: | --- |
| 0 | 4 | ASCII `B7LT` |
| 4 | 2 | uint16 버전 = 1 |
| 6 | 2 | uint16 격자 크기 = 33 |
| 8 | 4 | uint32 채널 수 = 3 |
| 12 | 4 | uint32 예약 = 0 |
| 16 | 215,622 | RGB interleaved uint16 little endian |

격자점 순서는 **R축이 가장 빠르고, 다음 G, 마지막 B**입니다. 채널 순서도 RGB입니다.
샘플 `(r,g,b)`, 채널 `c=0,1,2`의 바이트 오프셋:

```text
16 + 2 * (3 * (r + 33*g + 33*33*b) + c)
```

인코딩은 `round_to_nearest_even(clamp(value,0,1)*65535)`입니다. 생성기는
범위 밖 값과 비유한 값을 거부하며, 조용히 잘라서 오류를 숨기지 않습니다.
로더는 magic/version/size/channels/reserved, 정확한 파일 길이와 manifest SHA256을
검사해야 합니다. native endian이나 BGR로 해석하면 안 됩니다.

## manifest와 미리보기

`schema_version=1`, `luts`는 표시 순서가 고정된 배열입니다. id는 파일명 기반의 영문 식별자입니다.
분류는 `color_negative` 8, `slide` 6, `cinema_print` 3, `bw` 5, `creative` 5입니다.
`source.repo`, `source.commit`, `source.files`는 원본 추적용이며 앱에서 접근할 필요가 없습니다.
EditorHub는 Git 저장소가 없어 repo/commit이 null이며 URL·원본 SHA256·보관한 gzip 파일로 고정합니다.

`previews/*.png`는 직접 생성한 합성 sRGB 이미지에 LUT를 적용한 8bit RGB 썸네일입니다.
`input.png`가 원본, `contact-sheet.png`가 비교표입니다. 미리보기 자체는 LUT 데이터가 아닙니다.
실사진·실기 로그·개인 자료는 포함하지 않습니다. 배포 시 `assets/luts/NOTICE`를 함께 유지합니다.

## 참조 구현과 검증 경계

`tools/luts/build.py`의 `decode`, `sample`이 CPU 참조입니다. `test_pipeline.py`는
축·채널 순서, 끝점, clamp, 비선형 보간, 16bit Hald 보존, 잘못된 헤더를 검사합니다.
정량 결과는 `assets/luts/validation.json`과 `docs/LUT-VALIDATION.md`에 있습니다.
CPU 합성 패치 검증이며 앱 GPU·Darktable GUI·카메라 실기 결과와의 일치는 미검증입니다.

## M2 사용자 LUT 소비 확장

내장 27종의 bytes/SHA/33³ 계약은 그대로입니다. M2 로더는 동일 B7LT-v1 헤더에서
size 2~65, 정확한 길이 `16+6*size³`도 허용합니다. 사용자 `.cube`는 SAF로 읽고
red-fastest RGB uint16 B7LT로 저장합니다. 값 범위는 [0,1], 양자화는 round-to-nearest-even입니다.
TITLE, DOMAIN_MIN/MAX는 앱 내부의 같은 ID `.json` sidecar에 보관합니다.
DOMAIN은 생략 시 [0,1], 채널별 max>min·유한수이며 보간 좌표는
`clamp((rgb-DOMAIN_MIN)/(DOMAIN_MAX-DOMAIN_MIN),0,1)*(size-1)`입니다.
원래 크기/격자를 보존하며 DOMAIN 재격자화에 따른 경계 근사는 없습니다.
바이너리만 다른 곳으로 복사하면 비기본 DOMAIN/TITLE을 복원할 수 없으므로 sidecar와 한 쌍입니다.
가져온 뒤에는 SAF 원문에 재접근하지 않습니다. 원래 TITLE/DOMAIN/격자는 sidecar와 B7LT로 유지합니다.

중복/잘못된 헤더, 1D/혼합 LUT, 크기 범위 밖, 행 수 불일치, 비유한 값, [0,1] 밖 출력,
잘못된 DOMAIN/지원하지 않는 지시문은 원문을 포함하지 않는 한 줄 사유로 거부합니다.
파일은 32MiB, 한 줄 4096자 제한입니다. 출력이 음수/1 초과인 HDR `.cube`는 M2 범위 밖입니다.
내장·사용자 B7LT는 로딩 때 SHA256과 헤더/길이를 검사합니다.
