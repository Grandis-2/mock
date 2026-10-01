# 외부 예약 Mock API 명세

| 문서 정보 | 내용 |
| --- | --- |
| 대상 | 외부 예약 시스템 Mock · 기능 F-S-05 · F-A-03 |
| 버전 / 작성일 | 5.4 / 2026-10-01 (워커 타임아웃 5초 · 유지 7초 · 결과 불명 처리 · 키 조회 404 의 뜻 · 포기 규칙 · 없는 경로 `NO_SUCH_ENDPOINT` · 500 문구 구분 · 헤더 검사) |
| 서버 | `http://localhost:8081` |
| 개수 | 8개 |
| 기준 | ERD v5 (`스마트폰 사전예약 + 최소 일반 판매 ERD · v5 · 2026-09-17`, dbdiagram) |

**이 문서가 Mock API 계약의 정본이다.** 계약을 바꾸려면 이 파일을 고치는 PR 로 하고, 구현과 다르면 둘 중 하나가 버그다.

본 서비스는 이 문서의 필드 이름을 그대로 쓴다. 이전 초안의 이름(`ourReservationId` · `itemCode` · `qty` · `reservationNo` 등)은 쓰지 않으며, 보내면 모르는 필드라 400 이다.

---

# 공통 사항

## 실행 전제

| 항목 | 내용 |
| --- | --- |
| 실행 대수 | **프로세스 1개.** 설정·결함이 메모리라 2개 이상이면 갈린다 |
| 저장 | 등록 기록만 `external_mock` 스키마(MySQL). 설정·결함은 메모리 |
| 스레드 | **가상 스레드.** 지연 2000ms 로 부하를 걸면 요청 수천 건이 Mock 안에서 동시에 대기한다. 블로킹 스레드 풀이면 큐가 밀려 설정값보다 훨씬 큰 지연이 관측되고, "우리 서버가 느린 것"과 "Mock 이 느린 것"을 구분할 수 없게 된다 |
| 지연 위치 | 지연은 **트랜잭션 밖**에서 기다린다. 트랜잭션 안에서 기다리면 DB 커넥션이 그만큼 묶여 풀이 마른다 |
| 정합성 검사 | Mock 이 API 로 제공하지 않는다. `external_mock` 스키마를 읽기 전용으로 직접 조회한다 |

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
| 3 | 재시도를 그만둘 때는 같은 키로 먼저 취소한다(키 조회 절 「본 서비스의 판단」) |

500 은 주입 실패 말고도 중복 키 재시도 상한 초과 · 처리하지 못한 오류에서 같은 코드로 난다. 워커에게는 모두 일시 실패이고 저장 여부는 위처럼 확인한다. 부하 시험 집계에서는 `errorMessage` 로 구분한다.

| 500 의 원인 | `errorMessage` |
| --- | --- |
| 주입 실패 (`failureMode=HTTP_5XX`) | `외부 시스템을 사용할 수 없습니다.` |
| 중복 키 재시도 상한 초과 | `중복 키 재시도 상한(5회)을 넘었습니다.` |
| 처리하지 못한 오류 (교착 등 Mock 의 문제) | `Mock 이 처리하지 못한 오류입니다.` |

재시도해도 결과가 같은 요청은 반드시 4xx 다. 메서드 · `Content-Type` · `Accept` 가 틀려도 405 · 415 · 406 이 아니라 400 `INVALID_REQUEST` 이고, 무엇이 틀렸는지를 메시지에 담는다.

등록 · 조회 · 취소는 헤더 검사를 **처리 전에** 한다. `Content-Type` 이 `application/json` 이 아니거나 `Accept` 가 JSON 을 받지 않는 요청은 아무것도 남기지 않는다. 오류 본문은 `Accept` 와 상관없이 JSON 이다.

## 요청 검증

**요청 본문에 계약에 없는 필드가 있으면 400 `INVALID_REQUEST` 다.** 모르는 필드를 조용히 버리면 워커가 계약과 다른 본문을 보내도 아무도 모르고, 설정 API 에 없는 값을 넣어도 200 이 나와 적용된 줄 알게 된다. 저장하지 않는 필드(취소의 `reason`)도 계약에 있으면 받는다.

400 의 `errorMessage` 에는 무엇이 틀렸는지 담는다. 문구는 사람이 읽으라고 둔 것이고, 워커는 `errorCode` 로 분기한다.

| 틀린 것 | `errorMessage` 예 |
| --- | --- |
| 계약에 없는 필드 | `quantity 은(는) 알 수 없는 필드입니다. 받을 수 있는 필드: customerId, productId, sku` |
| enum 값 | `failureMode 은(는) HTTP_5XX, TIMEOUT 중 하나여야 합니다. 받은 값: HTTP_429` |
| 필수 값 누락 | `sku 은(는) 필수입니다.` |
| 길이 | `sku 은(는) 80자 이하여야 합니다.` · `Idempotency-Key 은(는) 1~100자여야 합니다.` |
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
- Mock 은 키 형식을 UUID 로 강제하지 않는다. ERD 키 칸이 `varchar(100)` 이므로 **1~100자**만 검사한다. Mock 단독 시험에서 `test-lost-1` 같은 키를 쓸 수 있다.

