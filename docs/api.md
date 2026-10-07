# 외부 예약 Mock API 명세

| 문서 정보 | 내용 |
| --- | --- |
| 대상 | 외부 예약 시스템 Mock · 기능 F-S-05 · F-A-03 |
| 버전 / 작성일 | 5.14 / 2026-10-07 (원장 목록 조회 `GET /external/reservations` 추가 — 정합성 검사용. 5.12: 재시도를 다 써도 Mock 취소를 보내지 않음) |
| 서버 | `http://localhost:8081` |
| 개수 | 9개 |
| 기준 | 원장 · 응답은 ERD v14.1 (2026-10-06) 의 `external_mock` 영역 — 칸 · 제약은 v5(2026-09-17)와 같다. 요청 본문은 본 서비스(preorder)의 `RegisterRequestPayload` · `CancelRequestPayload` (be 결정, 2026-10-06) |

**이 문서가 Mock API 계약의 정본이다.** 계약을 바꾸려면 이 파일을 고치는 PR 로 하고, 구현과 다르면 둘 중 하나가 버그다.

**요청은 preorder 형식, 원장 · 응답은 ERD 형식이다.** 요청 본문은 본 서비스가 보내는 이름 · 타입 그대로 받고, 원장에는 ERD 칸(`customer_id` · `product_id` · `sku`)으로 바꿔 넣는다(「내용 비교」). 요청에 예전 Mock 이름(`customerId` · `productId` · `sku` · 취소의 `externalNumber`)을 보내면 모르는 필드라 400 이다. 응답은 원장 이름(`customerId` · `productId` · `sku`)이고 예약번호는 `externalNumber` 다 — 본 서비스 워커가 아직 없어 읽는 쪽 이름이 정해지지 않았고, be 의 성공 이벤트도 같은 이름을 쓴다.

---

# 공통 사항

## 실행 전제

| 항목 | 내용 |
| --- | --- |
| 실행 대수 | **프로세스 1개.** 설정·결함이 메모리라 2개 이상이면 갈린다 |
| 저장 | 등록 기록만 `external_mock` 스키마(MySQL). 설정·결함은 메모리 |
| 스레드 | **가상 스레드.** 지연 2000ms 로 부하를 걸면 요청 수천 건이 Mock 안에서 동시에 대기한다. 블로킹 스레드 풀이면 큐가 밀려 설정값보다 훨씬 큰 지연이 관측되고, "우리 서버가 느린 것"과 "Mock 이 느린 것"을 구분할 수 없게 된다 |
| 지연 위치 | 지연은 **트랜잭션 밖**에서 기다린다. 트랜잭션 안에서 기다리면 DB 커넥션이 그만큼 묶여 풀이 마른다 |
| 정합성 검사 | 원장 목록 조회 `GET /external/reservations` 로 읽는다. 본 서비스는 `external_mock` 스키마를 직접 조회하지 않는다(2026-10-06 팀 결정 — 실제 외부 시스템처럼 API 로만) |

## 서버 · 인증 · 응답 형식

Mock 은 본 서비스와 별개 프로세스다. 팀 「API 공통 규칙」이 여기에 적용되지 않는다.

| 공통 규칙 항목 | Mock 적용 |
| --- | --- |
| 공통 응답 봉투 (success / data / error / timestamp / traceId) | ❌ 미적용 — Mock 자체 JSON |
| `X-Session-Token` 인증 · ADMIN 권한 | ❌ 미적용 — 인증 요구 없음 |
| 공통 오류 401 UNAUTHENTICATED · 403 FORBIDDEN | ❌ 미적용 |
| 본 서비스 기본 주소 `http://localhost:8080/api/v1` | ❌ Mock 은 `http://localhost:8081` |
| 공통 오류 500 INTERNAL_ERROR | ❌ 미적용 — Mock 의 500 은 `UPSTREAM_UNAVAILABLE`(주입 실패) |
| `Idempotency-Key` 는 같은 신청 재시도에서 같은 값·본문 사용 | ✅ 적용 |
| 204 = 본문 없음 | ✅ 적용 |
| `null` 과 필드 생략은 다름 | ✅ 적용 |

Mock 은 남의 회사 시스템을 연기한다. 우리 오류 포맷을 따르지 않는 것이 오히려 현실적이다.

성공은 자원 객체를 그대로 반환한다. 실패는 아래 형식이다.

```json
{
  "errorCode": "KEY_CANCELED",
  "errorMessage": "이미 취소된 키입니다.",
  "replayable": true,
  "externalNumber": null
}
```

- `replayable` — 이 결과가 저장되어 같은 키의 재요청에도 같은 응답이 나오는지. 일시 실패는 저장하지 않으므로 `false` 다. **재시도해도 되는지를 뜻하지 않는다** — 500 도 `false` 지만 재시도 대상이다. 재시도 여부는 `errorCode` 로 정한다(아래 오류 분류 계약).
- `externalNumber` — `KEY_CANCELED` · `KEY_PAYLOAD_MISMATCH` 인데 그 키에 이미 번호가 있으면 채운다. 그 외에는 `null` 이며 필드를 생략하지 않는다.

## 오류 분류 계약

본 서비스는 HTTP 상태가 아니라 `errorCode` 로 분기한다. 같은 409 라도 뜻이 다르다.

| 응답 | errorCode | 본 서비스 처리 |
| --- | --- | --- |
| 201 (재생 포함) | — | 확정 반영 |
| 409 | `KEY_CANCELED` | 취소된 키. 확정하지 않고 현재 상태로 정리 |
| 409 | `RESET_BUSY` | 워커는 받지 않는다. 초기화(제어 API) 전용 — 진행 중인 등록 · 취소가 있어 초기화를 거절했다 |
| **422** | **`KEY_PAYLOAD_MISMATCH`** | **같은 키로 다른 내용이 왔다. 본 서비스 버그이므로 재시도하지 않고 오류로 남긴다** |
| 400 | `INVALID_REQUEST` | 계약/설정 오류 |
| 500 | `UPSTREAM_UNAVAILABLE` | 일시 실패. 주입 실패. 커밋 전이라 아무것도 저장되지 않음 |
| 404 | `NOT_FOUND` | 조회 대상의 기록 없음. 키 조회의 404 는 "지금 등록이 없다" 는 뜻이다(키 조회 절 「본 서비스의 판단」) |
| 404 | `NO_SUCH_ENDPOINT` | 없는 경로. 주소 · 경로 설정 오류이므로 재시도하지 않고 오류로 알린다. **"등록 없음" 으로 읽지 않는다** |
| 타임아웃 | — | **결과 불명** (아래) |
| `errorCode` 가 없는 응답 | — | **결과 불명** (아래). 본문 없는 500, 연결 끊김, Mock 앞단 장비(로드 밸런서 등)가 대신 보낸 응답 |

**5xx · 타임아웃은 미등록의 증거가 아니다.** 재시도 전에 `GET /external/reservations/by-key/{externalKey}` 로 등록 여부를 먼저 확인한다.

### 결과 불명

응답만으로 등록이 됐는지 알 수 없는 경우다. 타임아웃과 `errorCode` 가 없는 응답이 여기에 든다.

Mock 이 응답하지 않는 두 경우 — `failureMode=TIMEOUT` 과 결함 `RESPONSE_LOST_AFTER_COMMIT` — 는 유지 시간(기본 7초) 동안 아무것도 보내지 않다가 본문 없는 500 으로 끝난다. 유지 시간을 워커 읽기 타임아웃(5초)보다 2초 길게 두므로 워커에는 타임아웃으로 보인다. 본문 없는 500 을 받았더라도 같게 처리한다.

| 순서 | 워커가 하는 일 |
| --- | --- |
| 1 | 새 키를 만들지 않는다 |
| 2 | 키 조회로 확인한다. ACTIVE 면 그 번호로 확정, 취소 표식이면 정리, 404 면 **같은 키로** 다시 등록 |
| 3 | 재시도를 다 써도(DEAD_LETTER) Mock 취소를 보내지 않는다. 같은 키로 취소하는 것은 예약을 취소로 끝낼 때뿐이다(키 조회 절 「본 서비스의 판단」) |

500 은 주입 실패 말고도 중복 키 재시도 상한 초과 · 처리하지 못한 오류에서 같은 코드로 난다. 워커에게는 모두 일시 실패이고 저장 여부는 위처럼 확인한다. 부하 시험 집계에서는 `errorMessage` 로 구분한다.

| 500 의 원인 | `errorMessage` |
| --- | --- |
| 주입 실패 (`failureMode=HTTP_5XX`) | `외부 시스템을 사용할 수 없습니다.` |
| 중복 키 재시도 상한 초과 | `중복 키 재시도 상한(5회)을 넘었습니다.` |
| 처리하지 못한 오류 (교착 등 Mock 의 문제) | `Mock 이 처리하지 못한 오류입니다.` |

