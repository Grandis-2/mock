# 외부 예약 Mock API 명세

| 문서 정보 | 내용 |
| --- | --- |
| 대상 | 외부 예약 시스템 Mock · 기능 F-S-05 · F-A-03 |
| 버전 / 작성일 | 5.19 / 2026-10-19 |
| 서버 | `http://localhost:8081` |
| 개수 | 12개 |
| 기준 | 요청 본문은 본 서비스의 `RegisterRequestPayload` · `CancelRequestPayload`, 원장과 응답은 ERD v14.1의 `external_mock` 영역입니다. `customer_id` · `product_id`만 UUID(`BINARY(16)`)입니다 |

> **이 문서가 Mock API 계약의 정본입니다.** 계약을 바꾸려면 이 파일을 고치는 PR로 하고, 구현과 다르면 둘 가운데 하나가 버그입니다.

**요청은 본 서비스 형식, 원장과 응답은 ERD 형식입니다.** 요청은 본 서비스가 보내는 이름 그대로 받아 원장 칸으로 바꿔 넣고(1-7 「내용 비교」), 응답은 원장 이름(`customerId` · `productId` · `sku` · `externalNumber`)으로 나갑니다.

## 목차

1. [공통 사항](#1-공통-사항)
   - [실행 전제](#1-1-실행-전제) · [서버 · 인증 · 응답 형식](#1-2-서버--인증--응답-형식) · [오류 분류 계약](#1-3-오류-분류-계약) · [요청 검증](#1-4-요청-검증) · [지연 · 실패 적용 범위](#1-5-지연--실패-적용-범위) · [멱등 키](#1-6-멱등-키) · [내용 비교](#1-7-내용-비교) · [등록 자원의 필드](#1-8-등록-자원의-필드)
2. [예약](#2-예약)
   - [예약 등록](#2-1-예약-등록-post-externalreservations) `POST /external/reservations`
   - [원장 목록 조회](#2-2-원장-목록-조회-get-externalreservations) `GET /external/reservations`
   - [번호로 단건 조회](#2-3-번호로-단건-조회-get-externalreservationsexternalnumber) `GET /external/reservations/{externalNumber}`
   - [키로 등록 상태 조회](#2-4-키로-등록-상태-조회-get-externalreservationsby-keyexternalkey) `GET /external/reservations/by-key/{externalKey}`
   - [예약 취소](#2-5-예약-취소-post-externalcancellations) `POST /external/cancellations`
3. [럭키드로우](#3-럭키드로우)
   - [응모](#3-1-응모-post-externaldraw-entries) `POST /external/draw-entries`
   - [회차 조회](#3-2-회차-조회-get-externaldrawsdrawid) `GET /external/draws/{drawId}`
   - [추첨](#3-3-추첨-post-externaldrawsdrawiddraw) `POST /external/draws/{drawId}/draw`
4. [설정](#4-설정)
   - [설정 조회](#4-1-설정-조회-get-externalconfig) `GET /external/config`
   - [설정 변경](#4-2-설정-변경-put-externalconfig) `PUT /external/config`
5. [결함 주입 · 운영](#5-결함-주입--운영)
   - [결함 주입](#5-1-결함-주입-post-externalfaults) `POST /external/faults`
   - [기록 초기화](#5-2-기록-초기화-post-externalreset) `POST /external/reset`

---

# 1. 공통 사항

## 1-1. 실행 전제

| 항목 | 내용 |
| --- | --- |
| 저장 | 등록 기록, 럭키드로우 회차와 응모는 `external_mock` 스키마(MySQL)에 둡니다 |
| 설정 · 결함 | 기본은 서버 메모리(`mock.shared-state=memory`, 1대용)이고, **여러 대면 `redis`**로 Redis에서 나눕니다(4-1) |
| 정합성 검사 | 원장 목록 조회 `GET /external/reservations`로 읽습니다. 실제 외부 시스템처럼, 본 서비스는 `external_mock` 스키마를 직접 조회하지 않습니다 |

## 1-2. 서버 · 인증 · 응답 형식

Mock은 본 서비스와 별개의 프로세스이므로, 팀 「API 공통 규칙」이 여기에 적용되지 않습니다.

| 공통 규칙 항목 | Mock 적용 |
| --- | --- |
| 공통 응답 봉투 (success / data / error / timestamp / traceId) | ❌ 미적용, Mock 자체 JSON |
| `X-Session-Token` 인증 · ADMIN 권한 | ❌ 미적용, 인증 요구 없음 |
| 공통 오류 401 UNAUTHENTICATED · 403 FORBIDDEN | ❌ 미적용 |
| 본 서비스 기본 주소 `http://localhost:8080/api/v1` | ❌ Mock은 `http://localhost:8081` |
| 공통 오류 500 INTERNAL_ERROR | ❌ 미적용, Mock의 500은 `UPSTREAM_UNAVAILABLE`(주입 실패) |
| `Idempotency-Key`는 같은 신청 재시도에서 같은 값 · 본문 사용 | ✅ 적용 |
| 204 = 본문 없음 | ✅ 적용 |
| `null`과 필드 생략은 다름 | ✅ 적용 |

Mock은 남의 회사 시스템을 흉내 냅니다. 우리 오류 형식을 따르지 않는 편이 오히려 현실적입니다.

성공하면 자원 객체를 그대로 돌려주고, 실패하면 아래 형식으로 돌려줍니다.

```json
{
  "errorCode": "KEY_CANCELED",
  "errorMessage": "이미 취소된 키입니다.",
  "replayable": true,
  "externalNumber": null
}
```

| 필드 | 뜻 |
| --- | --- |
| `replayable` | 이 결과가 저장되어, 같은 키의 재요청에도 같은 응답이 나오는지를 뜻합니다. 일시 실패는 저장하지 않으므로 `false`입니다. **재시도해도 되는지를 뜻하지 않습니다.** 500도 `false`지만 재시도 대상입니다. 재시도 여부는 `errorCode`로 정합니다(1-3) |
| `externalNumber` | `KEY_CANCELED` · `KEY_PAYLOAD_MISMATCH`인데 그 키에 이미 번호가 있으면 채웁니다. 그 밖에는 `null`이며 필드를 생략하지 않습니다 |

## 1-3. 오류 분류 계약

본 서비스는 HTTP 상태가 아니라 `errorCode`로 분기합니다. 같은 409라도 뜻이 다르기 때문입니다.

| 응답 | errorCode | 본 서비스 처리 |
| --- | --- | --- |
| 201 (재생 포함) | 없음 | 확정 반영 |
| 409 | `KEY_CANCELED` | 취소된 키입니다. 확정하지 않고 현재 상태로 정리합니다 |
| 409 | `RESET_BUSY` | 워커는 받지 않습니다. 초기화(제어 API) 전용이며, 진행 중인 등록 · 취소 · 응모 · 추첨이 있어 초기화를 거절했다는 뜻입니다 |
| 409 | `DRAW_CLOSED` | 럭키드로우: 이미 추첨한 회차에 새 응모가 왔습니다. 저장하지 않습니다. DLQ로 보내 사람이 정리합니다 |
| 409 | `CUSTOMER_ALREADY_ENTERED` | 럭키드로우: 같은 회차에 같은 회원의 다른 응모가 왔습니다. 저장하지 않습니다. 본 서비스 버그라 DLQ로 보냅니다 |
| 409 | `ENTRY_COUNT_MISMATCH` | 럭키드로우: 추첨 요청의 기대 응모 수와 받은 수가 다릅니다. 아무것도 바꾸지 않습니다. 다른 4xx와 달리 확정 거절이 아니라 **늦춰서 다시** 보냅니다 |
| 422 | `INVALID_WINNER_COUNT` | 럭키드로우: 당첨 인원 ≥ 받은 응모 수입니다. 본 서비스는 전원 당첨이면 부르지 않으므로 본 서비스 버그입니다. DLQ로 보냅니다 |
| **422** | **`KEY_PAYLOAD_MISMATCH`** | **같은 키로 다른 내용이 왔습니다. 본 서비스 버그이므로 재시도하지 않고 오류로 남깁니다** |
| 400 | `INVALID_REQUEST` | 계약 · 설정 오류입니다 |
| 500 | `UPSTREAM_UNAVAILABLE` | 일시 실패(주입 실패)입니다. 커밋 전이라 아무것도 저장되지 않았습니다 |
| 404 | `NOT_FOUND` | 조회 대상의 기록이 없습니다. 키 조회의 404는 "지금 등록이 없다"는 뜻입니다(2-4 「본 서비스의 판단」) |
| 404 | `NO_SUCH_ENDPOINT` | 없는 경로입니다. 주소 · 경로 설정 오류이므로 재시도하지 않고 오류로 알립니다. **"등록 없음"으로 읽지 않습니다** |
| 타임아웃 | 없음 | **결과 불명**입니다(아래) |
| `errorCode`가 없는 응답 | 없음 | **결과 불명**입니다(아래). 본문 없는 500, 연결 끊김, Mock 앞단 장비(로드 밸런서 등)가 대신 보낸 응답이 여기에 듭니다 |

**5xx와 타임아웃은 미등록의 증거가 아닙니다.** 재시도 전에 `GET /external/reservations/by-key/{externalKey}`로 등록 여부를 먼저 확인합니다.

### 결과 불명

응답만으로 등록이 됐는지 알 수 없는 경우입니다. 타임아웃과 `errorCode`가 없는 응답이 여기에 듭니다.

- Mock이 응답하지 않는 두 경우(`failureMode=TIMEOUT`, 결함 `RESPONSE_LOST_AFTER_COMMIT`)는 유지 시간(기본 7초) 동안 아무것도 보내지 않다가 본문 없는 500으로 끝납니다. 유지 시간을 워커 읽기 타임아웃(기본 3초, 운영도 3초)보다 2초 이상 길게 두므로 워커에게는 타임아웃으로 보입니다. 본문 없는 500을 받았더라도 같게 처리합니다.
- `errorCode`가 없는 응답을 워커가 직접 받아 보려면 `failureMode=MIXED`를 씁니다(4-2 설정 변경). 실패에 걸린 요청에 본문 없는 500과 앞단 장비를 흉내 낸 HTML 502 · 503 · 504를 바로 보냅니다. 모두 커밋 전이라 키 조회는 404입니다.

| 순서 | 워커가 하는 일 |
| --- | --- |
| 1 | 새 키를 만들지 않습니다 |
| 2 | 키 조회로 확인합니다. ACTIVE면 그 번호로 확정하고, 취소 표식이면 정리하고, 404면 **같은 키로** 다시 등록합니다 |
| 3 | 재시도를 다 써도(DEAD_LETTER) Mock 취소를 보내지 않습니다. 같은 키로 취소하는 것은 예약을 취소로 끝낼 때뿐입니다(2-4 「본 서비스의 판단」) |

500은 주입 실패 말고도 아래 경우에 같은 코드로 납니다. 워커에게는 모두 일시 실패이고, 원인은 `errorMessage`로 봅니다.

| 500의 원인 | `errorMessage` |
| --- | --- |
| 주입 실패 (`failureMode=HTTP_5XX`) | `외부 시스템을 사용할 수 없습니다.` |
| 중복 키 재시도 상한 초과 | `중복 키 재시도 상한(5회)을 넘었습니다.` |
| 처리하지 못한 오류 (교착 등 Mock의 문제) | `Mock 이 처리하지 못한 오류입니다.` |

- 재시도해도 결과가 같은 요청은 반드시 4xx입니다. 메서드 · `Content-Type` · `Accept`가 틀려도 405 · 415 · 406이 아니라 400 `INVALID_REQUEST`이고, 무엇이 틀렸는지를 메시지에 담습니다.
- 등록 · 조회 · 취소와 제어 API(설정 · 결함 · 초기화)는 헤더 검사를 **처리 전에** 합니다. `Content-Type`이 `application/json`이 아니거나 `Accept`가 JSON을 받지 않는 요청은 아무것도 남기지 않습니다. 설정을 바꾸거나 결함을 걸거나 기록을 지우지 않습니다. 오류 본문은 `Accept`와 상관없이 JSON입니다.

## 1-4. 요청 검증

**요청 본문에 계약에 없는 필드가 있으면 400 `INVALID_REQUEST`입니다.** 모르는 필드를 조용히 버리면 워커가 계약과 다른 본문을 보내도 아무도 모르고, 설정 API에 없는 값을 넣어도 200이 나와 적용된 줄 알게 됩니다. 저장하지 않는 필드(취소의 `reason`)도 계약에 있으면 받습니다.

**타입이 다른 값도 바꿔 받지 않습니다.** `"qty": "1"`(문자열 숫자), `1.9`(실수 → 정수), `""`(빈 문자열), `"customerRef": 1001`(숫자 → 문자열), 열거형 자리의 숫자(`"failureMode": 1`)는 모두 400입니다. 바꿔 주면 워커의 타입 오류가 Mock에서는 묻히고 실제 외부 시스템에서야 드러나기 때문입니다. 값이 바뀌지 않는 정수 → 실수(`"failureRate": 1`)만 받습니다. **같은 필드가 두 번 오면** 마지막 값을 쓰지 않고 400입니다.

400의 `errorMessage`에는 무엇이 틀렸는지 담습니다(예: `customerId 은(는) 알 수 없는 필드입니다. 받을 수 있는 필드: …`, `optionCode 은(는) 필수입니다.`, `ourReservationId 가 Idempotency-Key 와 다릅니다.`). 문구는 사람이 읽으라고 둔 것이고, 워커는 `errorCode`로 분기합니다.

같은 키의 동시 요청은 잠금 대기로 직렬화하므로, "처리 중"을 뜻하는 응답은 두지 않습니다.

## 1-5. 지연 · 실패 적용 범위

| 호출 | 지연 | 실패 주입 |
| --- | --- | --- |
| 등록 | ✅ 적용 | ✅ 적용 (기본 5%) |
| 취소 | ❌ | ❌ 과제 "항상 성공 가정" |
| 럭키드로우 응모 · 추첨 | ✅ 등록과 같은 설정 | ✅ 등록과 같은 설정, worker의 재시도 시험용 |
| 조회 (럭키드로우 회차 조회 포함) | ❌ | ❌ |
| 설정 · 결함 · 초기화 | ❌ | ❌ |

- 실패는 **커밋 전에** 일어나므로 아무것도 저장하지 않습니다.
- 5%는 호출 하나가 실패할 확률입니다. 예약의 최종 실패율이 아니고, 관측이 매번 정확히 5%일 필요도 없습니다(요구사항 5.1 · 5.4).
- **지연은 평균값입니다**(요구사항 2장 "평균 500ms 지연"). 요청마다 `registerLatencyMs × (1 ∓ latencyJitter)`에서 균등하게 뽑아, 기본값이면 **300 ~ 700ms**입니다. `latencyJitter`(기본 0.4 · 0 ~ 1)는 설정 파일로만 정합니다.
- 시험별 지연 · 실패율 조합(프리셋)은 [operations.md](operations.md) 1-1에 있습니다.

## 1-6. 멱등 키

```text
Idempotency-Key: {본 서비스의 preorders.preorder_token}
```

ERD의 `preorder_token`은 `char(36)` UUID이며 "공개 UUID이자 외부 Mock 등록 · 취소 키"입니다.

- 접수 때 정하고 바꾸지 않습니다. 재시도 · 리스 회수 · 보상에서 같은 값입니다.
- 등록 · 조회 · 취소가 같은 키를 씁니다.
- 결과 불명이라고 새 키를 만들지 않습니다. 같은 키로 재시도하거나 `by-key` 조회로 먼저 확인합니다.
- 식별자 비교는 **대소문자를 구별합니다.** ERD가 키 칸을 `COLLATE utf8mb4_bin`으로 둔 것과 같은 규칙입니다.
- Mock은 키를 UUID로 강제하지 않습니다. **영문 · 숫자 · `.` `_` `-`로 된 1 ~ 100자**(ERD 키 칸 `varchar(100)`)면 받습니다. 그래서 Mock 단독 시험에서 `test-lost-1` 같은 키를 쓸 수 있습니다. 외부 번호(`externalNumber`)도 같은 규칙입니다.
- 그 밖의 문자는 400입니다. 받아 놓고 다시 찾을 수 없는 키를 만들지 않기 위해서입니다.

| 막는 것 | 받으면 생기는 일 |
| --- | --- |
| `/` | 키 조회는 키를 경로에 넣습니다. 등록은 되는데 키 조회로 찾을 수 없습니다 |
| 공백 | 키 칸 콜레이션이 끝 공백을 무시하고 비교합니다(PAD SPACE). `pad-1 `의 등록을 `pad-1`의 조회 · 취소가 잡습니다 |
| 비 ASCII | 헤더가 ISO-8859-1로 읽혀 깨진 채 저장됩니다 |
| `,` | `Idempotency-Key` 헤더가 두 개면 `a,b` 한 키로 합쳐집니다 |
| `.` · `..`만으로 된 키 | 경로에서 현재 · 상위 경로로 해석됩니다 |

## 1-7. 내용 비교

같은 키의 재요청이 **같은 신청인지** 판정할 때, 저장된 행의 아래 세 칸(ERD)을 요청과 직접 비교합니다. 요청은 preorder 형식이라 바꿔서 비교합니다.

| 원장 칸 (ERD) | 요청 필드 | 바꾸는 법 |
| --- | --- | --- |
| `customer_id` | `customerRef` | UUID로(`BINARY(16)`). **소문자 표준 UUID(`xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`)만** 받습니다 |
| `product_id` | `itemCode` | UUID로. 같은 규칙입니다 |
| `sku` | `optionCode` | 그대로 |

거절 문장에는 **요청 필드 이름**(`customerRef` · `itemCode` · `optionCode`)으로 어느 것이 다른지 담습니다. 보낸 쪽이 자기 본문에서 찾을 수 있어야 하기 때문입니다.

- **대문자나 다른 표기의 UUID는 400입니다.** 대문자, 하이픈 없음, 중괄호, `urn:uuid:`, 숫자 id(`"1001"`)는 보낸 쪽의 버그라, 정규화해서 받지 않고 드러냅니다. 본 서비스는 `UUID.toString()`으로 보내므로 걸리지 않습니다.
- **`qty`와 `scope`는 저장 · 비교하지 않습니다**(ERD에 칸 없음). 사전예약은 수량 1 고정이라 `qty`는 1만 받고, 그 밖의 값은 같은 키 · 다른 내용(422)이 아니라 잘못된 요청(400)입니다. **`scope`는 비교하지 않으므로, 같은 키로 `scope`만 달라도 재생됩니다.**
- `ourReservationId`는 키와 같은 값이라 비교 대상이 아닙니다. 다르면 처리 전에 400입니다.

**본 서비스가 같은 키로 다른 내용을 보내는 것은 정상 흐름이 아닙니다.** `preorder_sync_jobs.request_payload`가 접수 때 고정되기 때문입니다. 그래도 거절하는 이유는 그 버그를 조용히 넘기지 않기 위해서입니다.

## 1-8. 등록 자원의 필드

원장 `external_mock.preorder_registrations`와 칸이 1:1입니다. 응답과 조회는 이 이름으로 나갑니다.

| 필드 | 타입 | ERD 칸 | 설명 |
| --- | --- | --- | --- |
| `externalKey` | string | `external_key` (PK) | 우리 `preorders.preorder_token`. 등록 본문의 `ourReservationId`와 같은 값 |
| `externalNumber` | string | `external_number` (UNIQUE) | Mock이 최초 등록에서 발급. 등록 전 취소 표식이면 `null` |
| `customerId` | string (UUID) | `customer_id` | 우리 `customers.id`. 요청의 `customerRef`와 같은 글자. 물리 FK 없음 |
| `productId` | string (UUID) | `product_id` | 우리 `products.id`. 요청의 `itemCode` |
| `sku` | string | `sku` | 우리 `product_options.sku`. 요청의 `optionCode` |
| `status` | string | `status` | `ACTIVE` / `CANCELED` |
| `confirmedAt` | datetime | `confirmed_at` | 등록을 확정한 시각 |
| `canceledAt` | datetime | `canceled_at` | 취소 표식을 남긴 시각 |

- `status = ACTIVE`이면 나머지 필드가 모두 채워져 있어야 합니다. ERD의 `ck_registration_active_fields` CHECK가 그것입니다. 등록 전 취소는 키 · 상태 · 취소 시각만 있는 행으로 남습니다.
- 시각은 모두 UTC이고 **밀리초 세 자리**로 씁니다(`2026-09-16T10:00:03.000Z`). 밀리초가 0이어도 `.000`을 붙입니다. 제어 API의 `appliedAt` · `createdAt`도 같습니다.

---

# 2. 예약

## 2-1. 예약 등록: POST /external/reservations

### 요구사항

- 본 서비스가 접수한 예약을 등록하고 외부 예약번호를 발급합니다. 형식은 **`R-yyyyMMdd-NNNNNNNNNN`**(UTC 발급일 + 10자리 난수)이고, 겹치면 새 번호로 다시 뽑습니다. 부하 시험을 반복해도 번호가 잘 겹치지 않게 10자리입니다(5-2 reset).
- 같은 키 · 같은 내용의 성공 요청은 같은 외부 예약번호를 돌려줍니다(과제 명세).
- **같은 키 · 다른 내용의 요청은 422로 거절합니다**(요구사항 5.3 · ERD).
- 키 선점 · 등록 · 결과 기록을 원자적으로 처리합니다.
- 성공만 저장 · 재생하며, 일시 실패는 저장하지 않습니다.
- 커밋 후 응답만 유실된 경우 저장된 성공을 재생합니다.
- 취소 표식이 있는 키는 재생보다 먼저 거절합니다.
- 설정된 지연과 실패 확률을 이 API에만 적용합니다.

### Request

**Headers**

```text
Content-Type: application/json
Accept: application/json
Idempotency-Key: 9f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f
```

| 이름 | 위치 | 타입 | 필수 | 제약 |
| --- | --- | --- | --- | --- |
| `Idempotency-Key` | header | string | 필수 | 영문 · 숫자 · `. _ -` 1 ~ 100자(1-6 멱등 키). 본 서비스는 `preorder_token`(`char(36)` UUID)을 보냅니다 |

**Body**: 본 서비스의 `RegisterRequestPayload` 그대로입니다. 원장에 들어가는 칸은 1-7 「내용 비교」 표를 따릅니다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `ourReservationId` | string | 필수 | 우리 `preorder_token`. **`Idempotency-Key` 헤더와 같아야 합니다.** 다르면 400이고 어느 키로도 저장하지 않습니다 |
| `customerRef` | string | 필수 | `customers.id`(UUID) 문자열 → `customer_id`. **소문자 표준 UUID** |
| `itemCode` | string | 필수 | `products.id`(UUID) 문자열 → `product_id`. 같은 규칙 |
| `optionCode` | string | 필수 | `product_options.sku` → `sku`. `maxLength=80` |
| `qty` | integer | 필수 | 수량. **1만** 받습니다. 저장하지 않습니다 |
| `scope` | string | 필수 | 요청 범위. 지금은 `preorder`. `maxLength=50`. 저장 · 비교하지 않습니다 |

```json
{
  "ourReservationId": "9f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
  "customerRef": "0199a3f2-7c4e-7a10-8b2d-3f4e5a6b7c8d",
  "itemCode": "0199a3f2-7c4e-7b20-9c3e-4f5a6b7c8d9e",
  "optionCode": "SM-G999-256-BLK",
  "qty": 1,
  "scope": "preorder"
}
```

### 시스템 처리

지연 · 실패 판정은 **잠금 밖**에서 먼저 처리합니다. 잠금을 쥐는 시간을 짧게 유지하고, 지연 중에도 취소 요청이 들어올 수 있게 하기 위해서입니다.

**트랜잭션 밖**

1. 설정된 `registerLatencyMs`를 **평균으로** 대기합니다(`× (1 ∓ latencyJitter)` 균등분포). **잠금을 아직 잡지 않았으므로 그사이 취소 요청이 표식을 남길 수 있습니다.**
2. 설정된 `failureRate`로 일시 실패를 일으킵니다. 커밋 전이므로 아무것도 저장하지 않습니다.

**트랜잭션 안**

3. 키 행을 **잠금 읽기**로 잠급니다. 같은 키의 다른 요청이 처리 중이면 그 트랜잭션이 끝날 때까지 기다립니다. 행이 없으면 잠금이 걸리지 않으며(READ COMMITTED), 같은 새 키의 동시 요청은 6단계 INSERT에서 직렬화됩니다.
4. **취소 표식이 있으면 409 `KEY_CANCELED`입니다.** 등록을 만들지 않으며, 재생보다 먼저 검사합니다.
5. 등록이 이미 있으면 다음과 같습니다.
   - **내용이 같으면** 201 + `X-Idempotent-Replay: true`로 재생합니다.
   - **내용이 다르면** 422 `KEY_PAYLOAD_MISMATCH`입니다. 기존 등록을 바꾸지 않습니다.
6. 등록을 커밋하고 201로 응답합니다. INSERT가 1062(중복 키)를 받으면 다른 요청(등록 · 취소)이 먼저 만든 것이므로 3단계부터 다시 합니다. ERD가 1062를 정상 분기로 정했습니다.
7. `RESPONSE_LOST_AFTER_COMMIT` 결함이 걸려 있으면, 커밋한 뒤 유지 시간 동안 응답하지 않습니다(결과 불명).

> 지연이 트랜잭션 밖이라 재생되는 요청도 지연을 한 번 겪습니다. 순서를 이렇게 둔 이유는 [README](../README.md) 「설계와 기술 특이점」 1에 있습니다.

### Response

**201 Created**

응답 헤더는 `X-Idempotent-Replay`(boolean) · `X-Mock-Config-Version`(integer) · `X-Mock-Injected-Latency-Ms`(integer)이고, 주입한 실패에만 `X-Mock-Injected-Failure`(string)가 붙습니다.

| 헤더 | 설명 |
| --- | --- |
| `X-Mock-Injected-Latency-Ms` | 이 요청에 **실제로 뽑힌 지연**(ms)이며, 0이어도 싣습니다. 지연이 원장을 보기 전이라 201만이 아니라 409 · 422 · 500에도 실립니다(형식이 틀린 400에는 없음). 부하 판정이 요청마다 `관측 − 이 값`으로 Mock이 더한 시간을 구할 때 쓰며, 워커는 쓰지 않습니다 |
| `X-Mock-Injected-Failure` | **일부러 낸 실패에만** 붙는 시험용 표식이고, 값은 그 종류입니다(`HTTP_500` · `HTTP_500_NO_BODY` · `HTTP_502` · `HTTP_503` · `HTTP_504` · `TIMEOUT`). 부하 판정이 주입 실패와 Mock의 진짜 오류를 가를 때 씁니다. **실제 장비는 이 헤더를 보내지 않으므로 워커는 이것으로 분기하지 않습니다** |

```json
{
  "externalKey": "9f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
  "externalNumber": "R-20260916-0048213579",
  "customerId": "0199a3f2-7c4e-7a10-8b2d-3f4e5a6b7c8d",
  "productId": "0199a3f2-7c4e-7b20-9c3e-4f5a6b7c8d9e",
  "sku": "SM-G999-256-BLK",
  "status": "ACTIVE",
  "confirmedAt": "2026-09-16T10:00:03.412Z",
  "canceledAt": null
}
```

**주요 오류**

| 응답 | errorCode | 설명 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | 필수 값 누락 · 계약에 없는 필드 · 키 길이 · `ourReservationId` ≠ 헤더 키 · 참조가 소문자 표준 UUID가 아님 · `qty` ≠ 1 |
| 409 | `KEY_CANCELED` | 취소 표식이 있는 키 |
| **422** | **`KEY_PAYLOAD_MISMATCH`** | **같은 키로 다른 `customerRef` · `itemCode` · `optionCode`** (`scope`는 비교하지 않음) |
| 500 | `UPSTREAM_UNAVAILABLE` | `failureMode=HTTP_5XX`의 주입 실패(MIXED의 `HTTP_500`도 같습니다). 커밋 전 |
| 500 | 없음 (본문 없음) | `failureMode=MIXED`의 `HTTP_500_NO_BODY`. 커밋 전. 워커에게는 결과 불명 |
| 502 · 503 · 504 | 없음 (HTML 본문) | `failureMode=MIXED`의 앞단 장비 흉내. `Content-Type: text/html`, `Accept`와 상관없이 HTML. 커밋 전. 워커에게는 결과 불명 |

MIXED의 HTML 본문은 대표 모양입니다(장비마다 문구가 달라 정답이 없습니다). 워커는 문구가 아니라 상태와 `errorCode` 유무로 분기해야 합니다.

```html
<html>
<head><title>502 Bad Gateway</title></head>
<body>
<center><h1>502 Bad Gateway</h1></center>
</body>
</html>
```

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
| 같은 키 · 같은 내용 연속 2회 | 같은 번호. 2회차 `X-Idempotent-Replay: true` |
| **같은 키 · 다른 optionCode** | **422 `KEY_PAYLOAD_MISMATCH`. 기존 등록 그대로** |
| `ourReservationId`가 헤더 키와 다름 | 400 `INVALID_REQUEST`. 어느 키로도 저장 안 됨 |
| `customerRef`가 대문자 UUID · 하이픈 없는 표기 · 숫자 `"1001"` | 400 `INVALID_REQUEST`. 소문자 표기와 같은 신청으로 보지 않음(등록된 키에도 422 · 재생이 아님) |
| `qty`가 1이 아님 | 400 `INVALID_REQUEST` (422 아님) |
| 같은 키 · `scope`만 다름 | 201 재생 (`scope`는 비교하지 않음) |
| 같은 키 동시 10건 | 정확히 1건만 등록. 번호 1개. 9건은 `X-Idempotent-Replay: true` |
| 취소된 키로 등록 시도 | 409 `KEY_CANCELED`. 등록 생성 안 됨 |
| **등록 성공 → 취소 → 같은 키 재등록** | **409 `KEY_CANCELED`.** 201 재생이 아님 |
| 실패율 1.0으로 10회 실패 → 0.0 변경 후 재시도 | 즉시 성공. 저장된 실패를 재생하지 않음 |
| `RESPONSE_LOST_AFTER_COMMIT` 주입 후 재시도 | 저장된 성공 재생. 등록 1건 |
| 커밋 직후 강제 종료 → 재기동 → 같은 키 재요청 | 저장된 성공 재생. 새 번호 발급 없음 |

- **동시 요청 시나리오는 최소 100회 반복해야 의미가 있습니다.** 동시성 버그는 경합에서만 나오므로, 한 번 통과는 증명이 아닙니다.
- **실패율 시나리오는 100%로 돌려야 드러납니다.** 일시 실패를 저장하면 실패율을 0%로 내려도 저장된 실패가 영원히 재생되어, 예약이 최종 확정에 도달하지 못합니다. 5%로만 시험하면 보이지 않습니다.

---

## 2-2. 원장 목록 조회: GET /external/reservations

### 요구사항

- 원장의 모든 행을 외부 키 순서로 한 페이지씩 돌려줍니다. 등록 행과 취소 표식 행(등록 전 취소)을 모두 줍니다.
- **정합성 검사가 Mock 쪽 등록을 전부 받아 갈 때 씁니다.** 키 조회는 본 서비스가 아는 키만 물을 수 있어서, "Mock에만 있는 등록"이나 "같은 회원 · 상품의 활성 등록 2건"을 찾지 못하기 때문입니다.
- 등록용 지연 · 실패 설정을 적용하지 않습니다.

### Request

**Headers**

```text
Accept: application/json
```

| 이름 | 위치 | 타입 | 필수 | 제약 |
| --- | --- | --- | --- | --- |
| `cursor` | query | string | 선택 | 이 외부 키보다 큰 행부터 줍니다. 앞 응답의 `nextCursor`를 그대로 보냅니다. 없으면 처음부터입니다. 영문 · 숫자 · `. _ -` 1 ~ 100자 |
| `size` | query | integer | 선택 | 한 페이지 건수. 1 ~ 1000, 기본 500 |

**Body**: 없음

```text
GET /external/reservations?size=500
GET /external/reservations?cursor=9f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f&size=500
```

### 시스템 처리

1. 쿼리 파라미터를 본문처럼 엄격하게 검사합니다. **모르는 이름 · 같은 이름 두 번 · 빈 값 · 범위를 벗어난 `size`는 400입니다.** 모르는 이름을 버리면 `productId=…`로 거른 줄 알고 전체를 받기 때문입니다.
2. 외부 키가 `cursor`보다 큰 행을 키 순서로 `size`건 읽습니다. 잠그지 않으므로, 진행 중인 등록을 기다리지 않고 목록 읽기가 등록을 막지도 않습니다.
3. 더 남아 있으면 `nextCursor`에 이 페이지 마지막 행의 키를, 없으면 `null`을 넣습니다. 남은 행이 딱 `size`건이어도 `null`입니다(빈 페이지를 한 번 더 부르지 않게).

**외부 키 순서로 넘기는 이유.** 키는 바뀌지 않으므로, 넘기는 사이에 등록 · 취소가 들어와도 원래 있던 행은 정확히 한 번씩 나옵니다. 오프셋 방식이면 앞쪽에 새 행이 끼는 순간 한 행이 두 번 나오거나 빠집니다. 넘기는 중에 새로 생긴 행은 나올 수도 안 나올 수도 있습니다.

### Response

**200 OK**

`items` 한 건은 `POST /external/reservations`의 201과 같은 형식입니다. 취소 표식만 있는 행은 `externalNumber` · `customerId` · `productId` · `sku` · `confirmedAt`이 `null`입니다. 값이 없어도 필드를 생략하지 않습니다.

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
      "customerId": "0199a3f2-7c4e-7a10-8b2d-3f4e5a6b7c8d",
      "productId": "0199a3f2-7c4e-7b20-9c3e-4f5a6b7c8d9e",
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
| 400 | `INVALID_REQUEST` | 모르는 쿼리 파라미터 · 같은 파라미터 두 번 · `cursor` 형식이 틀림 · `size`가 1 ~ 1000 정수가 아님 |

### 확인 시나리오

| 시나리오 | 기대 결과 |
| --- | --- |
| `nextCursor`를 따라 끝까지 부름 | 모든 행이 키 순서로 한 번씩. 마지막 응답의 `nextCursor`는 `null` |
| 취소 표식만 있는 키 | 목록에 나옴(`status` `CANCELED`, 번호 · 상품 필드 `null`) |
| 실패율 100% | 200, 지연 · 실패를 적용하지 않음 |
| `?productId=<상품 UUID>` | 400. 거르지 않고 전체를 주는 일이 없음 |

---

## 2-3. 번호로 단건 조회: GET /external/reservations/{externalNumber}

### 요구사항

- 외부 예약번호로 단건 조회하며, 취소된 등록도 돌려줍니다.
- 관리자 화면에서 특정 예약의 외부 상태를 확인할 때 씁니다.
- 등록용 지연 · 실패 설정을 적용하지 않습니다.

### Request

**Headers**

```text
Accept: application/json
```

| 이름 | 위치 | 타입 | 필수 | 제약 |
| --- | --- | --- | --- | --- |
| `externalNumber` | path | string | 필수 | 영문 · 숫자 · `. _ -` 1 ~ 100자 |

**Body**: 없음

### 시스템 처리

1. 번호 형식이 틀리면 400이고 조회하지 않습니다. 404 `NOT_FOUND`는 "그 기록이 없다"에만 씁니다.
2. 외부 예약번호로 등록 행을 조회합니다.
3. 취소된 등록이면 `status`를 `CANCELED`로, `canceledAt`을 채워 돌려줍니다. 숨기지 않습니다.
4. 없으면 404입니다.

### Response

**200 OK**: `POST /external/reservations`의 201과 같은 형식입니다.

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

## 2-4. 키로 등록 상태 조회: GET /external/reservations/by-key/{externalKey}

### 요구사항

- 외부 키로 등록과 취소 표식을 함께 조회합니다.
- **응답 유실 후 재시도하기 전에 확인하는 용도입니다.** 응답이 유실되면 본 서비스는 외부 번호를 모르므로, 번호 기준 조회로는 확인할 수 없습니다.
- `external_key`가 PK라 한 키의 등록은 최대 1건이고, `registrations` 배열은 0 ~ 1개입니다.
- `storedOutcome`에는 성공만 나타납니다. 일시 실패는 저장하지 않기 때문입니다.
- 기록이 전혀 없으면 404입니다. 이는 **조회 시점에 커밋됐거나 트랜잭션 안에 있는 등록이 없다**는 뜻입니다. 지연 구간(트랜잭션 밖)에서 기다리는 요청과 아직 도착하지 않은 요청은 포함하지 않으므로, 404 뒤에 같은 키가 등록될 수 있습니다.

### Request

**Headers**

```text
Accept: application/json
```

| 이름 | 위치 | 타입 | 필수 | 제약 |
| --- | --- | --- | --- | --- |
| `externalKey` | path | string | 필수 | 영문 · 숫자 · `. _ -` 1 ~ 100자. 우리 `preorder_token` |

**Body**: 없음

### 시스템 처리

1. **키 행을 잠금 읽기(`SELECT ... FOR SHARE`)로 조회합니다.** 같은 키의 등록 트랜잭션이 커밋될 때까지 기다립니다.
2. 취소 표식이 있으면 `cancelMarkerAt`에 시각을 돌려줍니다. `registrations[].canceledAt`과 같은 칸(`canceled_at`)입니다. **등록 후 취소된 키도 채웁니다.** 워커는 이것 하나로 "취소된 키"를 압니다.
3. 이 키로 만들어진 등록을 배열로 돌려줍니다.
4. 재생 대상으로 저장된 결과를 `storedOutcome`으로 알립니다. `SUCCESS` 또는 `null`입니다. **등록 후 취소된 키는 `null`**입니다. 다시 등록하면 201 재생이 아니라 409 `KEY_CANCELED`이기 때문입니다.
5. 등록과 취소 표식이 모두 없으면 404입니다.

| 키의 상태 | `cancelMarkerAt` | `registrations` | `storedOutcome` |
| --- | --- | --- | --- |
| 등록됨 | `null` | 1개 (`ACTIVE`) | `SUCCESS` |
| 등록 후 취소 | 취소 시각 | 1개 (`CANCELED`) | `null` |
| 취소 표식만 | 취소 시각 | 0개 | `null` |
| 기록 없음 | 404 | | |

> **1번이 잠금 읽기인 이유.** 일반 `SELECT`는 커밋 중인 등록을 보지도 기다리지도 않아, 404를 받은 워커가 재등록하면 **중복 등록**이 됩니다. 반대로 지연 구간(잠금 전)의 등록은 이 조회도 보지 못해 404로 보입니다. 지연 3,000ms 등록을 보내고 0.3초 뒤에 조회하면 404이고, 4초 뒤에는 ACTIVE입니다.

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
| 404 | `NOT_FOUND` | 키에 대한 기록이 없습니다. 등록 · 취소 표식 어느 것도 없음 |

### 본 서비스의 판단

| 응답 | 워커가 하는 일 |
| --- | --- |
| 404 | 지금은 등록이 없습니다 → **같은 키로** 다시 등록합니다. 지연 중인 등록이 있어도 같은 키라 한 건만 남습니다(먼저 커밋된 것을 재생) |
| `registrations`에 ACTIVE 있음 | 이미 됐습니다 → 그 번호로 확정 반영합니다. **재등록 금지** |
| `cancelMarkerAt` 있음 | 취소된 키입니다 → 확정하지 않고 정리합니다 |

**404를 보고 포기하지 않습니다.** 재시도를 다 써도 등록 작업은 DEAD_LETTER로 남기고 **Mock 취소를 보내지 않습니다.** 관리자가 같은 키로 재처리해 푸는데, 취소 표식이 있으면 재처리가 409 `KEY_CANCELED`로 막히기 때문입니다. 그사이 늦게 커밋된 등록이 있어도 재처리가 같은 키로 그 등록을 재생해 같은 번호로 맞춰집니다.

**같은 키로 `POST /external/cancellations`를 보내는 것은 예약을 취소로 끝낼 때뿐입니다**(사용자 · 관리자 취소, 결제 기한 만료). 취소는 항상 200이고, 표식 뒤에 늦게 도착한 등록은 409 `KEY_CANCELED`로 거절되어 외부에만 남는 등록(유령 등록)이 생기지 않습니다.

이 API가 없으면 응답이 유실됐을 때 **중복 등록 아니면 유실** 둘 가운데 하나가 됩니다.

---

## 2-5. 예약 취소: POST /external/cancellations

### 요구사항

- 외부 키 또는 외부 예약번호로 활성 등록을 비활성화합니다(기능명세).
- 업무상 거절이 없습니다(과제: "잘못 등록된 예약을 되돌리는 용도, 항상 성공 가정"). **키로 취소하면 미등록 · 이미 취소된 대상도 200입니다.**
- 키 취소는 등록이 없어도 취소 표식을 영속 저장합니다.
- 표식이 있는 키에 늦게 도착한 등록은 `KEY_CANCELED`로 거절됩니다.
- 반복 호출의 업무 효과는 한 번입니다.
- 지연 · 실패 설정을 적용하지 않습니다.

### Request

**Headers**

```text
Content-Type: application/json
Accept: application/json
```

**Body**: `externalKey`와 `reservationNo` 가운데 하나 이상이 필수입니다. 본 서비스의 `CancelRequestPayload` 그대로입니다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `externalKey` | string | 조건부 | 우리 `preorder_token`. 영문 · 숫자 · `. _ -` 1 ~ 100자 |
| `reservationNo` | string | 조건부 | Mock이 발급한 예약번호(등록 응답의 `externalNumber`). 영문 · 숫자 · `. _ -` 1 ~ 100자. 등록이 확인되지 않았으면 `null` |
| `reason` | string | 선택 | `USER_CANCEL` · `ADMIN_CANCEL` · `DEADLINE_EXCEEDED` · `GHOST_COMPENSATION`(정합성 검사의 키로 취소) 등. **저장하지 않습니다** (ERD에 칸 없음) |

```json
{
  "externalKey": "9f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
  "reservationNo": null,
  "reason": "GHOST_COMPENSATION"
}
```

### 시스템 처리

1. 같은 키 행을 잠그고 등록과 취소를 직렬화합니다.
2. 활성 등록이 있으면 `CANCELED`로 바꾸고 취소 시각을 남깁니다.
3. 이미 취소된 등록이면 아무것도 바꾸지 않습니다.
4. **등록 기록이 없어도 취소 표식 행을 만듭니다.** 같은 새 키를 등록 요청이 먼저 INSERT했으면 1062를 받으므로, 다시 잠금 읽기로 조회해 2단계부터 합니다.
5. 지연 구간에서 기다리던 등록이 뒤늦게 진행돼도 표식 때문에 활성화되지 못합니다.
6. `reservationNo`만 받았으면 그 번호의 등록을 찾아 그 키로 1 ~ 3단계를 합니다. **그 번호의 등록이 없으면 404입니다.** 키가 없어 표식을 남길 수 없고, 번호는 Mock이 등록할 때만 발급하므로 그 번호로 늦게 오는 등록도 없기 때문입니다.
7. 둘 다 받았으면 **같은 등록을 가리켜야 합니다.** 키의 등록 번호가 다르거나 그 번호가 다른 키의 등록이면 400이고 아무것도 바꾸지 않습니다. 키만 보고 취소하면 워커는 그 번호를 취소했다고 믿는데 다른 등록이 꺼지기 때문입니다. 키에 번호가 없고 그 번호의 등록도 없으면 키로 처리합니다.
8. 이번 호출로 실제 비활성화한 등록이 있었는지를 `hadActiveRegistration`으로 알립니다.
9. 취소된 행을 다시 활성화하지 않습니다.

### Response

**200 OK**: 활성 등록을 비활성화함

```json
{
  "externalKey": "9f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
  "externalNumbers": ["R-20260916-0048213579"],
  "hadActiveRegistration": true,
  "cancelMarkerAt": "2026-09-16T10:10:00.000Z",
  "canceledAt": "2026-09-16T10:10:00.000Z"
}
```

**200 OK**: 미등록 키, 표식만 남김

```json
{
  "externalKey": "1d6e82a4-1260-06c4-8e9f-0a1b2c3d4e5f",
  "externalNumbers": [],
  "hadActiveRegistration": false,
  "cancelMarkerAt": "2026-09-16T10:10:00.000Z",
  "canceledAt": "2026-09-16T10:10:00.000Z"
}
```

`cancelMarkerAt`과 `canceledAt`은 ERD의 같은 칸 `canceled_at`이라 항상 같은 값입니다. `externalNumbers`는 0 ~ 1개입니다(키가 PK).

**주요 오류**

| 응답 | errorCode | 설명 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | `externalKey`와 `reservationNo`가 둘 다 없음 |
| 400 | `INVALID_REQUEST` | 키 · 번호가 비었거나 100자를 넘음 |
| 400 | `INVALID_REQUEST` | 키와 번호가 서로 다른 등록을 가리킴 |
| 404 | `NOT_FOUND` | 번호만 받았는데 그 번호의 등록이 없음 |

### 확인 시나리오

세 순서 모두 최종 활성 등록이 0건이어야 합니다.

| 시나리오 | 기대 결과 |
| --- | --- |
| 등록 성공 → 취소 | `hadActiveRegistration: true` · `status: CANCELED` |
| **지연 중 등록 → 취소 → 지연 종료** | **취소가 즉시 200. 뒤늦게 진행된 등록이 `KEY_CANCELED`** |
| 취소(미등록 키) → 지연 등록 도착 | 409 `KEY_CANCELED`. 등록 생성 안 됨 |
| 같은 대상 취소 2회 | 둘 다 200. 2회차 `hadActiveRegistration: false`. 취소 시각 그대로 |
| 같은 새 키로 등록 · 취소 동시 | 취소는 모두 200. 최종 활성 0. 끈 등록은 등록이 있었을 때만 1건 |

두 번째 시나리오는 `registerLatencyMs`를 크게 잡아 만듭니다. 경합 시나리오는 최소 100회 반복합니다.

---

# 3. 럭키드로우

본 서비스가 응모비 결제를 마친 응모를 한 건씩 받아 쌓고, 마감 뒤 요청을 받으면 그 가운데 당첨 인원만큼 무작위로 뽑습니다. 부르는 쪽은 본 서비스 worker(`draw-register` 큐)이고, 이 장은 본 서비스 계약 `contracts/draw-entry-sync.md`의 「Mock 담당과 정할 것」에 대한 답입니다.

```text
회원 응모비 결제 (order)
  └ DRAW_ENTRY_PAID ─▶ SQS ─▶ worker ─▶ POST /external/draw-entries      응모 한 건씩 · 몇 건일지 모름 · 같은 응모가 두 번 올 수 있음
마감 뒤 (order)
  └ DRAW_REQUESTED(paidEntries, winnerCount) ─▶ worker
        GET  /external/draws/{drawId}          받은 응모 수 확인: paidEntries 와 다르면 기다렸다 다시
        POST /external/draws/{drawId}/draw     winnerCount 명 추첨 → winnerEntryIds
  └ DRAW_RESULT_READY ◀─ worker                order 가 당첨자 0원 주문
```

| 항목 | 규칙 |
| --- | --- |
| 회차 | `drawId`는 본 서비스에서 관리자가 만든 **드로우 하나의 id**이고, 새 드로우는 늘 새 `drawId`입니다. **Mock은 회차를 미리 모릅니다.** 처음 보는 `drawId`로 응모가 오면 그 회차를 만듭니다. 회차 등록 API를 따로 두면 순서 보장이 없어 응모가 먼저 닿을 수 있기 때문입니다 |
| 마감 | Mock은 마감 시각을 모릅니다. **추첨 전까지 오는 응모는 모두 받습니다**(본 서비스 계약 「마감 뒤에도 온다」) |
| 식별자 | `entryId` · `drawId`는 등록 키와 같은 규칙입니다(영문 · 숫자 · `. _ -` 1 ~ 100자). 본 서비스는 UUID를 보냅니다. `customerId`는 소문자 표준 UUID만 받습니다 |
| 줄 세우기 | 응모와 추첨은 **회차 행을 잠가** 한 줄로 섭니다. 같은 추첨 요청은 최소 한 번 전달과 worker 재전송 때문에 정상 흐름에서도 겹쳐 오는데, 그래야 한 번만 뽑고 나머지에는 처음 결과를 줍니다. 추첨 중에 응모가 하나 더 와도 센 수와 뽑은 대상이 어긋나지 않습니다(추첨 전에 오면 대상에 들고, 뒤에 오면 409 `DRAW_CLOSED`) |
| 지연 · 실패 | 응모와 추첨에 등록과 같은 설정을 씁니다(1-5). 실패는 커밋 전이라 저장 · 추첨하지 않습니다. 회차 조회에는 넣지 않습니다. 키별 결함(응답 유실 · 느린 성공)은 걸지 않습니다 |
| 결과 불명 | 5xx나 타임아웃이면 **조회 없이 같은 요청을 다시 보냅니다.** 응모(같은 `entryId`)와 추첨(같은 회차 · 같은 본문)은 재생되므로 두 번 쌓이거나 두 번 뽑히지 않습니다. 응모는 추첨 뒤에 다시 보내도 재생입니다. 등록의 키 조회 같은 API는 두지 않습니다 |
| 저장 | `external_mock`의 `draws` · `draw_entries`([schema.sql](schema.sql), 운영은 `db/migrations`) |
| 초기화 | `POST /external/reset`이 두 표도 비웁니다 |

## 3-1. 응모: POST /external/draw-entries

### 요구사항

- 응모를 한 건씩 받아 쌓습니다. 몇 건일지는 모릅니다.
- 같은 응모가 다시 오면 새로 쌓지 않고 처음 결과를 줍니다(SQS 최소 한 번 전달 · worker 재시도).
- 같은 회차에 같은 회원은 하나입니다. 본 서비스가 보장하지만, 어기면 거절합니다.
- 추첨한 회차에는 새 응모를 받지 않습니다.

### Request

**Headers**

```text
Idempotency-Key: 0199a3f2-8a10-7b21-9c32-4d5e6f708192
Content-Type: application/json
Accept: application/json
```

**Body**: 본 서비스 봉투의 값을 그대로 옮깁니다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `entryId` | string | 필수 | 응모 id(봉투 `aggregateId`). **`Idempotency-Key`와 같아야 합니다**(다르면 400) |
| `drawId` | string | 필수 | 회차 id(`payload.drawId`) |
| `customerId` | string | 필수 | 회원 id(`payload.customerId`). 소문자 표준 UUID |

```json
{
  "entryId": "0199a3f2-8a10-7b21-9c32-4d5e6f708192",
  "drawId": "0199a3f1-0000-7000-8000-000000000001",
  "customerId": "0199a3f0-0000-7000-8000-000000000002"
}
```

### 시스템 처리

1. 지연 → 실패 판정(걸리면 500, 저장 없음). 트랜잭션 밖입니다.
2. 회차 행을 잠급니다. **없으면 만듭니다**(`OPEN`). 처음 보는 회차로 두 응모가 동시에 오면, 진 쪽은 1062를 받고 다시 잠금 읽기로 이어 갑니다.
3. 같은 `entryId`가 있으면 내용(`drawId` · `customerId`)을 비교합니다. 같으면 **재생**(추첨 뒤여도)하고, 다르면 422 `KEY_PAYLOAD_MISMATCH`입니다. 다른 회차로 왔으면 2단계에서 만든 회차도 롤백되어 남지 않습니다.
4. 회차가 이미 추첨됐으면 409 `DRAW_CLOSED`입니다.
5. 같은 회차에 같은 회원의 다른 응모가 있으면 409 `CUSTOMER_ALREADY_ENTERED`입니다.
6. 새 응모를 저장합니다.

**3이 4보다 먼저인 이유.** 추첨 뒤에 온 재전송은 이미 받은 응모를 다시 보낸 것이지 새 응모가 아닙니다. 409로 거절하면 worker는 들어간 응모를 안 들어간 것으로 믿습니다.

### Response

**201 Created**: 재생도 201이며, 헤더로 가릅니다.

```text
X-Idempotent-Replay: false
X-Mock-Config-Version: 3
```

```json
{
  "entryId": "0199a3f2-8a10-7b21-9c32-4d5e6f708192",
  "drawId": "0199a3f1-0000-7000-8000-000000000001",
  "customerId": "0199a3f0-0000-7000-8000-000000000002",
  "receivedAt": "2026-10-14T03:00:00.123Z"
}
```

재생은 처음 응답과 같은 본문에 `X-Idempotent-Replay: true`이고, 응모 수는 늘지 않습니다. 당첨 여부는 싣지 않습니다(회차 조회 · 추첨 응답에 있습니다).

**주요 오류**

| 응답 | errorCode | 설명 | worker |
| --- | --- | --- | --- |
| 400 | `INVALID_REQUEST` | 형식 · 필드 · 헤더 오류, 본문 `entryId` ≠ `Idempotency-Key` | DLQ (다시 보내도 같음) |
| 409 | `DRAW_CLOSED` | 추첨한 회차에 새 응모. `replayable: true` | DLQ. order의 「드로우 추첨 뒤 결제 완료」 ERROR와 함께 사람이 정리 |
| 409 | `CUSTOMER_ALREADY_ENTERED` | 같은 회차에 같은 회원의 다른 응모. `replayable: true` | DLQ (본 서비스 버그) |
| 422 | `KEY_PAYLOAD_MISMATCH` | 같은 응모 id로 다른 회차 · 회원. 메시지에 다른 칸 이름 | DLQ (본 서비스 버그) |
| 500 | `UPSTREAM_UNAVAILABLE` | 주입 실패. 저장 없음 | 재시도 |
| 500 본문 없음 · HTML 502 ~ 504 | 없음 | `failureMode=MIXED`일 때 | 재시도 |

### 확인 시나리오

| 시나리오 | 기대 결과 |
| --- | --- |
| 처음 보는 회차로 응모 | 201, 회차 `OPEN` 생성 |
| 같은 응모 2회 | 2회차 201 재생, 본문 같음, 응모 수 그대로 |
| 같은 회차에 같은 회원 · 다른 응모 | 409 `CUSTOMER_ALREADY_ENTERED`, 저장 없음 |
| 추첨 뒤 새 응모 / 이미 받은 응모 재전송 | 409 `DRAW_CLOSED` / 201 재생 |
| 처음 보는 회차에 다른 회원 10명 동시 | 회차 1개, 응모 10건 모두 새로 (MySQL 100회) |
| 같은 응모 10건 동시 | 새 응모 1건 + 재생 9건 (MySQL 100회) |
| 같은 회원의 다른 응모 10건 동시 | 1건만 받고 9건 409 (MySQL 100회) |

## 3-2. 회차 조회: GET /external/draws/{drawId}

### 요구사항

- worker가 추첨 전에 **Mock이 받은 응모 수**를 결제 완료 수(`paidEntries`)와 대조할 때 부릅니다.
- 추첨 결과도 줍니다(추첨 응답과 같은 모양).
- 지연 · 실패를 넣지 않습니다.

### Request

**Headers**

```text
Accept: application/json
```

| 경로 변수 | 설명 |
| --- | --- |
| `drawId` | 회차 id. 영문 · 숫자 · `. _ -` 1 ~ 100자. 아니면 400입니다(404가 아닙니다. "아직 응모가 없다"와 섞이지 않게 하기 위해서입니다) |

### 시스템 처리

1. 회차, 받은 응모 수, 당첨 응모를 **REPEATABLE READ** 한 트랜잭션으로 읽습니다. 문장마다 그때의 커밋을 보면, 그사이에 추첨이 커밋될 때 "`OPEN`인데 당첨이 있는" 응답이 나가기 때문입니다. 잠그지 않으므로 응모와 추첨을 막지 않습니다.
2. 진행 중(커밋 전)인 응모는 세지 않습니다. worker는 수가 다르면 기다렸다가 다시 대조합니다(본 서비스 계약).

### Response

**200 OK**: 응모 받는 중

```json
{
  "drawId": "0199a3f1-0000-7000-8000-000000000001",
  "status": "OPEN",
  "entryCount": 1523,
  "winnerCount": null,
  "winnerEntryIds": [],
  "drawnAt": null
}
```

| 필드 | 설명 |
| --- | --- |
| `status` | `OPEN`(응모 받는 중) / `DRAWN`(추첨 끝, 이후 새 응모는 409) |
| `entryCount` | 이 회차로 받은 응모 수. 추첨 뒤에는 늘지 않습니다 |
| `winnerCount` · `drawnAt` | `DRAWN`일 때만 값이 있고, 그 전에는 생략하지 않고 `null`입니다 |
| `winnerEntryIds` | 당첨 응모 id, 뽑힌 순서. `OPEN`이면 **빈 배열**입니다(`null`이 아닙니다) |

**주요 오류**

| 응답 | errorCode | 설명 |
| --- | --- | --- |
| 404 | `NOT_FOUND` | **이 회차로 받은 응모가 아직 없습니다.** 응모 수 0으로 읽으면 됩니다 |
| 400 | `INVALID_REQUEST` | `drawId` 형식 오류 |

## 3-3. 추첨: POST /external/draws/{drawId}/draw

### 요구사항

- 받은 응모 가운데 `winnerCount`명을 **겹치지 않게, 모두 같은 확률로** 뽑습니다. 1명도 되고 여러 명도 됩니다.
- 회차마다 한 번입니다. 같은 요청이 다시 오면 다시 뽑지 않고 처음 결과를 줍니다(회차 id가 멱등 키이며, `Idempotency-Key` 헤더를 받지 않습니다).

### Request

**Headers**

```text
Content-Type: application/json
Accept: application/json
```

**Body**

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `winnerCount` | integer | 필수 | 당첨 인원(`DRAW_REQUESTED.payload.winnerCount`). 1 이상이고, 받은 응모 수보다 작아야 합니다 |
| `expectedEntryCount` | integer | 선택 | 기대 응모 수(`payload.paidEntries`). 1 이상. **없으면 수를 확인하지 않습니다** |

```json
{
  "winnerCount": 10,
  "expectedEntryCount": 1523
}
```

**`expectedEntryCount`를 두는 이유.** 본 서비스 계약은 "조회로 수를 대조한 뒤 추첨"이라, 두 호출 사이에 응모가 하나 더 닿으면 대조는 맞았는데 추첨 대상이 달라집니다. 추첨 요청에 기대 수를 실으면 Mock이 회차를 잠근 채 세고 뽑으므로 그 틈이 없습니다.

**worker는 이 칸을 보내고**, 409 `ENTRY_COUNT_MISMATCH`를 다른 4xx와 달리 **"늦춰 다시"**로 읽습니다. 보내지 않으면 Mock은 수를 확인하지 않고 원래 흐름(조회 → 추첨)대로 동작합니다.

### 시스템 처리

1. 지연 → 실패 판정(걸리면 500, 추첨하지 않음). 트랜잭션 밖입니다.
2. 회차 행을 잠급니다. **없으면** 기대 수를 보냈을 때 409 `ENTRY_COUNT_MISMATCH`(받은 수 0), 보내지 않았을 때 404 `NOT_FOUND`입니다. 회차를 만들지 않습니다. 회차는 응모가 만듭니다.
3. 이미 추첨했으면 요청(`winnerCount` · `expectedEntryCount`)을 저장된 값과 비교합니다. 같으면 **저장된 결과를 재생**(200, `X-Idempotent-Replay: true`)하고, 다르면 422 `KEY_PAYLOAD_MISMATCH`입니다.
4. `expectedEntryCount`가 있고 받은 응모 수와 다르면 409 `ENTRY_COUNT_MISMATCH`입니다. 아무것도 바꾸지 않고, 메시지에 받은 수를 담습니다.
5. `winnerCount` ≥ 받은 응모 수면 422 `INVALID_WINNER_COUNT`입니다(본 서비스는 전원 당첨이면 부르지 않습니다).
6. 응모 가운데 `winnerCount`개를 뽑습니다. `SecureRandom`으로 부분 Fisher–Yates 셔플을 합니다(남은 후보 가운데 하나를 고르게 골라 앞으로 빼기를 당첨 인원만큼). 당첨 응모에 뽑힌 순서(1부터)를 적고, 회차를 `DRAWN`으로 바꿉니다.

### Response

**200 OK**: 재생도 200이며, 헤더로 가릅니다(`X-Idempotent-Replay` · `X-Mock-Config-Version`).

```json
{
  "drawId": "0199a3f1-0000-7000-8000-000000000001",
  "status": "DRAWN",
  "entryCount": 1523,
  "winnerCount": 10,
  "winnerEntryIds": ["0199a3f2-…", "0199a3f2-…"],
  "drawnAt": "2026-10-14T04:00:00.000Z"
}
```

회차 조회와 같은 모양입니다. `winnerEntryIds`는 정확히 `winnerCount`개이고, 겹치지 않으며, 그 회차가 받은 응모 가운데서 나옵니다(본 서비스 계약 `DRAW_RESULT_READY` 조건). 순서는 뽑힌 순서이며 뜻은 없습니다. worker는 이 목록을 그대로 order에 넘깁니다.

**주요 오류**

| 응답 | errorCode | 설명 | worker |
| --- | --- | --- | --- |
| 409 | `ENTRY_COUNT_MISMATCH` | 받은 수 ≠ 기대 수(처음 보는 회차는 받은 수 0). 아무것도 바꾸지 않음 | **늦춰 다시**(가시성 지연). 본 서비스 계약의 "다르면 기다렸다 다시 대조" |
| 404 | `NOT_FOUND` | 기대 수 없이, 받은 응모가 없는 회차 | 늦춰 다시 (응모가 아직 안 닿음) |
| 422 | `KEY_PAYLOAD_MISMATCH` | 추첨한 회차에 다른 당첨 인원 · 기대 수. 메시지에 다른 칸 이름 | DLQ (본 서비스 버그) |
| 422 | `INVALID_WINNER_COUNT` | 당첨 인원 ≥ 받은 응모 수 | DLQ (본 서비스 버그) |
| 400 | `INVALID_REQUEST` | 형식 오류, `winnerCount` 없음 · 1 미만, `expectedEntryCount` 1 미만, 모르는 필드 | DLQ |
| 500 | `UPSTREAM_UNAVAILABLE` | 주입 실패. 추첨하지 않음 | 재시도 |

### 확인 시나리오

| 시나리오 | 기대 결과 |
| --- | --- |
| 응모 5건 · 2명 추첨 | 200 `DRAWN`, 겹치지 않는 2명, 회차 조회와 같은 응답 |
| 같은 추첨 2회 | 2회차 200 재생, 같은 결과 |
| 기대 수 4 · 받은 수 3 → 응모 1건 더 → 다시 | 409 `ENTRY_COUNT_MISMATCH`("받은 수=3") → 200 |
| 같은 추첨 10건 동시 | 추첨 1번 + 재생 9번, 10건 모두 같은 당첨 (MySQL 100회) |
| 추첨 1건과 새 응모 5건 동시 | 추첨이 센 수 = 실제로 들어간 응모 수, 늦은 응모는 409 (MySQL 100회) |
| 5명 가운데 1명 × 5만 번 | 각자 1만 ± 500번, 모두 같은 확률 |

---

# 4. 설정

## 4-1. 설정 조회: GET /external/config

### 요구사항

- 현재 적용 중인 지연 · 실패 설정을 조회합니다.
- 설정을 바꾼 뒤 현재 적용값을 다시 확인할 수 있어야 합니다(요구사항 4.4).

### Request

**Headers**

```text
Accept: application/json
```

**Body**: 없음

### 시스템 처리

1. 현재 설정을 그대로 돌려줍니다.
2. 설정 버전과 마지막 적용 시각을 함께 돌려줍니다.

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
  "mixedResponses": [],
  "configVersion": 4,
  "appliedAt": "2026-09-16T09:40:00.000Z",
  "timeoutHoldMs": 7000,
  "workerReadTimeoutMs": 3000
}
```

| 필드 | 설명 |
| --- | --- |
| `registerLatencyMs` | 지연의 **평균**. 꼬리를 걸어도 전체 평균은 이 값입니다 |
| `latencyJitter` | 지연을 흔드는 폭. 실제 대기는 `평균 × (1 ∓ 이 값)` 균등분포입니다. **조회만 되고 변경은 안 됩니다.** 설정 파일로 정합니다 |
| `latencyTailRate` · `latencyTailMinMs` · `latencyTailMaxMs` | 지연의 느린 꼬리. 요청마다 이 비율로 [최소, 최대] 균등에서 뽑습니다. 비율 0이면 꼬리 없음(구간도 0) |
| `bodyLatencyMs` | 꼬리에 걸리지 않은 요청의 평균. 전체 평균이 `registerLatencyMs`가 되도록 꼬리만큼 낮춘 값(반올림)이고, 지터는 여기에만 걸립니다. **조회만 됩니다** |
| `mixedResponses` | `failureMode=MIXED`에서 섞는 실패 응답 종류. MIXED가 아니면 빈 목록, MIXED인데 고르지 않았으면 다섯 전부 |
| `configVersion` · `appliedAt` | 설정을 바꿀 때마다 오르는 버전과 그 시각 |
| `timeoutHoldMs` · `workerReadTimeoutMs` | 유지 시간과 워커 읽기 타임아웃(4-2). **조회만 되고 변경은 안 됩니다.** 설정 파일로 정합니다. 부하 보고서가 어떤 조건의 Mock이었는지 남기는 데 씁니다 |

설정은 DB가 아니라 설정 파일 기본값 + 메모리라, 한 대(`memory`)면 재기동할 때 기본값으로 돌아갑니다. 등록 기록은 DB라 남습니다.

**여러 대(`mock.shared-state=redis`)면** 설정과 결함을 Redis에서 나누며, 계약에서 달라지는 점은 아래 셋입니다(원리는 [README](../README.md) 「설계와 기술 특이점」 4, 띄우는 법은 [operations.md](operations.md) 2-6).

- **재기동해도 기본값으로 돌아가지 않고 공유된 설정을 따릅니다.**
- 응답 헤더 `X-Mock-Config-Version`은 모든 서버에서 같은 설정이면 같은 버전입니다. 서버마다 다르면 아직 따라잡는 중입니다.
- 설정을 바꾼 뒤 부하를 걸 때는 1 ~ 2초 기다리고 시작합니다.

---

## 4-2. 설정 변경: PUT /external/config

### 요구사항

- 지연과 실패율을 재기동 없이 바꿉니다. 시연에서 쓰는 조작 패널입니다(과제 명세).
- 각 등록 시도는 시작 시점의 설정 버전을 고정합니다.
- 유효 범위를 벗어난 값은 거절합니다. **범위 검사는 API에서 합니다**(설정 테이블이 없으므로 DB 제약이 없습니다).
- 적용 실패를 저장 성공으로 표시하지 않습니다. 응답은 실제 적용값입니다(요구사항 4.4).

### Request

**Headers**

```text
Content-Type: application/json
Accept: application/json
```

**Body**

| 필드 | 타입 | 필수 | 제약 |
| --- | --- | --- | --- |
| `registerLatencyMs` | integer | 필수 | 지연의 **평균**. `default=500` · `min=0` · `max=60000` |
| `failureRate` | number | 필수 | `default=0.05` · `min=0` · `max=1` |
| `failureMode` | string | 선택 | `HTTP_5XX`(기본) / `TIMEOUT` / `MIXED` |
| `mixedResponses` | string[] | 선택 · **MIXED일 때만** | 섞을 종류. `HTTP_500` · `HTTP_500_NO_BODY` · `HTTP_502` · `HTTP_503` · `HTTP_504` 가운데서 고릅니다. 생략하면 다섯 전부 |
| `latencyTailRate` | number | 선택(꼬리 묶음) | 꼬리에 걸릴 확률. `min=0` · **1 미만** |
| `latencyTailMinMs` | integer | 선택(꼬리 묶음) | 꼬리 구간 하한. `min=0` · `max=60000` · 최대 이하 |
| `latencyTailMaxMs` | integer | 선택(꼬리 묶음) | 꼬리 구간 상한(포함). `min=0` · `max=60000` |

`latencyJitter` · `timeoutHoldMs`는 받지 않습니다(설정 파일로만, 보내면 400). 분포의 모양을 실행 중에 바꾸려면 아래 꼬리를 씁니다.

### 지연 꼬리 (선택)

대부분 빠르고 드물게 수 초 걸리는 외부 시스템, 특히 **워커 타임아웃 뒤에 늦게 커밋되는 등록**을 만듭니다. 지연 대기는 잠금 밖이라 기다리는 동안 키 조회는 404이고, 워커가 포기한 뒤에 커밋됩니다. 응답 유실 결함은 커밋한 뒤 끊어서 키 조회가 처음부터 ACTIVE이므로 이 경우가 아닙니다.

- 요청마다 `latencyTailRate` 확률로 [`latencyTailMinMs`, `latencyTailMaxMs`] 균등에서 뽑고, 나머지는 몸통에서 뽑습니다.
- **전체 평균은 `registerLatencyMs` 그대로입니다.** 몸통 평균을 `(평균 − 비율 × 꼬리 평균) ÷ (1 − 비율)`로 낮춥니다. 예를 들어 500 · 2%가 2 ~ 4초면 몸통 평균은 449ms(`bodyLatencyMs`)입니다. 지터는 몸통에만 걸립니다.
- 세 칸은 **함께 보내거나 함께 뺍니다.** 다 빼면 꼬리 없음이고(통째 교체라 앞 설정의 꼬리를 남기지 않습니다), 일부만 보내면 400입니다. 비율만 보내고 구간을 빠뜨린 실수가 조용히 "꼬리 없음"이 되지 않게 하기 위해서입니다.
- 비율이 0이면 꼬리 없음이고 아래 평균 검사를 건너뜁니다(지연 0 · 꼬리 0도 받습니다). 비율이 0보다 크면 `비율 × 꼬리 평균 < registerLatencyMs`여야 합니다. 그렇지 않으면 꼬리만으로 평균을 넘겨 맞출 수 없습니다.
- 실제로 뽑힌 대기는 등록 응답의 `X-Mock-Injected-Latency-Ms`로 나갑니다. 기동할 때는 늘 꼬리 없음입니다.

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

### 실패 모드 3종

실패할지는 `failureRate`(확률)가 정하고, 걸린 뒤의 모양은 `failureMode`가 정합니다. 셋 다 커밋 전이라 저장되지 않습니다.

| 모드 | 실패에 걸리면 | 워커가 보는 것 |
| --- | --- | --- |
| `HTTP_5XX` (기본) | 즉시 500 + `UPSTREAM_UNAVAILABLE` | 일시 실패 |
| `TIMEOUT` | 응답하지 않은 채 연결을 **유지 시간** 동안 붙잡았다가 본문 없는 500으로 끝냅니다. 유지 시간이 워커 읽기 타임아웃보다 길어 워커에게는 타임아웃으로 보입니다 | 결과 불명 |
| `MIXED` | `mixedResponses` 가운데 하나를 같은 확률로 보냅니다(아래 표) | 종류에 따라 다름 |

**MIXED**는 워커의 5xx 처리와 로그를 종류별로 볼 때 씁니다. 실제 연동에서는 500 말고도 앞단 장비의 502 · 503 · 504가 오는데, 워커가 갈라야 하는 것은 상태 번호가 아니라 **`errorCode` 유무**입니다(1-3 오류 분류 계약).

| `mixedResponses` 값 | 응답 | 워커가 보는 것 |
| --- | --- | --- |
| `HTTP_500` | 500 + JSON `errorCode: UPSTREAM_UNAVAILABLE` (`HTTP_5XX`와 같음) | 일시 실패 |
| `HTTP_500_NO_BODY` | 본문 없는 500 | 결과 불명 |
| `HTTP_502` · `HTTP_503` · `HTTP_504` | 그 상태 + HTML 본문, `errorCode` 없음. `Accept`와 상관없이 HTML | 결과 불명 |

- 생략하면 다섯 전부, 하나만 주면 그 응답만 나옵니다. 그래서 시연에서 "지금 503을 보여 준다"가 됩니다.
- `TIMEOUT`은 섞지 않습니다. 연결을 붙잡아 무겁고, 따로 고를 수 있기 때문입니다.
- 429는 아직 없습니다. "5xx인데 저장된" 경우도 만들지 않습니다. 커밋 전에 보내므로 키 조회는 늘 404입니다.
- 주입한 실패에는 표식 헤더 `X-Mock-Injected-Failure`가 붙습니다(2-1 Response). 워커는 이 헤더로 분기하지 않습니다.

```json
{
  "registerLatencyMs": 500,
  "failureRate": 0.05,
  "failureMode": "MIXED",
  "mixedResponses": ["HTTP_502", "HTTP_503", "HTTP_504"]
}
```

### 유지 시간과 워커 타임아웃

유지 시간은 Mock 설정 `mock.timeout-hold-ms`(**기본 7000ms**)이고, 본 서비스 워커의 HTTP 읽기 타임아웃(`nova.worker.mock.read-timeout`, **기본 · 운영 3초**)보다 2초 이상 길어야 워커가 타임아웃을 겪습니다. 워커 타임아웃은 Mock 설정 `mock.worker-read-timeout-ms`(기본 3000ms)로 옮겨 적습니다.

| 유지 시간 | 결과 |
| --- | --- |
| `0 < 유지 < 워커 + 2000` | **Mock이 뜨지 않습니다.** 짧으면 워커가 타임아웃 대신 본문 없는 500을 받기 때문입니다(둘이 같으면 동시 200건 가운데 32%가 500) |
| `0` | 시험 전용입니다. 붙잡지 않고 바로 본문 없는 500으로 끝내므로 운영 · 시연에서 쓰지 않습니다 |
| 워커 타임아웃 + 2초 이상 | 워커가 타임아웃을 겪고, Mock도 곧 연결을 정리합니다 |
| 너무 김 | 부하 중 연결이 쌓입니다. 1,000 RPS · 실패율 1.0 · 유지 10초면 약 1만 개로 톰캣 기본 최대 연결(8,192)을 넘습니다 |

### 시스템 처리

1. 값의 범위, 꼬리 묶음 규칙(위 "지연 꼬리"), 섞기 규칙(위 "MIXED")을 검사하고 어긋나면 400입니다. 설정을 건드리지 않습니다.
2. 설정을 바꾸고 `configVersion`을 1 올립니다.
3. `appliedAt`을 기록합니다.
4. 진행 중인 등록 시도는 시작 시점 버전을 유지하고, 변경은 그 뒤의 시도부터 적용합니다.
5. 반영된 설정을 그대로 돌려줍니다.

### Response

**200 OK**: `GET /external/config`와 같은 형식입니다.

**주요 오류**

| 응답 | errorCode | 설명 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | 범위 밖 값 · 꼬리 칸 일부만 · 꼬리 최소 > 최대 · 꼬리만으로 평균을 넘음 |
| 400 | `INVALID_REQUEST` | `mixedResponses`: MIXED가 아닌데 보냄(조용히 버리면 섞이고 있다고 믿게 됨) · 빈 목록 · 같은 값 두 번 · 모르는 값(`mixedResponses[1] 은(는) … 중 하나여야 합니다`) |

### 확인 시나리오

| 시나리오 | 기대 결과 |
| --- | --- |
| 등록 진행 중 설정 변경 | 진행 중 시도는 원래 버전 유지 |
| 음수 지연 · 0 ~ 1 밖 실패율 | 400 |
| 꼬리 칸 세 개를 다 줌 | 적용, `bodyLatencyMs`가 보정값 (2% · 2 ~ 4초면 449) |
| 꼬리 칸을 다 뺌 | 꼬리 없음, 앞 설정의 꼬리를 남기지 않음 |
| 꼬리 칸 일부만 · 비율 1 이상 · 최소 > 최대 · 비율 × 꼬리 평균 ≥ 평균 | 400, 설정 그대로 |
| 지연 0 · 꼬리 0 | 적용 (비율 0이면 몸통 평균 검사를 건너뜀) |
| `failureMode=MIXED` · 목록 생략 · 실패율 1.0 | 다섯 종류가 섞여 나옴. 설정 응답 `mixedResponses`에 다섯 |
| `mixedResponses: ["HTTP_503"]` | 실패는 전부 503 + HTML |
| `HTTP_5XX`에 `mixedResponses` · 빈 목록 · 중복 · `HTTP_429` | 400, 설정 그대로 |

**재기동 시험과 겹칠 때 주의합니다.** 한 대(memory)면 설정이 메모리라 재기동하면 기본값으로 돌아가므로, 시연 순서에 "재기동 후 설정 재입력"을 넣어 두는 것이 좋습니다. 여러 대(redis)면 재기동해도 공유된 설정이 남습니다.

---

# 5. 결함 주입 · 운영

결함 주입과 초기화는 **시험 · 시연용**입니다. Mock 자체가 시험용 서버라 인증이나 프로필로 따로 막지 않습니다. 그래서 공유 환경에 띄우면 주소를 아는 누구나 기록을 지울 수 있습니다.

결함은 DB가 아니라 메모리(`memory` 모드) 또는 Redis(`redis` 모드)에 둡니다. 한 번 발동하면 자동 해제되고 `reset`하면 사라집니다. redis 모드면 어느 서버에 걸든 모든 서버를 통틀어 한 번 발동하고, 재기동해도 남습니다.

## 5-1. 결함 주입: POST /external/faults

### 요구사항

- 키를 지정해 등록 **커밋 후 응답 유실**을 주입합니다.
- 키를 지정해 **느린 성공**(커밋 전에 정한 시간 기다린 뒤 정상 처리)을 주입합니다. 워커 타임아웃보다 길게 걸면 **워커가 포기한 뒤 늦게 커밋되는 등록**을 정확히 한 건 만듭니다.
- 결함은 키 단위로 격리되며, 한 번 발동하면 자동 해제됩니다.
- 지연 · 실패율로는 이 상황을 만들 수 없습니다. 주입한 실패는 모두 커밋 전이라 등록이 저장되지 않기 때문입니다(요구사항 8장 "외부 성공 응답 유실 재현").

### Request

**Headers**

```text
Content-Type: application/json
Accept: application/json
```

**Body**

| 필드 | 타입 | 필수 | 제약 |
| --- | --- | --- | --- |
| `externalKey` | string | 필수 | 결함을 걸 키. 등록 키와 같은 형식(영문 · 숫자 · `. _ -` 1 ~ 100자)입니다. 등록이 받지 않는 키에 걸면 발동할 수 없기 때문입니다 |
| `faultType` | string | 필수 | `RESPONSE_LOST_AFTER_COMMIT` · `SLOW_SUCCESS` |
| `delayMs` | integer | `SLOW_SUCCESS`에 필수 | 커밋 전에 기다릴 시간. `min=1` · `max=60000`. **`RESPONSE_LOST_AFTER_COMMIT`에 주면 400입니다.** 조용히 버리면 보낸 사람이 "기다렸다가 응답을 잃는다"고 믿기 때문입니다 |

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

**응답 유실(`RESPONSE_LOST_AFTER_COMMIT`)**

1. 키와 결함 유형을 검사합니다.
2. 해당 키에 결함을 등록합니다. 같은 키에 이미 결함이 있으면 새 결함으로 바꿉니다.
3. 등록 요청이 그 키로 들어오면 등록 처리 **7단계**에서 발동합니다. 등록을 커밋한 뒤 유지 시간 동안 응답하지 않습니다(결과 불명). 다음 같은 키 요청은 저장된 성공을 재생해야 합니다.
   - **새로 커밋한 등록에만 발동합니다.** 이미 등록된 키에 걸면 재생 요청에는 커밋이 없어 발동하지 않고 그대로 남습니다. 취소된 키(409)도 마찬가지입니다. 남은 결함은 `reset`이 지웁니다.
4. 한 번 발동하면 자동 해제합니다. 발동하지 않은 결함은 `memory` 모드면 재기동할 때 사라지고, `redis` 모드면 Redis에 남아 `reset`이 지웁니다.
   - 결함을 확인하다 Redis에 닿지 못하면 결함 없음으로 넘어갑니다. 등록이 실패하지 않게 하기 위해서입니다(시간 초과 상한은 README 「설계와 기술 특이점」 4).
5. 다른 키에 영향을 주지 않습니다.

**느린 성공(`SLOW_SUCCESS`)**: 위 3 대신 이렇게 발동합니다.

1. 그 키로 등록이 들어오면, 지연 주입과 실패 판정 **뒤, 키 행 잠금 전**(등록 처리 2-1단계)에 `delayMs`만큼 기다린 뒤 정상 처리합니다.
2. 결함은 **기다리기 전에 꺼냅니다.** 꺼내는 순간 한 번만 발동합니다. 기다리는 동안 들어온 같은 키 재시도는 결함이 이미 없어 바로 진행하며, 먼저 커밋할 수 있습니다. 그러면 재시도 응답은 201 · `X-Idempotent-Replay: false`이고, 원래 요청이 재생 쪽이 됩니다.
3. 기다리는 동안은 잠금 전이라 **키 조회가 404**이고, 그사이 남긴 취소 표식은 원래 요청을 409 `KEY_CANCELED`로 막습니다.
4. 기다린 시간은 `X-Mock-Injected-Latency-Ms`에 더해 나갑니다. 기다리는 요청은 초기화 장벽 안에 있어서, 그동안 `reset`은 409 `RESET_BUSY`입니다.
5. **이미 등록됐거나 취소된 키에 걸어도 발동합니다.** 원장을 보기 전에 꺼내므로, 기다린 뒤 평소 결과(재생 201 · 422 · 409)를 받고 결함은 그때 쓰입니다. 실패 판정에 걸린 요청은 결함을 쓰지 않고, 결함은 다음 등록에 남습니다. 늦은 커밋을 보려면 **아직 등록하지 않은 키**에 겁니다.

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

`delayMs`는 `SLOW_SUCCESS`일 때 건 대기 시간이고, 응답 유실이면 `null`입니다.

**주요 오류**

| 응답 | errorCode | 설명 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | 잘못된 `faultType` · 형식이 틀린 `externalKey` · `SLOW_SUCCESS`에 `delayMs` 없음 · 범위 밖 · 응답 유실에 `delayMs` · 헤더 오류 등. 결함을 걸지 않습니다 |

---

## 5-2. 기록 초기화: POST /external/reset

### 요구사항

- 등록 · 멱등 · 취소 기록과 럭키드로우 회차 · 응모를 지웁니다. **설정은 유지합니다.**
- 이전 시험의 잔량이 다음 대조에 섞이지 않게 합니다.
- 실수로 실행하는 것을 막기 위해 확인 문자열을 요구합니다.
- **시험 사이에만 부릅니다.** 진행 중인 등록 · 취소 · 응모 · 추첨이 있으면 지우지 않고 409로 거절합니다.

### Request

**Headers**

```text
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

1. 확인 문자열이 `RESET`인지 검사합니다. 아니면 400입니다.
2. 진행 중인 등록 · 취소 · 응모 · 추첨이 있으면 **아무것도 지우지 않고** 409 `RESET_BUSY`로 거절합니다. 기다리지 않습니다.
3. 등록 행과 취소 표식, 멱등 기록을 지웁니다. 럭키드로우 응모와 회차도 지웁니다. 지우는 동안 들어온 요청은 초기화가 커밋될 때까지 기다립니다.
4. 주입된 결함도 함께 지웁니다.
5. 지연 · 실패 설정과 설정 버전은 그대로 둡니다.
6. 지운 건수를 돌려줍니다.

**진행 중인 요청을 막는 이유.** 지연과 결함의 유지 시간은 트랜잭션 밖이라 초기화를 모릅니다. 막지 않으면 "지연 중인 등록 K → K 취소 200(표식) → 초기화(표식까지 삭제) → 지연이 끝난 등록이 커밋" 순서가 되어, **취소 성공을 받은 K가 ACTIVE로 살아납니다.** `TIMEOUT`이나 결함으로 붙잡힌 요청도 진행 중으로 세므로, 유지 시간(기본 7초) 동안은 거절됩니다.

**여러 대(`mock.shared-state=redis`)일 때.** 원장은 DB, 결함은 Redis라서 어느 서버로 보내도 **모든 서버 몫이 지워집니다.** 다만 2번의 "진행 중인 요청"은 **초기화를 받은 서버의 것만** 셉니다. 다른 서버에서 지연 중인 등록은 막지 못해 위의 되살아남이 생길 수 있으므로, 여러 대에서는 **부하 · 시연이 끝나 요청이 없을 때만 초기화합니다.**

### Response

**200 OK**

```json
{
  "deletedCount": 4821,
  "deletedDrawEntries": 1523,
  "deletedDraws": 1
}
```

| 필드 | 설명 |
| --- | --- |
| `deletedCount` | 지운 **등록 원장** 행 수(등록 + 취소 표식) |
| `deletedDrawEntries` · `deletedDraws` | 지운 럭키드로우 응모 · 회차 수 |

**주요 오류**

| 응답 | errorCode | 설명 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | `confirm` 누락 또는 값 불일치 |
| 409 | `RESET_BUSY` | 진행 중인 등록 · 취소 · 응모 · 추첨이 있습니다. 메시지에 건수가 나옵니다. 아무것도 지우지 않았습니다 |

```json
{
  "errorCode": "RESET_BUSY",
  "errorMessage": "진행 중인 등록 · 취소 · 응모 · 추첨이 1건 있어 초기화할 수 없습니다. 끝난 뒤 다시 부르세요.",
  "replayable": false,
  "externalNumber": null
}
```

- 409를 받으면 진행 중인 요청이 끝난 뒤 다시 부릅니다. 시연 중이라면 지연을 0으로 내리고, 지금 진행 중인 요청이 끝날 만큼(설정해 둔 최대 지연 + 유지 시간) 기다린 뒤 부릅니다. 부하 시험 뒤에는 원장 행 수가 멎을 때까지 기다리고 부릅니다(하네스가 이미 그렇게 합니다). 다만 `TIMEOUT` 실패 모드는 아무것도 커밋하지 않아 행 수가 처음부터 멎어 있으므로, 마지막 요청부터 유지 시간(7초)이 지났는지로 판단합니다.
- 전체 초기화입니다. 범위를 나눠 지울 수 없어, 부하 시험을 반복할 때는 매 실행 전에 부릅니다.
- **본 서비스의 외부 번호도 함께 비웁니다.** Mock만 지우면 전에 준 번호가 다시 나와 본 서비스의 UNIQUE 저장과 부딪힐 수 있습니다(같은 날 5,000건씩 14번 돌린 뒤 15번째에 하나라도 겹칠 확률 약 3.4%).