## 내용 비교

같은 키의 재요청이 **같은 신청인지** 판정할 때 저장된 행의 아래 세 칸을 요청과 직접 비교한다.

```
customerId · productId · sku
```

ERD 에서 `request_hash` 를 제거했으므로 지문 대신 칼럼을 본다. 신청 내용이 이미 행에 남아 있고, 어느 필드가 달라 거절됐는지 응답에 담을 수 있다.

**본 서비스가 같은 키로 다른 내용을 보내는 것은 정상 흐름이 아니다.** `preorder_sync_jobs.request_payload` 가 접수 때 고정되기 때문이다. 그래도 거절하는 이유는 그 버그를 조용히 넘기지 않기 위해서다.

## 등록 자원의 필드

ERD `external_mock.preorder_registrations` 와 1:1 이다.

| 필드 | 타입 | ERD 칸 | 설명 |
| --- | --- | --- | --- |
| `externalKey` | string | `external_key` (PK) | 우리 `preorders.preorder_token` |
| `externalNumber` | string | `external_number` (UNIQUE) | Mock 이 최초 등록에서 발급. 등록 전 취소 표식이면 `null` |
| `customerId` | integer | `customer_id` | 우리 `customers.id`. 물리 FK 없음 |
| `productId` | integer | `product_id` | 우리 `products.id` |
| `sku` | string | `sku` | 우리 `product_variants.sku` |
| `status` | string | `status` | `ACTIVE` / `CANCELED` |
| `confirmedAt` | datetime | `confirmed_at` | 등록을 확정한 시각 |
| `canceledAt` | datetime | `canceled_at` | 취소 표식을 남긴 시각 |

`status = ACTIVE` 이면 나머지 필드가 모두 채워져 있어야 한다. ERD 의 `ck_registration_active_fields` CHECK 가 그것이다. 등록 전 취소는 키·상태·취소 시각만 있는 행으로 남는다.

`quantity` 는 ERD 에서 제거했다. 사전예약 신청 단위가 수량 1 고정이고 `preorders` 에도 수량 칸이 없다.

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

- 본 서비스가 접수한 예약을 등록하고 외부 예약번호를 발급한다. 형식은 **`R-yyyyMMdd-NNNNNNNNNN`** — UTC 발급일 + 10자리 난수. 겹치면 새 번호로 다시 뽑는다. 6자리면 하루 100만 개라 부하 시험에서 동나고, `reset` 뒤 전에 준 번호가 다시 나와 본 서비스의 UNIQUE 저장이 깨질 수 있어서 10자리다.
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
| `Idempotency-Key` | header | string | 필수 | 1~100자. 본 서비스는 `preorder_token`(`char(36)` UUID)을 보낸다 |

**Body**

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `customerId` | integer | 필수 | 우리 `customers.id` |
| `productId` | integer | 필수 | 우리 `products.id` |
| `sku` | string | 필수 | 우리 `product_variants.sku`. `maxLength=80` |