재시도해도 결과가 같은 요청은 반드시 4xx 다. 메서드 · `Content-Type` · `Accept` 가 틀려도 405 · 415 · 406 이 아니라 400 `INVALID_REQUEST` 이고, 무엇이 틀렸는지를 메시지에 담는다.

등록 · 조회 · 취소와 제어 API(설정 · 결함 · 초기화)는 헤더 검사를 **처리 전에** 한다. `Content-Type` 이 `application/json` 이 아니거나 `Accept` 가 JSON 을 받지 않는 요청은 아무것도 남기지 않는다 — 설정을 바꾸거나 결함을 걸거나 기록을 지우지 않는다. 오류 본문은 `Accept` 와 상관없이 JSON 이다.

## 요청 검증

**요청 본문에 계약에 없는 필드가 있으면 400 `INVALID_REQUEST` 다.** 모르는 필드를 조용히 버리면 워커가 계약과 다른 본문을 보내도 아무도 모르고, 설정 API 에 없는 값을 넣어도 200 이 나와 적용된 줄 알게 된다. 저장하지 않는 필드(취소의 `reason`)도 계약에 있으면 받는다.

**타입이 다른 값도 바꿔 받지 않는다.** `"qty": "1"`(문자열 숫자) · `1.9`(실수 → 정수) · `""`(빈 문자열) · `"customerRef": 1001`(숫자 → 문자열) · 열거형 자리의 숫자(`"failureMode": 1`)는 모두 400 이다. 바꿔 주면 워커의 타입 오류가 Mock 에서는 묻히고 실제 외부 시스템에서야 드러난다. 값이 바뀌지 않는 정수 → 실수(`"failureRate": 1`)만 받는다. **같은 필드가 두 번 오면** 마지막 값을 쓰지 않고 400 이다.

400 의 `errorMessage` 에는 무엇이 틀렸는지 담는다. 문구는 사람이 읽으라고 둔 것이고, 워커는 `errorCode` 로 분기한다.

| 틀린 것 | `errorMessage` 예 |
| --- | --- |
| 계약에 없는 필드 | `customerId 은(는) 알 수 없는 필드입니다. 받을 수 있는 필드: ourReservationId, customerRef, itemCode, optionCode, qty, scope` |
| enum 값 | `failureMode 은(는) HTTP_5XX, TIMEOUT 중 하나여야 합니다. 받은 값: HTTP_429` |
| 필수 값 누락 | `optionCode 은(는) 필수입니다.` |
| 타입이 다른 값 | `customerRef 값의 형식이 올바르지 않습니다.` |
| 같은 필드 두 번 | `customerRef 필드가 두 번 왔습니다.` |
| 길이 · 범위 · 형식 | `optionCode 은(는) 80자 이하여야 합니다.` · `customerRef 은(는) 0 으로 시작하지 않는 1~18자리 숫자여야 합니다.` · `qty 은(는) 1 이어야 합니다.` · `Idempotency-Key 은(는) 영문 · 숫자 · . _ - 로 된 1~100자여야 합니다.` |
| 본문 키 ≠ 헤더 키 | `ourReservationId 가 Idempotency-Key 와 다릅니다.` |
| 필수 헤더 누락 | `Idempotency-Key 헤더가 필요합니다.` |
| `Content-Type` | `Content-Type 이 application/json 이어야 합니다. 받은 값: application/*` |
| `Accept` | `Accept 가 application/json 을 받아야 합니다. 받은 값: text/plain` |
| JSON 이 깨짐 · 본문 없음 | `요청 본문을 읽을 수 없습니다.` |

같은 키의 동시 요청은 락 대기로 직렬화하므로 "처리 중" 을 뜻하는 응답은 두지 않는다.

## 지연 · 실패 적용 범위

| 호출 | 지연 | 실패 주입 |
| --- | --- | --- |
| 등록 | ✅ 적용 | ✅ 적용 (기본 5%) |
| 취소 | ❌ | ❌ — 과제 "항상 성공 가정" |
| 조회 | ❌ | ❌ |
| 설정 · 결함 · 초기화 | ❌ | ❌ |

- 실패는 **커밋 전에** 발생한다. 아무것도 저장하지 않는다.
- 5% 는 호출 실패 조건이며 예약의 최종 실패율을 강제하지 않는다. (요구사항 5.1)
- 관측 비율이 매번 정확히 5% 일 것을 요구하지 않는다. (요구사항 5.4)
- **지연은 평균값이다.** 주제와 요구사항 2장이 *"평균 500ms 지연"* 으로 정했다. 요청마다
  `registerLatencyMs × (1 ∓ latencyJitter)` 사이에서 균등하게 뽑는다. 기본값이면 **300 ~ 700ms**,
  평균 500ms 다. `latencyJitter` 는 설정 파일로 정하고(기본 0.4 · **0 ~ 1**, 벗어나면 Mock 이 기동하지
  않는다) 설정 API 로는 바꾸지 않는다.
- 균등분포를 쓰는 이유는 부하 판정이다. 주입한 지연의 백분위를 미리 계산할 수 있어(기본값에서
  p95 680ms · p99 696ms) 관측값에서 그 몫을 빼면 Mock 이 실제로 쓴 시간이 남는다.

## 멱등 키

```
Idempotency-Key: {본 서비스의 preorders.preorder_token}
```

ERD 의 `preorder_token` 은 `char(36)` UUID 이며 "공개 UUID 이자 외부 Mock 등록·취소 키" 다.

- 접수 때 정하고 바꾸지 않는다. 재시도·리스 회수·보상에서 같은 값이다.
- 등록·조회·취소가 같은 키를 쓴다.
- 결과 불명이라고 새 키를 만들지 않는다. 같은 키로 재시도하거나 `by-key` 조회로 먼저 확인한다.
- 식별자 비교는 **대소문자를 구별한다.** ERD 가 키 칸을 `COLLATE utf8mb4_bin` 으로 둔 것과 같은 규칙이다.
- Mock 은 키를 UUID 로 강제하지 않는다. **영문 · 숫자 · `.` `_` `-` 로 된 1~100자**(ERD 키 칸 `varchar(100)`)면 받는다. Mock 단독 시험에서 `test-lost-1` 같은 키를 쓸 수 있다. 외부 번호(`externalNumber`)도 같은 규칙이다.
- 그 밖의 문자는 400 이다. 받아 놓고 다시 찾을 수 없는 키를 만들지 않기 위해서다.

  | 막는 것 | 받으면 생기는 일 |
  | --- | --- |
  | `/` | 키 조회는 키를 경로에 넣는다. 등록은 되는데 키 조회로 찾을 수 없다 |
  | 공백 | 키 칸 콜레이션이 끝 공백을 무시하고 비교한다(PAD SPACE). `pad-1 ` 의 등록을 `pad-1` 의 조회 · 취소가 잡는다 |
  | 비 ASCII | 헤더가 ISO-8859-1 로 읽혀 깨진 채 저장된다 |
  | `,` | `Idempotency-Key` 헤더가 두 개면 `a,b` 한 키로 합쳐진다 |
  | `.` · `..` 만으로 된 키 | 경로에서 현재 · 상위 경로로 해석된다 |

## 내용 비교

같은 키의 재요청이 **같은 신청인지** 판정할 때 저장된 행의 아래 세 칸(ERD)을 요청과 직접 비교한다. 요청은 preorder 형식이라 바꿔서 비교한다.

| 원장 칸 (ERD) | 요청 필드 | 바꾸는 법 |
| --- | --- | --- |
| `customer_id` | `customerRef` | 숫자로. **0 으로 시작하지 않는 1~18자리 숫자만** 받는다 |
| `product_id` | `itemCode` | 숫자로. 같은 규칙 |
| `sku` | `optionCode` | 그대로 |

ERD 에서 `request_hash` 를 제거했으므로 지문 대신 칼럼을 본다. 신청 내용이 이미 행에 남아 있고, 어느 필드가 달라 거절됐는지 응답에 담을 수 있다. **거절 문장에는 요청 필드 이름**(`customerRef` · `itemCode` · `optionCode`)을 쓴다 — 보낸 쪽이 자기 본문에서 찾을 수 있어야 한다.

- **앞에 0 이 붙은 참조는 400 이다.** `"01001"` 을 받아 1001 로 바꾸면 `"1001"` 과 조용히 같은 신청이 된다. 숫자가 아닌 값(`"C-1001"`)과 19자리 이상(`long` 범위를 넘을 수 있음)도 400 이다. 본 서비스는 `String.valueOf(id)` 로 보내므로 걸리지 않는다.
- **`qty` · `scope` 는 저장 · 비교하지 않는다**(ERD 에 칸 없음). 사전예약은 수량 1 고정이라 `qty` 는 1 만 받고, 그 밖은 같은 키 · 다른 내용(422)이 아니라 잘못된 요청(400)이다. **`scope` 는 비교하지 않으므로 같은 키로 `scope` 만 달라도 재생된다.**
- `ourReservationId` 는 키와 같은 값이라 비교 대상이 아니다. 다르면 처리 전에 400 이다.

