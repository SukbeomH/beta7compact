# M5a 레시피와 공유 계약

레시피 기본 version=2. v1은 새 필드 없이 읽고 기존 LUT→WB/노출 순서를 유지합니다.
v2 `edits`는 highlights/shadows/contrast/saturation/vibrance(각 ±100), curves(c0 공통,
c1 R, c2 G, c3 B), hsl(b0~b7의 h/s/l 각 ±100), split(sh/ss/hh/hs/balance)를 담습니다.
곡선은 p0~p15의 x/y 객체이며 점 2~16개, x 0/1 끝점, 증가 간격 최소 .00001, y 0~1입니다.
스플릿 색조 0~360, 채도 0~100, 균형 ±100입니다. 기존 필드의 범위는 M2와 같습니다.
원본 결과/작업 JSON은 불변이며 DB는 Room v4를 유지합니다. DAO에는 이름 조회와 보관 레시피
삭제만 추가합니다. 이름 충돌은 트랜잭션 안에서 (2), (3)…을 붙이며 기존 이름을 덮어쓰지 않습니다.

## 코드

`b7c1:<base64url(zlib UTF-8 JSON), padding 없음>.<소문자 SHA256 앞 8바이트 hex>`.
체크섬 대상은 압축 바이트입니다. 체크섬은 오염 검출이며 발신자 인증이 아닙니다.
JSON envelope는 name/lutName(각 UTF-8을 base64url한 문자열), recipe(중첩 JSON)입니다.
LUT 격자/파일/URI는 포함하지 않습니다. 내장 LUT는 id, 사용자 LUT는 원래 id와 표시 이름을 참조합니다.
선택적 recipe.lutName64는 가져온 사용자 LUT의 필요 이름을 보관하기 위한 UTF-8 base64url 메타데이터입니다.

- 입력 코드 24,000자(공백 trim 전), 압축 해제 JSON 16,384바이트, 레시피 JSON 16,384자.
- JSON 객체 깊이 최대 7단계, 중복 key/비유한수/범위 밖 값/잘못된 점/시드/버전은 거부합니다.
- 이름 최대 80자, LUT 이름 최대 160자, 제어문자는 거부합니다. 해제 중 상한+1바이트에서 중단합니다.
  압축 끝/추가 데이터/base64url 정규 형태/체크섬/UTF-8도 검사합니다. 거부 이유는 고정 한 줄입니다.
- 붙여넣기 또는 ACTION_SEND text/plain으로 들어온 코드는 검사 후 이름과 LUT 필요 여부를 확인하고
  보관합니다. LUT가 없으면 적용하지 않습니다. 나중에 필요한 LUT를 가져오고 선택한 후 보관 항목을
  눌러 명시적으로 현재 LUT에 연결합니다. 사용자가 연결을 승인할 때에만 JSON의 LUT 참조를 바꿉니다.
  공유 인텐트 수신만으로 파일/사진/카메라에 작업하지 않습니다.

## QR

앱의 RecipeQr는 새로 작성한 byte-mode QR Model 2 인코더입니다. 외부 코드/라이브러리를
복사하거나 앱에 추가하지 않았습니다. L 정정, mask 0, 버전 1/20/40(ASCII 각각 17/858/2953자)을
지원합니다. 긴 코드는 텍스트 공유를 유지하고 QR 용량 초과 한 줄 사유를 표시합니다.
8종 마스크 자동 최적화는 하지 않으며 고정 mask 0도 표준의 유효한 마스크입니다.
표시는 흑백 정수 픽셀 모듈과 4모듈 quiet zone을 사용합니다. 물리 화면/카메라 인식률은 실기 확인 대상입니다.
**M5a는 QR 표시만 구현합니다. 스캔은 카메라 앱으로 읽은 텍스트를 붙여넣는 방식입니다.**
카메라 권한, ML Kit, 네트워크 QR 서비스는 추가하지 않았습니다.

형식/용량: [DENSO WAVE QR versions](https://www.qrcode.com/en/about/version.html),
규격은 ISO/IEC 18004 QR Model 2에 따릅니다.
GF(256) 다항식 0x11d, RS 블록 20-L=(3×107+5×108) data + 블록당 EC28,
40-L=(19×118+6×119) data + 블록당 EC30, BCH format 0x537/version 0x1f25를 사용합니다.

테스트 벡터는 기존 서버 시스템의 qrcode-terminal 0.12.0에 포함된 독립 QRCode 구현을 실행해
만든 모듈 데이터입니다. 해당 구현을 제품 소스/의존성에 복사하지 않았습니다.
[oracle 프로젝트](https://github.com/gtanner/qrcode-terminal),
[upstream QRCode](https://github.com/kazuhikoarase/qrcode-generator).
app/src/test/resources/m5a의 v1/v20/v40 텍스트는 각각 다음 ASCII 데이터의 모든 모듈입니다.

- v1: b7c1:test
- v20: b7c1: + aB9_- 150회
- v40: b7c1: + z5_-Q 500회

재생성은 시스템에 oracle이 이미 있는 환경에서 Node로 QRCode(version,1)를 만들고
addData(text), makeImpl(false,0), modules를 1/0 행으로 기록합니다.
고정 버전/L/byte/mask0의 표준 출력과 JVM 인코더를 비트 단위로 대조합니다.
시험 데이터만 저장하며, 구현 코드의 라이선스를 앱에 전이시키는 복사 작업은 하지 않습니다.