```json
{
  "customerId": 1001,
  "productId": 12,
  "sku": "SM-G999-256-BLK"
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

응답 헤더 `X-Idempotent-Replay`(boolean) · `X-Mock-Config-Version`(integer)

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
| 400 | `INVALID_REQUEST` | 필수 값 누락 · 계약에 없는 필드 · 키 길이 |
| 409 | `KEY_CANCELED` | 취소 표식이 있는 키 |
| **422** | **`KEY_PAYLOAD_MISMATCH`** | **같은 키로 다른 `customerId`·`productId`·`sku`** |
| 500 | `UPSTREAM_UNAVAILABLE` | `failureMode=HTTP_5XX` 의 주입 실패. 커밋 전 |

```json
{
  "errorCode": "KEY_PAYLOAD_MISMATCH",
  "errorMessage": "같은 키로 다른 내용이 요청되었습니다. sku 이(가) 다릅니다.",
  "replayable": false,
  "externalNumber": "R-20260916-0048213579"
}
```

### 확인 시나리오

| 시나리오 | 기대 결과 |
| --- | --- |
| 같은 키·같은 내용 연속 2회 | 같은 번호. 2회차 `X-Idempotent-Replay: true` |
| **같은 키·다른 sku** | **422 `KEY_PAYLOAD_MISMATCH`. 기존 등록 그대로** |
| 같은 키 동시 10건 | 정확히 1건만 등록. 번호 1개. 9건은 `X-Idempotent-Replay: true` |
| 취소된 키로 등록 시도 | 409 `KEY_CANCELED`. 등록 생성 안 됨 |
| **등록 성공 → 취소 → 같은 키 재등록** | **409 `KEY_CANCELED`.** 201 재생이 아님 |
| 실패율 1.0 으로 10회 실패 → 0.0 변경 후 재시도 | 즉시 성공. 저장된 실패를 재생하지 않음 |
| `RESPONSE_LOST_AFTER_COMMIT` 주입 후 재시도 | 저장된 성공 재생. 등록 1건 |
| 커밋 직후 강제 종료 → 재기동 → 같은 키 재요청 | 저장된 성공 재생. 새 번호 발급 없음 |

- **동시 요청 시나리오는 최소 100회 반복해야 의미가 있다.** 동시성 버그는 경합에서만 나오므로 한 번 통과는 증명이 아니다.
- **실패율 시나리오는 100% 로 돌려야 드러난다.** 일시 실패를 저장하면 실패율을 0% 로 내려도 저장된 실패가 영원히 재생되어 예약이 최종 확정에 도달하지 못한다. 5% 로만 시험하면 안 보인다.

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

| 이름 | 위치 | 타입 | 필수 |
| --- | --- | --- | --- |
| `externalNumber` | path | string | 필수 |

**Body**: 없음

### 시스템 처리

1. 외부 예약번호로 등록 행을 조회한다.
2. 취소된 등록이면 `status` 를 `CANCELED` 로, `canceledAt` 을 채워 반환한다. 숨기지 않는다.
3. 없으면 404.

### Response

**200 OK** — `POST /external/reservations` 의 201 과 같은 형식

**주요 오류**

| 응답 | errorCode | 설명 |
| --- | --- | --- |
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
| `externalKey` | path | string | 필수 | 1~100자. 우리 `preorder_token` |

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

**404 를 보고 포기하지 않는다.** 재시도를 그만둘 때(재시도 소진 · 마감 초과)는 먼저 같은 키로 `POST /external/cancellations` 를 보내 취소 표식을 남긴다. 취소는 지연 · 실패 주입이 없어 항상 200 이고, 표식이 생긴 뒤 늦게 도착한 등록은 409 `KEY_CANCELED` 로 거절된다. 표식 없이 포기하면 뒤늦게 커밋된 등록이 외부에만 남는다(유령 등록).

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

**Body** — `externalKey` 와 `externalNumber` 중 하나 이상 필수

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `externalKey` | string | 조건부 | 우리 `preorder_token`. 1~100자 |
| `externalNumber` | string | 조건부 | Mock 이 발급한 번호. 1~100자 |
| `reason` | string | 선택 | `USER_CANCEL` · `ADMIN_CANCEL` · `DEADLINE_EXCEEDED` · `RETRY_EXHAUSTED` · `GHOST_COMPENSATION` 등. **저장하지 않는다** (ERD 에 칸 없음) |

```json
{
  "externalKey": "9f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
  "externalNumber": null,
  "reason": "GHOST_COMPENSATION"
}
```

### 시스템 처리

1. 같은 키 행을 잠그고 등록·취소를 직렬화한다.
2. 활성 등록이 있으면 `CANCELED` 로 바꾸고 취소 시각을 남긴다.
3. 이미 취소된 등록이면 아무것도 바꾸지 않는다.
4. **등록 기록이 없어도 취소 표식 행을 만든다.** 같은 새 키를 등록 요청이 먼저 INSERT 했으면 1062 를 받으므로, 다시 잠금 읽기로 조회해 2단계부터 한다.
5. 지연 구간에서 기다리던 등록이 뒤늦게 진행돼도 표식 때문에 활성화되지 못한다.
6. `externalNumber` 만 받았으면 그 번호의 등록을 찾아 그 키로 1~3단계를 한다. **그 번호의 등록이 없으면 404 다.** 키가 없어 표식을 남길 수 없고, 번호는 Mock 이 등록할 때만 발급하므로 그 번호로 늦게 오는 등록도 없다.
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
| 400 | `INVALID_REQUEST` | `externalKey` 와 `externalNumber` 가 둘 다 없음 |
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
  "failureRate": 0.05,
  "failureMode": "HTTP_5XX",
  "configVersion": 4,
  "appliedAt": "2026-09-16T09:40:00.000Z"
}
```

| 필드 | 설명 |
| --- | --- |
| `registerLatencyMs` | 지연의 **평균** |
| `latencyJitter` | 지연을 흔드는 폭. 실제 대기는 `평균 × (1 ∓ 이 값)` 균등분포. **조회만 되고 변경은 안 된다** — 설정 파일로 정한다 |
| `configVersion` · `appliedAt` | 설정을 바꿀 때마다 오르는 버전과 그 시각 |

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