**본 서비스가 같은 키로 다른 내용을 보내는 것은 정상 흐름이 아니다.** `preorder_sync_jobs.request_payload` 가 접수 때 고정되기 때문이다. 그래도 거절하는 이유는 그 버그를 조용히 넘기지 않기 위해서다.

## 등록 자원의 필드

ERD v14.1 `external_mock.preorder_registrations` 와 1:1 이다. 응답 · 조회는 이 이름으로 나간다.

| 필드 | 타입 | ERD 칸 | 설명 |
| --- | --- | --- | --- |
| `externalKey` | string | `external_key` (PK) | 우리 `preorders.preorder_token`. 등록 본문의 `ourReservationId` 와 같은 값 |
| `externalNumber` | string | `external_number` (UNIQUE) | Mock 이 최초 등록에서 발급. 등록 전 취소 표식이면 `null` |
| `customerId` | integer | `customer_id` | 우리 `customers.id`. 요청의 `customerRef` 를 숫자로 바꾼 값. 물리 FK 없음 |
| `productId` | integer | `product_id` | 우리 `products.id`. 요청의 `itemCode` |
| `sku` | string | `sku` | 우리 `product_options.sku`. 요청의 `optionCode` |
| `status` | string | `status` | `ACTIVE` / `CANCELED` |
| `confirmedAt` | datetime | `confirmed_at` | 등록을 확정한 시각 |
| `canceledAt` | datetime | `canceled_at` | 취소 표식을 남긴 시각 |

`status = ACTIVE` 이면 나머지 필드가 모두 채워져 있어야 한다. ERD 의 `ck_registration_active_fields` CHECK 가 그것이다. 등록 전 취소는 키·상태·취소 시각만 있는 행으로 남는다.

시각은 모두 UTC 이고 **밀리초 세 자리**로 쓴다(`2026-09-16T10:00:03.000Z`). 밀리초가 0 이어도 `.000` 을 붙인다. 제어 API 의 `appliedAt` · `createdAt` 도 같다.

수량 칸은 ERD 에서 제거했다. 사전예약 신청 단위가 수량 1 고정이고 `preorders` 에도 수량 칸이 없다. 요청의 `qty` 는 1 인지만 검사한다.

## 시험 프리셋

| 용도 | registerLatencyMs | failureRate |
| --- | --- | --- |
| 기본 | 500 | 0.05 |
| 지연 감도 | 2000 | 0.0 |
| 재시도 시험 | 500 | 0.5 |
| 완전 실패 | 500 | 1.0 |
| **결함 시험** | **500** | **0.0** |
| **취소 경합 시험** | **10000** | **0.0** |

결함 주입 시나리오(`RESPONSE_LOST_AFTER_COMMIT`)를 확인할 때는 **실패율을 0 으로 내린다.** 지연·실패 판정이 결함 발동보다 앞서므로, 기본 5% 로 두면 재시도가 결함에 닿기 전에 일시 실패로 끝날 수 있다.

"등록이 오가는 중에 취소가 끼어드는" 상황은 **지연을 크게 잡아** 만든다. 등록 요청이 지연 구간에서 기다리는 동안 취소를 보내면 된다. 지연은 락 밖이라 그 사이 취소가 표식을 남길 수 있다.

---

# 예약

## POST /external/reservations

| 속성 | 값 |
| --- | --- |
| API 이름 | 예약 등록 |
| 기능명 | 외부 예약 Mock |
| 메서드 | POST |

### 요구사항

- 본 서비스가 접수한 예약을 등록하고 외부 예약번호를 발급한다. 형식은 **`R-yyyyMMdd-NNNNNNNNNN`** — UTC 발급일 + 10자리 난수. 겹치면 새 번호로 다시 뽑는다. 6자리면 하루 100만 개라 부하 시험에서 동나고, `reset` 뒤 전에 준 번호가 다시 나와 본 서비스의 UNIQUE 저장이 깨질 수 있어서 10자리다. 10자리여도 반복 실행에서는 겹칠 확률이 쌓이므로 본 서비스 쪽도 함께 비운다(아래 reset).
- 같은 키·같은 내용의 성공 요청은 같은 외부 예약번호를 반환한다. (과제 명세)
- **같은 키·다른 내용의 요청은 422 로 거절한다.** (요구사항 5.3 · ERD)
- 키 선점·등록·결과 기록을 원자적으로 처리한다.
- 성공만 저장·재생하며 일시 실패는 저장하지 않는다.
- 커밋 후 응답만 유실된 경우 저장된 성공을 재생한다.
- 취소 표식이 있는 키는 재생보다 먼저 거절한다.
- 설정된 지연과 실패 확률을 이 API 에만 적용한다.

### Request

**Headers**

```
Content-Type: application/json
Accept: application/json
Idempotency-Key: 9f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f
```

| 이름 | 위치 | 타입 | 필수 | 제약 |
| --- | --- | --- | --- | --- |
| `Idempotency-Key` | header | string | 필수 | 영문 · 숫자 · `. _ -` 1~100자(멱등 키 절). 본 서비스는 `preorder_token`(`char(36)` UUID)을 보낸다 |

**Body**

본 서비스의 `RegisterRequestPayload` 그대로다. 원장에 들어가는 칸은 「내용 비교」 표를 따른다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `ourReservationId` | string | 필수 | 우리 `preorder_token`. **`Idempotency-Key` 헤더와 같아야 한다** — 다르면 400(어느 키로도 저장하지 않음) |
| `customerRef` | string | 필수 | `customers.id` 문자열 → `customer_id`. **0 으로 시작하지 않는 1~18자리 숫자** |
| `itemCode` | string | 필수 | `products.id` 문자열 → `product_id`. 같은 규칙 |
| `optionCode` | string | 필수 | `product_options.sku` → `sku`. `maxLength=80` |
| `qty` | integer | 필수 | 수량. **1 만** 받는다. 저장하지 않는다 |
| `scope` | string | 필수 | 요청 범위. 지금은 `preorder`. `maxLength=50`. 저장 · 비교하지 않는다 |

```json
{
  "ourReservationId": "9f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
  "customerRef": "1001",
  "itemCode": "12",
  "optionCode": "SM-G999-256-BLK",
  "qty": 1,
  "scope": "preorder"
}
```

### 시스템 처리

지연·실패 판정은 **락 밖**에서 먼저 처리한다. 락 보유 시간을 짧게 유지하고, 지연 중에도 취소 요청이 들어올 수 있게 하기 위해서다.

**트랜잭션 밖**

1. 설정된 `registerLatencyMs` 를 **평균으로** 대기한다(`× (1 ∓ latencyJitter)` 균등분포). **락을 아직 잡지 않았으므로 그 사이 취소 요청이 표식을 남길 수 있다.**
2. 설정된 `failureRate` 로 일시 실패를 발생시킨다. 커밋 전이므로 아무것도 저장하지 않는다.

**트랜잭션 안**

3. 키 행을 **잠금 읽기**로 잠근다. 같은 키의 다른 요청이 처리 중이면 그 트랜잭션이 끝날 때까지 기다린다. 행이 없으면 잠금이 걸리지 않으며(READ COMMITTED), 같은 새 키의 동시 요청은 6단계 INSERT 에서 직렬화된다.
4. **취소 표식이 있으면 409 `KEY_CANCELED`.** 등록을 만들지 않는다. 재생보다 먼저 검사한다.
5. 등록이 이미 있으면
   - **내용이 같으면** 201 + `X-Idempotent-Replay: true` 로 재생한다.
   - **내용이 다르면** 422 `KEY_PAYLOAD_MISMATCH`. 기존 등록을 바꾸지 않는다.
6. 등록을 커밋하고 201 로 응답한다. INSERT 가 1062(중복 키)를 받으면 다른 요청(등록·취소)이 먼저 만든 것이므로 3단계부터 다시 한다. ERD 가 1062 를 정상 분기로 정했다.
7. `RESPONSE_LOST_AFTER_COMMIT` 결함이 걸려 있으면 커밋한 뒤 유지 시간 동안 응답하지 않는다(결과 불명).

> **취소 표식 검사를 재생 검사보다 앞에 둔 이유.** 순서가 반대면 `등록 → 취소 → 재등록` 이 201 로 응답한다.
>
> **지연을 트랜잭션 밖에 둔 대가:** 재생되는 요청도 지연을 한 번 겪는다. 응답 유실 복구가 `registerLatencyMs` 만큼 늦어지는 정도이며,
> 커넥션 풀이 마르는 것보다 낫다.

### Response

**201 Created**

응답 헤더 `X-Idempotent-Replay`(boolean) · `X-Mock-Config-Version`(integer) · `X-Mock-Injected-Latency-Ms`(integer)

`X-Mock-Injected-Latency-Ms` 는 이 요청에 **실제로 뽑힌 지연**(ms)이다. 지연이 0 이어도 `0` 을 싣는다. 지연 주입이 원장을 보기 **전**(처리 순서 1단계)이라 201 · 재생만이 아니라 **409 · 422 · 주입 실패의 500 · 주입이 아닌 500(교착 · 재시도 상한 등)에도 실린다.** 요청 형식이 틀린 400 은 주입 전에 거절되므로 없다. 부하 판정이 요청마다 `관측 − 이 값` 으로 오버헤드를 구하는 데 쓴다 — 지연을 흔들면 관측 백분위에서 주입 백분위를 빼는 것은 오버헤드의 백분위가 아니다. 워커는 쓰지 않는다.

```json
{
  "externalKey": "9f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
  "externalNumber": "R-20260916-0048213579",
  "customerId": 1001,
  "productId": 12,
  "sku": "SM-G999-256-BLK",
  "status": "ACTIVE",
  "confirmedAt": "2026-09-16T10:00:03.412Z",
  "canceledAt": null
}
```

**주요 오류**

| 응답 | errorCode | 설명 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | 필수 값 누락 · 계약에 없는 필드 · 키 길이 · `ourReservationId` ≠ 헤더 키 · 참조가 숫자 형식이 아님 · `qty` ≠ 1 |
| 409 | `KEY_CANCELED` | 취소 표식이 있는 키 |
| **422** | **`KEY_PAYLOAD_MISMATCH`** | **같은 키로 다른 `customerRef`·`itemCode`·`optionCode`** (`scope` 는 비교하지 않음) |
| 500 | `UPSTREAM_UNAVAILABLE` | `failureMode=HTTP_5XX` 의 주입 실패. 커밋 전 |

```json
{
  "errorCode": "KEY_PAYLOAD_MISMATCH",
  "errorMessage": "같은 키로 다른 내용이 요청되었습니다. optionCode 이(가) 다릅니다.",
  "replayable": false,
  "externalNumber": "R-20260916-0048213579"
}
```

### 확인 시나리오

| 시나리오 | 기대 결과 |
| --- | --- |
| 같은 키·같은 내용 연속 2회 | 같은 번호. 2회차 `X-Idempotent-Replay: true` |
| **같은 키·다른 optionCode** | **422 `KEY_PAYLOAD_MISMATCH`. 기존 등록 그대로** |
| `ourReservationId` 가 헤더 키와 다름 | 400 `INVALID_REQUEST`. 어느 키로도 저장 안 됨 |
| `customerRef` 가 `"01001"` · `"C-1001"` | 400 `INVALID_REQUEST`. `"1001"` 과 같은 신청으로 보지 않음 |
| `qty` 가 1 이 아님 | 400 `INVALID_REQUEST` (422 아님) |
| 같은 키 · `scope` 만 다름 | 201 재생 (`scope` 는 비교하지 않음) |
| 같은 키 동시 10건 | 정확히 1건만 등록. 번호 1개. 9건은 `X-Idempotent-Replay: true` |
| 취소된 키로 등록 시도 | 409 `KEY_CANCELED`. 등록 생성 안 됨 |
| **등록 성공 → 취소 → 같은 키 재등록** | **409 `KEY_CANCELED`.** 201 재생이 아님 |
| 실패율 1.0 으로 10회 실패 → 0.0 변경 후 재시도 | 즉시 성공. 저장된 실패를 재생하지 않음 |
| `RESPONSE_LOST_AFTER_COMMIT` 주입 후 재시도 | 저장된 성공 재생. 등록 1건 |
| 커밋 직후 강제 종료 → 재기동 → 같은 키 재요청 | 저장된 성공 재생. 새 번호 발급 없음 |

- **동시 요청 시나리오는 최소 100회 반복해야 의미가 있다.** 동시성 버그는 경합에서만 나오므로 한 번 통과는 증명이 아니다.
- **실패율 시나리오는 100% 로 돌려야 드러난다.** 일시 실패를 저장하면 실패율을 0% 로 내려도 저장된 실패가 영원히 재생되어 예약이 최종 확정에 도달하지 못한다. 5% 로만 시험하면 안 보인다.

---

## GET /external/reservations

| 속성 | 값 |
| --- | --- |
| API 이름 | 원장 목록 조회 |
| 기능명 | 외부 예약 Mock |
| 메서드 | GET |

### 요구사항

- 원장의 모든 행을 외부 키 순서로 페이지씩 돌려준다. 등록 행과 취소 표식 행(등록 전 취소)을 모두 준다.
- **정합성 검사가 Mock 쪽 등록을 전부 받아 갈 때 쓴다.** 본 서비스는 Mock DB 를 직접 읽지 않는다(2026-10-06 팀 결정). 키 조회는 본 서비스가 아는 키만 물을 수 있어서 "Mock 에만 있는 등록" 이나 "같은 회원 · 상품의 활성 등록 2건" 을 찾지 못한다.
- 등록용 지연·실패 설정을 적용하지 않는다.

### Request

**Headers**

```
Accept: application/json
```

| 이름 | 위치 | 타입 | 필수 | 제약 |
| --- | --- | --- | --- | --- |
| `cursor` | query | string | 선택 | 이 외부 키보다 큰 행부터 준다. 앞 응답의 `nextCursor` 를 그대로 보낸다. 없으면 처음부터. 영문 · 숫자 · `. _ -` 1~100자 |
| `size` | query | integer | 선택 | 한 페이지 건수. 1~1000, 기본 500 |

**Body**: 없음

```
GET /external/reservations?size=500
GET /external/reservations?cursor=9f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f&size=500
```

### 시스템 처리

1. 쿼리 파라미터를 본문처럼 엄격하게 검사한다. **모르는 이름 · 같은 이름 두 번 · 빈 값 · 범위를 벗어난 `size` 는 400.** 모르는 이름을 버리면 `productId=12` 로 거른 줄 알고 전체를 받는다.
2. 외부 키가 `cursor` 보다 큰 행을 키 순서로 `size` 건 읽는다. 잠그지 않는다 — 진행 중인 등록을 기다리지 않고, 목록 읽기가 등록을 막지도 않는다.
3. 더 남아 있으면 `nextCursor` 에 이 페이지 마지막 행의 키를, 없으면 `null` 을 넣는다. 남은 행이 딱 `size` 건이어도 `null` 이다(빈 페이지를 한 번 더 부르지 않게).

**페이지를 외부 키 순서로 넘기는 이유** — 키는 바뀌지 않는다. 그래서 페이지를 넘기는 사이에 등록 · 취소가 들어와도 원래 있던 행은 정확히 한 번씩 나온다(상태는 그 행을 읽은 순간의 값). 건수로 건너뛰는 방식(오프셋)이면 앞쪽에 새 행이 끼는 순간 한 행이 두 번 나오거나 빠진다. 넘기는 중에 새로 생긴 행은 나올 수도 있고 안 나올 수도 있다 — 검사하는 쪽이 가린다.

### Response

**200 OK**

`items` 한 건은 `POST /external/reservations` 의 201 과 같은 형식이다. 취소 표식만 있는 행은 `externalNumber` · `customerId` · `productId` · `sku` · `confirmedAt` 이 `null` 이다. 값이 없어도 필드를 생략하지 않는다.

```json
{
  "items": [
    {
      "externalKey": "1d6e82a4-1260-06c4-8e9f-0a1b2c3d4e5f",
      "externalNumber": null,
      "customerId": null,
      "productId": null,
      "sku": null,
      "status": "CANCELED",
      "confirmedAt": null,
      "canceledAt": "2026-09-16T10:10:00.000Z"
    },
    {
      "externalKey": "9f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
      "externalNumber": "R-20260916-0048213579",
      "customerId": 1001,
      "productId": 12,
      "sku": "SM-G999-256-BLK",
      "status": "ACTIVE",
      "confirmedAt": "2026-09-16T10:00:03.412Z",
      "canceledAt": null
    }
  ],
  "nextCursor": "9f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f"
}
```

**주요 오류**

| 응답 | errorCode | 설명 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | 모르는 쿼리 파라미터 · 같은 파라미터 두 번 · `cursor` 형식이 틀림 · `size` 가 1~1000 정수가 아님 |

### 확인 시나리오

| 시나리오 | 기대 결과 |
| --- | --- |
| `nextCursor` 를 따라 끝까지 부름 | 모든 행이 키 순서로 한 번씩. 마지막 응답의 `nextCursor` 는 `null` |
| 취소 표식만 있는 키 | 목록에 나온다(`status` `CANCELED`, 번호 · 상품 필드 `null`) |
| 실패율 100% | 200 — 지연 · 실패를 적용하지 않는다 |
| `?productId=12` | 400. 거르지 않고 전체를 주는 일이 없다 |

---

## GET /external/reservations/{externalNumber}

| 속성 | 값 |
| --- | --- |
| API 이름 | 번호로 단건 조회 |
| 기능명 | 외부 예약 Mock |
| 메서드 | GET |

### 요구사항