`latencyJitter` 는 받지 않는다. 보내면 모르는 필드라 400 이다. 실행 중에 바꿀 수 있으면 등록 파트와의
계약인 `ConfigSnapshot` 이 늘어나고, 부하 판정 도중 분포가 바뀌면 주입한 몫을 빼낼 수 없다.
`timeoutHoldMs` 와 같은 취급이다.

```json
{
  "registerLatencyMs": 2000,
  "failureRate": 0.0,
  "failureMode": "HTTP_5XX"
}
```

`failureMode` 2종 — `HTTP_5XX` 즉시 500 · `TIMEOUT` 응답하지 않은 채 연결을 **유지 시간** 동안 붙잡았다가 본문 없는 500 으로 끝낸다. 유지 시간이 워커 읽기 타임아웃보다 길어 워커에는 타임아웃으로 보인다(결과 불명). 둘 다 커밋 전 일시 실패다.

유지 시간은 Mock 설정 `mock.timeout-hold-ms` 이고 **기본값 7000ms** 다. 본 서비스 워커의 HTTP 읽기 타임아웃 **5초**(be `worker` 의 `external.mock.read-timeout`)에 2초를 더한 값이다. 워커 타임아웃을 바꾸면 이 값도 함께 바꾼다.

| 유지 시간 | 결과 |
| --- | --- |
| 워커 타임아웃보다 짧거나 2초 안쪽 | 워커가 타임아웃 대신 본문 없는 500 을 받을 수 있다. 둘이 같으면 경주가 되어 일부만 타임아웃이 된다(둘 다 5초에서 동시 200건 중 32% 가 500). 재현하려던 상황이 아니다 |
| 워커 타임아웃 + 2초 | 워커가 타임아웃을 겪고 Mock 도 곧 연결을 정리한다 |
| 너무 김 | 부하 시험 중 연결이 쌓인다. 1,000 RPS · `failureRate=1.0` 이면 유지 10초에 약 1만 개로 톰캣 기본 최대 연결(8,192)을 넘는다 |

### 시스템 처리

1. 값의 범위를 검사하고 벗어나면 400.
2. 설정을 바꾸고 `configVersion` 을 1 증가시킨다.
3. `appliedAt` 을 기록한다.
4. 진행 중인 등록 시도는 시작 시점 버전을 유지하고, 변경은 이후 시도부터 적용한다.
5. 반영된 설정을 그대로 반환한다.

### Response

**200 OK** — `GET /external/config` 와 같은 형식

**주요 오류**

| 응답 | errorCode | 설명 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | 범위 밖 값 |

### 확인 시나리오

| 시나리오 | 기대 결과 |
| --- | --- |
| 등록 진행 중 설정 변경 | 진행 중 시도는 원래 버전 유지 |
| 음수 지연 · 0~1 밖 실패율 | 400 |

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
| `externalKey` | string | 필수 | 결함을 걸 키 |
| `faultType` | string | 필수 | `RESPONSE_LOST_AFTER_COMMIT`. 결함을 더 만들 때를 위해 필드로 둔다 |

```json
{
  "externalKey": "9f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
  "faultType": "RESPONSE_LOST_AFTER_COMMIT"
}
```

### 시스템 처리

1. 키와 결함 유형을 검사한다.
2. 해당 키에 결함을 등록한다. 같은 키에 이미 결함이 있으면 새 결함으로 바꾼다.
3. 등록 요청이 그 키로 들어오면 등록 처리 **7단계**에서 발동한다. 등록을 커밋한 뒤 유지 시간 동안 응답하지 않는다(결과 불명). 다음 같은 키 요청은 저장된 성공을 재생해야 한다.
   - **새로 커밋한 등록에만 발동한다.** 이미 등록된 키에 걸면 재생 요청에는 커밋이 없어 발동하지 않고 그대로 남는다. 취소된 키(409)도 마찬가지다. 남은 결함은 `reset` 이 지운다.
4. 한 번 발동하면 자동 해제한다. 발동하지 않은 결함은 재기동하면 사라진다.
5. 다른 키에 영향을 주지 않는다.

### Response

**201 Created**

```json
{
  "externalKey": "9f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
  "faultType": "RESPONSE_LOST_AFTER_COMMIT",
  "createdAt": "2026-09-16T10:30:00.000Z"
}
```

**주요 오류**

| 응답 | errorCode | 설명 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | 잘못된 `faultType` 등 |

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
2. 등록 행과 취소 표식, 멱등 기록을 지운다.
3. 주입된 결함도 함께 지운다.
4. 지연·실패 설정과 설정 버전은 그대로 둔다.
5. 지운 건수를 반환한다.

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

전체 초기화다. ERD 에 실행 범위 칸이 없어 범위를 나눠 지울 수 없다. 부하 시험을 반복할 때는 매 실행 전에 이걸 부르는 흐름이 된다.