- 외부 예약번호로 단건 조회하며 취소된 등록도 반환한다.
- 관리자 화면에서 특정 예약의 외부 상태를 확인할 때 쓴다.
- 등록용 지연·실패 설정을 적용하지 않는다.

### Request

**Headers**

```
Accept: application/json
```

| 이름 | 위치 | 타입 | 필수 | 제약 |
| --- | --- | --- | --- | --- |
| `externalNumber` | path | string | 필수 | 영문 · 숫자 · `. _ -` 1~100자 |

**Body**: 없음

### 시스템 처리

1. 번호 형식이 틀리면 400. 조회하지 않는다 — 404 `NOT_FOUND` 는 "그 기록이 없다" 에만 쓴다.
2. 외부 예약번호로 등록 행을 조회한다.
3. 취소된 등록이면 `status` 를 `CANCELED` 로, `canceledAt` 을 채워 반환한다. 숨기지 않는다.
4. 없으면 404.

### Response

**200 OK** — `POST /external/reservations` 의 201 과 같은 형식

**주요 오류**

| 응답 | errorCode | 설명 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | 번호 형식이 틀림 |
| 404 | `NOT_FOUND` | 등록되지 않았습니다 |

### 확인 시나리오

| 시나리오 | 기대 결과 |
| --- | --- |
| 취소된 등록 단건 조회 | 200 반환 (숨기지 않음) |

---

## GET /external/reservations/by-key/{externalKey}

| 속성 | 값 |
| --- | --- |
| API 이름 | 키로 등록 상태 조회 |
| 기능명 | 외부 등록·재시도 |
| 메서드 | GET |

### 요구사항

- 외부 키로 등록·취소 표식을 함께 조회한다.
- **응답 유실 후 재시도 전 확인용이다.** 응답이 유실되면 본 서비스는 외부 번호를 모르므로 번호 기준 조회로는 확인할 수 없다.
- `external_key` 가 PK 라 한 키의 등록은 최대 1건이다. `registrations` 배열은 0~1개다.
- `storedOutcome` 에는 성공만 나타난다. 일시 실패는 저장하지 않는다.
- 기록이 전혀 없으면 404 다. **조회 시점에 커밋됐거나 트랜잭션 안에 있는 등록이 없다**는 뜻이다. 지연 구간(트랜잭션 밖)에서 기다리는 요청과 아직 도착하지 않은 요청은 포함하지 않으므로, 404 뒤에 같은 키가 등록될 수 있다.

### Request

**Headers**

```
Accept: application/json
```

| 이름 | 위치 | 타입 | 필수 | 제약 |
| --- | --- | --- | --- | --- |
| `externalKey` | path | string | 필수 | 영문 · 숫자 · `. _ -` 1~100자. 우리 `preorder_token` |

**Body**: 없음

### 시스템 처리

1. **키 행을 잠금 읽기(`SELECT ... FOR SHARE`)로 조회한다.** 같은 키의 등록 트랜잭션이 커밋될 때까지 기다린다.
2. 취소 표식이 있으면 `cancelMarkerAt` 에 시각을 반환한다. `registrations[].canceledAt` 과 같은 칸(`canceled_at`)이다. **등록 후 취소된 키도 채운다** — 워커는 이것 하나로 "취소된 키" 를 안다.
3. 이 키로 만들어진 등록을 배열로 반환한다.
4. 재생 대상으로 저장된 결과를 `storedOutcome` 으로 알린다. `SUCCESS` / `null`. **등록 후 취소된 키는 `null`** 이다 — 다시 등록하면 201 재생이 아니라 409 `KEY_CANCELED` 이기 때문이다.
5. 등록·취소 표식이 모두 없으면 404.

| 키의 상태 | `cancelMarkerAt` | `registrations` | `storedOutcome` |
| --- | --- | --- | --- |
| 등록됨 | `null` | 1개 (`ACTIVE`) | `SUCCESS` |
| 등록 후 취소 | 취소 시각 | 1개 (`CANCELED`) | `null` |
| 취소 표식만 | 취소 시각 | 0개 | `null` |
| 기록 없음 | 404 | | |

(2026-09-27 NV-22 구현 때 확정)

> **1번이 잠금 읽기여야 하는 이유.** READ COMMITTED 에서 일반 `SELECT` 는 커밋 안 된 행을 보여주지 않고
> 기다리지도 않는다. 진행 중인 등록을 못 본 채 404 를 돌려주면 워커가 재등록해 **중복 등록**이 된다.
> 잠금 읽기여야 트랜잭션 안에 있는 등록을 놓치지 않는다.

> **지연 중인 요청과의 관계.** 지연은 락 밖에서 처리하므로 지연을 크게 잡아도 이 조회가 그만큼 기다리지 않는다.
> 그래서 지연 구간에 있는 등록은 404 로 보인다. 지연 3,000ms 등록을 보내고 0.3초 뒤 조회하면 404, 4초 뒤에는
> 같은 키가 ACTIVE 다. 이 창은 최대 지연(60,000ms × 1.4 = 84초)까지 열릴 수 있다.

### Response

**200 OK**

```json
{
  "externalKey": "9f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
  "cancelMarkerAt": null,
  "registrations": [
    {
      "externalNumber": "R-20260916-0048213579",
      "status": "ACTIVE",
      "confirmedAt": "2026-09-16T10:00:03.412Z",
      "canceledAt": null
    }
  ],
  "storedOutcome": "SUCCESS"
}
```

**주요 오류**

| 응답 | errorCode | 설명 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | 키가 비었거나 100자를 넘음 |
| 404 | `NOT_FOUND` | 키에 대한 기록이 없습니다. 등록·취소 표식 어느 것도 없음 |

### 본 서비스의 판단

| 응답 | 워커가 하는 일 |
| --- | --- |
| 404 | 지금은 등록이 없다 → **같은 키로** 다시 등록. 지연 중인 등록이 있어도 같은 키라 한 건만 남는다(먼저 커밋된 것을 재생) |
| `registrations` 에 ACTIVE 있음 | 이미 됐음 → 그 번호로 확정 반영. **재등록 금지** |
| `cancelMarkerAt` 있음 | 취소된 키 → 확정하지 않고 정리 |

**404 를 보고 포기하지 않는다.** 재시도를 다 써도 등록 작업은 DEAD_LETTER 로 남기고 **Mock 취소를 보내지 않는다.** DEAD_LETTER 는 관리자가 같은 키로 재처리해 푸는데, 취소 표식이 있으면 재처리가 409 `KEY_CANCELED` 로 막혀 풀 수 없다. 그사이 늦게 커밋된 등록이 있어도 예약은 아직 등록 대기라, 재처리하면 같은 키라 그 등록을 재생해 같은 번호로 맞춰진다.

**같은 키로 `POST /external/cancellations` 를 보내는 것은 예약을 취소로 끝낼 때다**(사용자 · 관리자 취소, 결제 기한 만료 — 본 서비스의 취소 작업). 취소는 지연 · 실패 주입이 없어 항상 200 이고, 표식이 생긴 뒤 늦게 도착한 등록은 409 `KEY_CANCELED` 로 거절된다. 등록이 지연되는 사이에 예약이 취소돼도 표식이 먼저 남으므로, 뒤늦게 커밋된 등록이 외부에만 남지 않는다(유령 등록).

이 API 가 없으면 응답 유실 시 **중복 등록 아니면 유실** 둘 중 하나가 된다. 과제의 "유실 없이 끝까지 처리" 와 "같은 예약이 두 번 등록되면 안 됨" 이 함께 걸려 있다.

---

## POST /external/cancellations

| 속성 | 값 |
| --- | --- |
| API 이름 | 예약 취소 |
| 기능명 | 잘못된 외부 등록 정리 |
| 메서드 | POST |

### 요구사항

- 외부 키 또는 외부 예약번호로 활성 등록을 비활성화한다. (기능명세)
- 업무상 거절이 없다(과제: "잘못 등록된 예약을 되돌리는 용도, 항상 성공 가정"). **키로 취소하면 미등록·이미 취소된 대상도 200 이다.**
- 키 취소는 등록이 없어도 취소 표식을 영속 저장한다.
- 표식이 있는 키의 지연 도착 등록은 `KEY_CANCELED` 로 거절된다.
- 반복 호출의 업무 효과는 한 번이다.
- 지연·실패 설정을 적용하지 않는다.

### Request

**Headers**

```
Content-Type: application/json
Accept: application/json
```

**Body** — `externalKey` 와 `reservationNo` 중 하나 이상 필수. 본 서비스의 `CancelRequestPayload` 그대로다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `externalKey` | string | 조건부 | 우리 `preorder_token`. 영문 · 숫자 · `. _ -` 1~100자 |
| `reservationNo` | string | 조건부 | Mock 이 발급한 예약번호(등록 응답의 `externalNumber`). 영문 · 숫자 · `. _ -` 1~100자. 등록이 확인되지 않았으면 `null` |
| `reason` | string | 선택 | `USER_CANCEL` · `ADMIN_CANCEL` · `DEADLINE_EXCEEDED` · `GHOST_COMPENSATION`(정합성 검사의 키로 취소) 등. **저장하지 않는다** (ERD 에 칸 없음) |

```json
{
  "externalKey": "9f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
  "reservationNo": null,
  "reason": "GHOST_COMPENSATION"
}
```

### 시스템 처리

1. 같은 키 행을 잠그고 등록·취소를 직렬화한다.
2. 활성 등록이 있으면 `CANCELED` 로 바꾸고 취소 시각을 남긴다.
3. 이미 취소된 등록이면 아무것도 바꾸지 않는다.
4. **등록 기록이 없어도 취소 표식 행을 만든다.** 같은 새 키를 등록 요청이 먼저 INSERT 했으면 1062 를 받으므로, 다시 잠금 읽기로 조회해 2단계부터 한다.
5. 지연 구간에서 기다리던 등록이 뒤늦게 진행돼도 표식 때문에 활성화되지 못한다.
6. `reservationNo` 만 받았으면 그 번호의 등록을 찾아 그 키로 1~3단계를 한다. **그 번호의 등록이 없으면 404 다.** 키가 없어 표식을 남길 수 없고, 번호는 Mock 이 등록할 때만 발급하므로 그 번호로 늦게 오는 등록도 없다.
7. 둘 다 받았으면 **같은 등록을 가리켜야 한다.** 키의 등록 번호가 다르거나 그 번호가 다른 키의 등록이면 400 이고 아무것도 바꾸지 않는다. 키만 보고 취소하면 워커는 그 번호를 취소했다고 믿는데 다른 등록이 꺼지기 때문이다. 키에 번호가 없고 그 번호의 등록도 없으면 키로 처리한다.
8. 이번 호출로 실제 비활성화한 등록이 있었는지를 `hadActiveRegistration` 으로 알린다.
9. 취소된 행을 재활성화하지 않는다.

### Response

**200 OK** — 활성 등록을 비활성화함

```json
{
  "externalKey": "9f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
  "externalNumbers": ["R-20260916-0048213579"],
  "hadActiveRegistration": true,
  "cancelMarkerAt": "2026-09-16T10:10:00.000Z",
  "canceledAt": "2026-09-16T10:10:00.000Z"
}
```

**200 OK** — 미등록 키. 표식만 남김

```json
{
  "externalKey": "1d6e82a4-1260-06c4-8e9f-0a1b2c3d4e5f",
  "externalNumbers": [],
  "hadActiveRegistration": false,
  "cancelMarkerAt": "2026-09-16T10:10:00.000Z",
  "canceledAt": "2026-09-16T10:10:00.000Z"
}
```

`cancelMarkerAt` 과 `canceledAt` 은 ERD 의 같은 칸 `canceled_at` 이라 항상 같은 값이다. `externalNumbers` 는 0~1개다(키가 PK).

**주요 오류**

| 응답 | errorCode | 설명 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | `externalKey` 와 `reservationNo` 가 둘 다 없음 |
| 400 | `INVALID_REQUEST` | 키 · 번호가 비었거나 100자를 넘음 |
| 400 | `INVALID_REQUEST` | 키와 번호가 서로 다른 등록을 가리킴 |
| 404 | `NOT_FOUND` | 번호만 받았는데 그 번호의 등록이 없음 |

### 확인 시나리오

세 순서 모두 최종 활성 등록 0건이어야 한다.

| 시나리오 | 기대 결과 |
| --- | --- |
| 등록 성공 → 취소 | `hadActiveRegistration: true` · `status: CANCELED` |
| **지연 중 등록 → 취소 → 지연 종료** | **취소가 즉시 200. 뒤늦게 진행된 등록이 `KEY_CANCELED`** |
| 취소(미등록 키) → 지연 등록 도착 | 409 `KEY_CANCELED`. 등록 생성 안 됨 |
| 같은 대상 취소 2회 | 둘 다 200. 2회차 `hadActiveRegistration: false`. 취소 시각 그대로 |
| 같은 새 키로 등록 · 취소 동시 | 취소는 모두 200. 최종 활성 0. 끈 등록은 등록이 있었을 때만 1건 |

> 두 번째 시나리오는 `registerLatencyMs` 를 크게 잡아 만든다. 지연이 락 밖이라 그 사이 취소가 표식을 남긴다.
> DB 에서 보면 세 번째 시나리오와 순서가 같다.

경합 시나리오는 최소 100회 반복한다. 표식이 없으면 "취소 성공했다고 응답해놓고 나중에 등록이 살아나는" 상태가 되어 과제의 "항상 성공 가정" 이 거짓이 된다.

---

# 설정

## GET /external/config

| 속성 | 값 |
| --- | --- |
| API 이름 | Mock 설정 조회 |
| 기능명 | Mock 지연·실패 설정 |
| 메서드 | GET |

### 요구사항

- 현재 적용 중인 지연·실패 설정을 조회한다.
- 설정 변경 후 현재 적용값을 다시 확인할 수 있어야 한다. (요구사항 4.4)

### Request

**Headers**

```
Accept: application/json
```

**Body**: 없음

### 시스템 처리

1. 현재 설정을 그대로 반환한다.
2. 설정 버전과 마지막 적용 시각을 함께 반환한다.

### Response

**200 OK**

```json
{
  "registerLatencyMs": 500,
  "latencyJitter": 0.4,
  "latencyTailRate": 0.0,
  "latencyTailMinMs": 0,
  "latencyTailMaxMs": 0,
  "bodyLatencyMs": 500,
  "failureRate": 0.05,
  "failureMode": "HTTP_5XX",
  "configVersion": 4,
  "appliedAt": "2026-09-16T09:40:00.000Z",
  "timeoutHoldMs": 7000,
  "workerReadTimeoutMs": 5000
}
```

| 필드 | 설명 |
| --- | --- |
| `registerLatencyMs` | 지연의 **평균**. 꼬리를 걸어도 전체 평균은 이 값이다 |
| `latencyJitter` | 지연을 흔드는 폭. 실제 대기는 `평균 × (1 ∓ 이 값)` 균등분포. **조회만 되고 변경은 안 된다** — 설정 파일로 정한다 |
| `latencyTailRate` · `latencyTailMinMs` · `latencyTailMaxMs` | 지연의 느린 꼬리. 요청마다 이 비율로 [최소, 최대] 균등에서 뽑는다. 비율 0 이면 꼬리 없음(구간도 0) |
| `bodyLatencyMs` | 꼬리에 걸리지 않은 요청의 평균. 전체 평균이 `registerLatencyMs` 가 되도록 꼬리만큼 낮춘 값(반올림)이고, 지터는 여기에만 걸린다. **조회만 된다** |
| `configVersion` · `appliedAt` | 설정을 바꿀 때마다 오르는 버전과 그 시각 |
| `timeoutHoldMs` · `workerReadTimeoutMs` | 유지 시간과 워커 읽기 타임아웃(설정 절). **조회만 되고 변경은 안 된다** — 설정 파일로 정한다. 부하 보고서가 어떤 조건의 Mock 이었는지 남기는 데 쓴다 |

`latencyJitter` 를 응답에 싣는 이유는 둘이다. 부하 하네스가 이 값으로 주입한 지연의 백분위를
계산하고, 요구사항 5.4 가 *"적용한 설정과 실제 호출 결과를 함께 기록"* 하라고 정했다.

ERD 는 설정 테이블을 두지 않는다 — 환경변수 기본값 + Mock 메모리이며 재시작하면 기본값으로 돌아간다. 등록 기록은 테이블이라 그대로 남는다.

**Mock 은 프로세스 1개로 띄운다(ECS 태스크 1개 고정 · 오토스케일링 없음).** 설정과 결함이 메모리에 있어서 2개 이상이면 설정 변경이 한쪽에만 적용되고, 결함을 건 키의 요청이 다른 쪽으로 가면 발동하지 않는다. 등록 기록은 DB 라 영향이 없다. 여러 개로 띄워야 할 일이 생기면 설정·결함을 DB 로 옮기고 다시 검토한다.

---

## PUT /external/config

| 속성 | 값 |
| --- | --- |
| API 이름 | Mock 설정 변경 |
| 기능명 | Mock 지연·실패 설정 |
| 메서드 | PUT |

### 요구사항

- 지연과 실패율을 재기동 없이 변경한다. 시연 조작 패널이다. (과제 명세)
- 각 등록 시도는 시작 시점의 설정 버전을 고정한다.
- 유효 범위를 벗어난 값은 거절한다. **범위 검사는 API 에서 한다** (설정 테이블이 없으므로 DB 제약이 없다).
- 적용 실패를 저장 성공으로 표시하지 않는다. 응답은 실제 적용값이다. (요구사항 4.4)

### Request

**Headers**

```
Content-Type: application/json
Accept: application/json
```

**Body**

| 필드 | 타입 | 필수 | 제약 |
| --- | --- | --- | --- |
| `registerLatencyMs` | integer | 필수 | 지연의 **평균**. `default=500` · `min=0` · `max=60000` |
| `failureRate` | number | 필수 | `default=0.05` · `min=0` · `max=1` |
| `failureMode` | string | 선택 | `HTTP_5XX`(기본) / `TIMEOUT` |
| `latencyTailRate` | number | 선택(꼬리 묶음) | 꼬리에 걸릴 확률. `min=0` · **1 미만** |
| `latencyTailMinMs` | integer | 선택(꼬리 묶음) | 꼬리 구간 하한. `min=0` · `max=60000` · 최대 이하 |
| `latencyTailMaxMs` | integer | 선택(꼬리 묶음) | 꼬리 구간 상한(포함). `min=0` · `max=60000` |

`latencyJitter` 는 받지 않는다. 보내면 모르는 필드라 400 이다. 실행 중에 바꿀 수 있으면 등록 파트와의
계약인 `ConfigSnapshot` 이 늘어난다. 분포의 모양을 실행 중에 바꾸려면 아래 꼬리를 쓴다.
`timeoutHoldMs` 와 같은 취급이다.

**지연 꼬리(선택)** — 대부분 빠르고 드물게 수 초 걸리는 외부 시스템, 특히 **워커 타임아웃 뒤에 늦게 커밋되는 등록**을
만든다. 지연 대기는 잠금 밖이라 기다리는 동안 키 조회는 404 이고, 워커가 포기한 뒤에 커밋된다. 응답 유실 결함은
커밋한 뒤 끊어 키 조회가 처음부터 ACTIVE 라 이 경우가 아니다.

- 요청마다 `latencyTailRate` 확률로 [`latencyTailMinMs`, `latencyTailMaxMs`] 균등에서 뽑고, 나머지는 몸통에서 뽑는다.
- **전체 평균은 `registerLatencyMs` 그대로다.** 몸통 평균을 `(평균 − 비율 × 꼬리 평균) ÷ (1 − 비율)` 로 낮춘다.
  예: 500 · 2% 가 2~4초면 몸통 평균 449ms(`bodyLatencyMs`). 지터는 몸통에만 걸린다.
- 세 칸은 **함께 보내거나 함께 뺀다.** 다 빼면 꼬리 없음이고(통째 교체라 앞 설정의 꼬리를 남기지 않는다),
  일부만 보내면 400 이다 — 비율만 보내고 구간을 빠뜨린 실수가 조용히 "꼬리 없음" 이 되지 않게.
- 비율 0 이면 꼬리 없음이고 아래 평균 검사를 건너뛴다(지연 0 · 꼬리 0 도 받는다).
  비율이 0 보다 크면 `비율 × 꼬리 평균 < registerLatencyMs` 여야 한다 — 아니면 꼬리만으로 평균을 넘겨 맞출 수 없다.
- 실제로 뽑힌 대기는 등록 응답의 `X-Mock-Injected-Latency-Ms` 로 나간다. 기동할 때는 늘 꼬리 없음이다.

```json
{
  "registerLatencyMs": 500,
  "failureRate": 0.05,
  "latencyTailRate": 0.02,
  "latencyTailMinMs": 2000,
  "latencyTailMaxMs": 4000
}
```

```json
{
  "registerLatencyMs": 2000,
  "failureRate": 0.0,
  "failureMode": "HTTP_5XX"
}
```

`failureMode` 2종 — `HTTP_5XX` 즉시 500 · `TIMEOUT` 응답하지 않은 채 연결을 **유지 시간** 동안 붙잡았다가 본문 없는 500 으로 끝낸다. 유지 시간이 워커 읽기 타임아웃보다 길어 워커에는 타임아웃으로 보인다(결과 불명). 둘 다 커밋 전 일시 실패다.

유지 시간은 Mock 설정 `mock.timeout-hold-ms` 이고 **기본값 7000ms** 다. 본 서비스 워커의 HTTP 읽기 타임아웃 **5초**(be `worker` 의 `external.mock.read-timeout`)에 2초를 더한 값이다. 워커 타임아웃을 바꾸면 이 값도 함께 바꾼다.

워커 타임아웃은 Mock 설정 `mock.worker-read-timeout-ms`(기본 5000ms)로 옮겨 적는다. Mock 은 이 값을 쓰지 않고, **유지 시간이 `worker-read-timeout-ms + 2000` 보다 짧으면 기동하지 않는다**(0 은 붙잡지 않는 시험 전용이라 예외). 워커 타임아웃이 바뀌었는데 유지 시간만 그대로 두는 실수가 기동 단계에서 드러난다.

| 유지 시간 | 결과 |
| --- | --- |
| `0 < 유지 < 워커 + 2000` | **Mock 이 뜨지 않는다.** 이 검사가 없다면 워커가 타임아웃 대신 본문 없는 500 을 받을 수 있어서다. 둘이 같으면 경주가 되어 일부만 타임아웃이 된다(둘 다 5초에서 동시 200건 중 32% 가 500). 재현하려던 상황이 아니다 |
| `0` | 시험 전용. 붙잡지 않고 바로 본문 없는 500 으로 끝낸다 — 워커에는 일시 실패로 보이므로 운영 · 시연에서 쓰지 않는다 |
| 워커 타임아웃 + 2초 | 워커가 타임아웃을 겪고 Mock 도 곧 연결을 정리한다 |
| 너무 김 | 부하 시험 중 연결이 쌓인다. 1,000 RPS · `failureRate=1.0` 이면 유지 10초에 약 1만 개로 톰캣 기본 최대 연결(8,192)을 넘는다 |

### 시스템 처리

1. 값의 범위와 꼬리 묶음 규칙(위 "지연 꼬리")을 검사하고 어긋나면 400. 설정을 건드리지 않는다.
2. 설정을 바꾸고 `configVersion` 을 1 증가시킨다.
3. `appliedAt` 을 기록한다.
4. 진행 중인 등록 시도는 시작 시점 버전을 유지하고, 변경은 이후 시도부터 적용한다.
5. 반영된 설정을 그대로 반환한다.

### Response

**200 OK** — `GET /external/config` 와 같은 형식

**주요 오류**

| 응답 | errorCode | 설명 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | 범위 밖 값 · 꼬리 칸 일부만 · 꼬리 최소 > 최대 · 꼬리만으로 평균을 넘음 |

### 확인 시나리오

| 시나리오 | 기대 결과 |
| --- | --- |
| 등록 진행 중 설정 변경 | 진행 중 시도는 원래 버전 유지 |
| 음수 지연 · 0~1 밖 실패율 | 400 |
| 꼬리 칸 세 개를 다 줌 | 적용, `bodyLatencyMs` 가 보정값 (2% · 2~4초면 449) |
| 꼬리 칸을 다 뺌 | 꼬리 없음 — 앞 설정의 꼬리를 남기지 않는다 |
| 꼬리 칸 일부만 · 비율 1 이상 · 최소 > 최대 · 비율 × 꼬리 평균 ≥ 평균 | 400, 설정 그대로 |
| 지연 0 · 꼬리 0 | 적용 (비율 0 이면 몸통 평균 검사를 건너뛴다) |

**재기동 시험과 겹칠 때 주의** — 설정이 메모리라 재기동하면 기본값으로 돌아간다. 시연 순서에 "재기동 후 설정 재입력" 을 넣어두는 게 좋다.

---

# 결함 주입 · 운영

결함 주입과 초기화는 **시험 · 시연용**이다. Mock 자체가 시험용 서버라 인증이나 프로필로 따로 막지 않는다 —
공유 환경에 띄우면 주소를 아는 누구나 기록을 지울 수 있다.

결함은 DB 가 아니라 **Mock 메모리**에 둔다. 한 번 발동하면 자동 해제되고, 재기동하거나 `reset` 하면 사라진다.

## POST /external/faults

| 속성 | 값 |
| --- | --- |
| API 이름 | 결함 주입 |
| 기능명 | 외부 예약 Mock |
| 메서드 | POST |

### 요구사항

- 키를 지정해 등록 **커밋 후 응답 유실**을 주입한다.
- 키를 지정해 **느린 성공**(커밋 전에 정한 시간 기다린 뒤 정상 처리)을 주입한다. 워커 타임아웃보다 길게 걸면 **워커가 포기한 뒤 늦게 커밋되는 등록**을 정확히 한 건 만든다.
- 결함은 키 단위로 격리되며 한 번 발동하면 자동 해제된다.
- 지연·실패율로는 이 상황을 만들 수 없다. 주입한 실패는 모두 커밋 전이라 등록이 저장되지 않는다.

> 과제 원문에는 결함 주입이 없다. 요구사항 8장이 *"처리 중 종료·재기동, 외부 성공 응답 유실을 재현해
> 원래 신청의 처리 지속 확인"* 을 요구하므로 실질적으로 필수다. 5% 확률에 기대지 않고 정확히 재현하는 수단이다.

### Request

**Headers**

```
Content-Type: application/json
Accept: application/json
```

**Body**

| 필드 | 타입 | 필수 | 제약 |
| --- | --- | --- | --- |
| `externalKey` | string | 필수 | 결함을 걸 키. 등록 키와 같은 형식(영문 · 숫자 · `. _ -` 1~100자) — 등록이 받지 않는 키에 걸면 발동할 수 없다 |
| `faultType` | string | 필수 | `RESPONSE_LOST_AFTER_COMMIT` · `SLOW_SUCCESS` |
| `delayMs` | integer | `SLOW_SUCCESS` 에 필수 | 커밋 전에 기다릴 시간. `min=1` · `max=60000`. **`RESPONSE_LOST_AFTER_COMMIT` 에 주면 400** — 조용히 버리면 보낸 사람이 "기다렸다 응답을 잃는다" 고 믿는다 |

```json
{
  "externalKey": "9f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
  "faultType": "RESPONSE_LOST_AFTER_COMMIT"
}
```

```json
{
  "externalKey": "9f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
  "faultType": "SLOW_SUCCESS",
  "delayMs": 6000
}
```

### 시스템 처리

1. 키와 결함 유형을 검사한다.
2. 해당 키에 결함을 등록한다. 같은 키에 이미 결함이 있으면 새 결함으로 바꾼다.
3. 등록 요청이 그 키로 들어오면 등록 처리 **7단계**에서 발동한다. 등록을 커밋한 뒤 유지 시간 동안 응답하지 않는다(결과 불명). 다음 같은 키 요청은 저장된 성공을 재생해야 한다.
   - **새로 커밋한 등록에만 발동한다.** 이미 등록된 키에 걸면 재생 요청에는 커밋이 없어 발동하지 않고 그대로 남는다. 취소된 키(409)도 마찬가지다. 남은 결함은 `reset` 이 지운다.
4. 한 번 발동하면 자동 해제한다. 발동하지 않은 결함은 재기동하면 사라진다.
5. 다른 키에 영향을 주지 않는다.

**느린 성공(`SLOW_SUCCESS`)** — 위 3 대신 이렇게 발동한다.

1. 그 키로 등록이 들어오면 지연 주입 · 실패 판정 **뒤, 키 행 잠금 전**(등록 처리 2-1단계)에 `delayMs` 만큼 기다린 뒤 정상 처리한다.
2. 결함은 **기다리기 전에 꺼낸다** — 꺼내는 순간 한 번만 발동한다. 기다리는 동안 들어온 같은 키 재시도는 결함이 이미 없어 바로 진행한다(먼저 커밋할 수 있다 — 그러면 재시도 응답은 201 · `X-Idempotent-Replay: false` 이고 원래 요청이 재생 쪽이 된다).
3. 기다리는 동안은 잠금 전이라 **키 조회가 404** 이고, 그사이 남긴 취소 표식은 원래 요청을 409 `KEY_CANCELED` 로 막는다.
4. 기다린 시간은 `X-Mock-Injected-Latency-Ms` 에 더해 나간다. 기다리는 요청은 초기화 장벽 안에 있어 그동안 `reset` 은 409 `RESET_BUSY` 다.
5. **이미 등록됐거나 취소된 키에 걸어도 발동한다.** 잠금 전에 꺼내므로 원장을 보기 전이라, 실패 판정을 통과한 다음 등록은 기다린 뒤 평소 결과(같은 내용이면 재생 201 · 다른 내용이면 422 `KEY_PAYLOAD_MISMATCH` · 취소된 키면 409 `KEY_CANCELED`)를 받고 결함은 그때 쓰인다. 실패 판정에 걸린 요청은 기다리지 않고 결함도 쓰지 않는다 — 결함은 남아 그다음 등록에서 발동한다. 새로 커밋한 등록에만 발동하고 그 밖에는 남는 응답 유실과 다르다. 늦은 커밋을 보려면 **아직 등록하지 않은 키**에 건다.

지연 꼬리(`tail-over-timeout`)는 같은 상황을 확률로 여러 건 만든다. 이 결함은 시연에서 지정한 키 한 건으로 만든다.

### Response

**201 Created**

```json
{
  "externalKey": "9f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
  "faultType": "RESPONSE_LOST_AFTER_COMMIT",
  "delayMs": null,
  "createdAt": "2026-09-16T10:30:00.000Z"
}
```

`delayMs` 는 `SLOW_SUCCESS` 일 때 건 대기 시간이고, 응답 유실이면 `null` 이다.

**주요 오류**

| 응답 | errorCode | 설명 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | 잘못된 `faultType` · 형식이 틀린 `externalKey` · `SLOW_SUCCESS` 에 `delayMs` 없음 · 범위 밖 · 응답 유실에 `delayMs` · 헤더 오류 등. 결함을 걸지 않는다 |

---

## POST /external/reset

| 속성 | 값 |
| --- | --- |
| API 이름 | 기록 초기화 |
| 기능명 | 외부 예약 Mock |
| 메서드 | POST |

### 요구사항

- 등록·멱등·취소 기록을 지운다. **설정은 유지한다.**
- 이전 시험의 잔량이 다음 대조에 섞이지 않게 한다.
- 실수 실행을 막기 위해 확인 문자열을 요구한다.
- **시험 사이에만 부른다.** 진행 중인 등록 · 취소가 있으면 지우지 않고 409 로 거절한다.

### Request

**Headers**

```
Content-Type: application/json
Accept: application/json
```

**Body**

| 필드 | 타입 | 필수 | 제약 |
| --- | --- | --- | --- |
| `confirm` | string | 필수 | `const="RESET"` |

```json
{
  "confirm": "RESET"
}
```

### 시스템 처리

1. 확인 문자열이 `RESET` 인지 검사한다. 아니면 400.
2. 진행 중인 등록 · 취소가 있으면 **아무것도 지우지 않고** 409 `RESET_BUSY` 로 거절한다. 기다리지 않는다.
3. 등록 행과 취소 표식, 멱등 기록을 지운다. 지우는 동안 들어온 등록 · 취소는 초기화가 커밋될 때까지 기다린다.
4. 주입된 결함도 함께 지운다.
5. 지연·실패 설정과 설정 버전은 그대로 둔다.
6. 지운 건수를 반환한다.

**진행 중인 요청을 막는 이유.** 지연 · 결함의 유지 시간은 트랜잭션 밖이라 초기화를 모른다. 막지 않으면 지연 중인
등록 K → K 취소 200(표식) → 초기화(표식까지 삭제) → 지연이 끝난 등록이 커밋되어, **취소 성공을 받은 K 가
ACTIVE 로 살아난다.** `TIMEOUT` · 결함으로 붙잡힌 요청도 진행 중으로 센다 — 유지 시간(기본 7초) 동안은 거절된다.

### Response

**200 OK**

```json
{
  "deletedCount": 4821
}
```

**주요 오류**

| 응답 | errorCode | 설명 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | `confirm` 누락 또는 값 불일치 |
| 409 | `RESET_BUSY` | 진행 중인 등록 · 취소가 있다. 메시지에 건수가 나온다. 아무것도 지우지 않았다 |

```json
{
  "errorCode": "RESET_BUSY",
  "errorMessage": "진행 중인 등록 · 취소가 1건 있어 초기화할 수 없습니다. 끝난 뒤 다시 부르세요.",
  "replayable": false,
  "externalNumber": null
}
```

409 를 받으면 진행 중인 요청이 끝난 뒤 다시 부른다. 시연 중이라면 지연을 0 으로 내리고, 지금 진행 중인 요청이
끝날 만큼(설정해 둔 최대 지연 + 유지 시간) 기다린 뒤 부른다. 부하 시험 뒤에는 원장 행 수가 멎을 때까지
기다리고 부른다(하네스가 이미 그렇게 한다).

**`timeout` 시나리오 뒤는 예외다.** 아무것도 커밋하지 않아 원장 행 수는 처음부터 멎어 있지만, Mock 은 유지
시간(7초)까지 요청을 쥐고 있다. 클라이언트가 5초에 포기한 뒤에도 마지막 요청부터 최대 2초는 409 가 날 수
있다. 행 수가 아니라 마지막 요청을 보낸 뒤 유지 시간이 지났는지로 판단한다.

전체 초기화다. ERD 에 실행 범위 칸이 없어 범위를 나눠 지울 수 없다. 부하 시험을 반복할 때는 매 실행 전에 이걸 부르는 흐름이 된다.

**본 서비스의 외부 번호도 함께 비운다.** Mock 만 지우면 전에 준 번호가 다시 나올 수 있고, 본 서비스는 번호를 UNIQUE 로 저장한다. 겹칠 확률은 실행마다 쌓인다 — 같은 날 5,000건씩 14번 돌린 뒤 15번째 실행에서 하나라도 겹칠 확률이 약 3.4% 다.
