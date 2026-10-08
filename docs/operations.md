# Mock 운영·시연 가이드

요구사항 8장이 요구하는 산출물이다. **Mock 조절 · 장애 재현 · 부하 실행 · 결과 확인** 네 가지를
넘겨받는 사람이 그대로 따라 할 수 있게 적는다.

> 절차 제목의 **확인함** 은 실제로 따라 해 본 것이다. 결과 표의 숫자는 그때 받은 값이다.

## 0. 준비

| | 버전 | 확인 |
| --- | --- | --- |
| JDK | 25 | `java -version` |
| Docker Desktop | 최신 | `docker ps` |
| Git | 최신 | `git --version` |

Gradle 은 설치하지 않는다. `gradlew` 가 받아온다.

저장소에 없어 직접 만들어야 하는 파일 두 개 — `.gitignore` 가 막아 두었다.

```bash
cp .env.example .env
cp src/main/resources/application.yml.example src/main/resources/application.yml
```

**둘 다 예시 그대로 쓰면 된다.** 3307 포트가 이미 쓰이고 있을 때만 `.env` 의 `MYSQL_PORT` 와
`application.yml` 의 `datasource.url` 을 **같이** 고친다. 한쪽만 고치면 접속이 거부된다.

`application.yml` 의 `mock:` 절은 재기동했을 때 돌아가는 기본값이다. **범위를 벗어나면 Mock 이 뜨지
않는다** — 틀린 값으로 조용히 도는 것보다 낫다. 지연 · 실패율은 설정 API 와 같은 범위다.

| 설정 | 받는 범위 | 흔한 실수 |
| --- | --- | --- |
| `register-latency-ms` | 0 ~ 60000 | |
| `failure-rate` | 0 ~ 1 | **5% 를 `5` 로 적기** — `0.05` 가 맞다. 막지 않으면 등록이 전부 실패한 채로 뜬다 |
| `latency-jitter` | 0 ~ 1 | 1 을 넘기면 하한만 0 에서 잘려 평균이 조용히 올라간다 |
| `timeout-hold-ms` | 0, 또는 **`worker-read-timeout-ms` + 2000 이상** | 워커 타임아웃과 같게 두기 — 워커가 타임아웃 대신 빈 500 을 받는다. 0 은 붙잡지 않고 바로 빈 500 을 보내는 시험 전용 값이라 운영 · 시연에서 쓰지 않는다 |
| `worker-read-timeout-ms` | 1 이상 | be 워커의 읽기 타임아웃(5초)을 옮겨 적는 값이다. 워커 값이 바뀌면 `timeout-hold-ms` 와 함께 옮긴다 |
| `spring.threads.virtual.enabled` | `true` | 줄이 빠지면 플랫폼 스레드로 돌아 대기하는 요청이 스레드를 다 차지한다. 그래서 꺼져 있으면 뜨지 않는다 |

> **예전에 복사한 `application.yml` 은 `timeout-hold-ms: 5000` 이라 이제 뜨지 않는다.** 7000 으로 고치거나
> 줄을 지워 기본값(7000)을 쓴다. 워커 타임아웃이 5초로 정해지면서 기본값이 바뀌었다.

뜨지 않으면 로그 맨 아래에 어느 값이 틀렸는지 나온다(확인함 — `failure-rate: 5` 로 기동).

```
APPLICATION FAILED TO START
    Property: mock.failureRate
    Value: "5.0"
    Reason: 다음 값 이하여야 합니다 1.0
```

**로그 레벨은 INFO 다.** 예전에 복사한 `application.yml` 은 DEBUG 라 그대로 둬도 뜨지만, 결함 발동 · 클라이언트가
먼저 끊은 요청 · 중복 키 재시도가 건마다 한 줄씩 찍힌다. 부하 판정에서는 수만 줄이 되니 INFO 로 고친다.
잠깐 DEBUG 로 보고 싶으면 파일을 고치지 않고 기동 인자로 켠다.

```bash
./gradlew bootRun --args='--logging.level.com.grandis.nova.mockapi=DEBUG'
```

```bash
docker compose up -d      # 처음 뜰 때 docs/schema.sql 이 자동 실행된다
./gradlew bootRun         # http://localhost:8081
curl http://localhost:8081/external/config
```

**NV-328(원장 `customer_id` · `product_id` 가 BIGINT → BINARY(16)) 이전에 만든 DB 는 그대로 뜨지 않는다.** `schema.sql` 이
`CREATE TABLE IF NOT EXISTS` 라 옛 표가 남고, `ddl-auto: validate` 가 칸 형 불일치로 기동을 멈춘다. 로컬은 `docker compose down -v` 뒤
다시 띄우고, 다른 DB 는 표를 비운 뒤 두 칸을 `BINARY(16) NULL` 로 바꾼다(행이 남은 채 바꾸면 오류 없이 쓰레기 값이 된다).
be 의 UUID 전환(NV-326)과 **같은 때** 바꾼다 — 새 Mock 은 숫자 id 를, 옛 Mock 은 UUID 를 400 으로 거절하므로 한쪽만 바뀐 사이의 등록은
모두 실패한다. 순서: be 워커 · Mock 정지 → 원장 비우고 칸 변경 → 새 Mock 기동 → be 배포(마이그레이션이 남은 동기화 작업도 비운다) → 워커 재개.

## 1. Mock 조절 — 지연과 실패율

재기동 없이 바꾼다. 시연 조작 패널이다.

```bash
curl localhost:8081/external/config
curl -X PUT localhost:8081/external/config -H 'Content-Type: application/json' \
  -d '{"registerLatencyMs":2000,"failureRate":0.0,"failureMode":"HTTP_5XX"}'
```

응답은 요청값이 아니라 **실제 적용값**이다. 범위를 벗어나면 400 이고 설정은 그대로다.

**`registerLatencyMs` 는 평균이다.** 실제 대기는 `평균 × (1 ∓ latencyJitter)` 사이에서 요청마다
흔들린다. 조회 응답에 `latencyJitter` 가 함께 나오니 지금 얼마나 흔들리는지 거기서 확인한다(기본 0.4).
지터는 설정 파일로만 정하고 이 API 로는 바꾸지 않는다 — 보내면 400 이다.

**설정·조회·취소 경로에는 지연·실패를 주입하지 않는다.** 그래서 실패율 100% 상태에서도 되돌릴 수
있다. (확인함 — 등록은 500 인데 설정 변경과 초기화는 200)

### 시험 프리셋

지연은 평균값이다. 오른쪽 칸이 지터 0.4 에서 실제로 뽑히는 범위다.

| 용도 | registerLatencyMs | failureRate | failureMode | 실제 대기 |
| --- | --- | --- | --- | --- |
| 기본 | 500 | 0.05 | HTTP_5XX | 300 ~ 700ms |
| 지연 감도 | 2000 | 0.0 | HTTP_5XX | 1.2 ~ 2.8초 |
| 재시도 시험 | 500 | 0.5 | HTTP_5XX | 300 ~ 700ms |
| **결함 시험** | 500 | **0.0** | HTTP_5XX | 300 ~ 700ms |
| 취소 경합 시험 | 10000 | 0.0 | HTTP_5XX | 6 ~ 14초 |
| 타임아웃 부하 | 0 | 1.0 | **TIMEOUT** | 없음 |
| 5xx 종류 섞기 | 500 | 0.05 | **MIXED** | 300 ~ 700ms |

**취소 경합**은 지연 동안 취소를 끼워 넣는 시험이라 창이 넉넉해야 한다. 가장 짧게 뽑혀도 6초라
손으로 취소를 보내기에 충분하다.

**지연 감도**의 최대 2.8초는 워커 타임아웃(5초) 안이다. 지연을 평균 3,500ms 이상으로 올리면 최대
4.9초에 처리 시간이 붙어 일부 요청이 워커에게는 결과 불명으로 보일 수 있다 — Mock 이 느린 게 아니라
설정이 타임아웃에 걸친 것이다.

결함 주입을 확인할 때 **실패율을 0 으로 내리는 이유** — 지연·실패 판정이 결함 발동보다 앞서므로,
기본 5% 로 두면 재시도가 결함에 닿기 전에 일시 실패로 끝날 수 있다.

## 2. 장애 재현

### 2-1. 결과 불명 만들기 — 커밋 전(`TIMEOUT`) · 커밋 후(결함) · 늦은 커밋(느린 성공)

결과 불명은 워커가 응답을 받지 못한 것이다. 만드는 방법이 셋이고, **워커 눈에는 똑같이 "응답 없음" 인데
DB 는 서로 다르다** — 없음 · 있음 · 나중에 생김. 이 대조가 멱등 키와 `by-key` 조회가 필요한 이유다.

| | `TIMEOUT` | 결함 (응답 유실) | 결함 (느린 성공) |
| --- | --- | --- | --- |
| 거는 법 | 실패율 + `failureMode: TIMEOUT` | `POST /external/faults` 로 키 하나 | `POST /external/faults` 로 키 하나 · `SLOW_SUCCESS` · `delayMs` |
| 발동 | 주사위에 걸린 요청 | 그 키의 새 등록 한 번 | 그 키의 등록 한 번 (실패 판정 통과) |
| 시점 | **커밋 전** | **커밋 후** | **커밋 전 대기** 뒤 정상 처리 |
| 워커가 보는 것 | 응답 없음 | 응답 없음 | 응답 없음 |
| DB | **없음** | **있음** | 포기 시점엔 **없음** → **나중에 생김** |
| 같은 키 재요청 | 새 등록 (`Replay: false`) | 재생 (`Replay: true`) | **먼저 커밋** (`Replay: false`) — 늦게 깬 원래 요청이 재생 쪽 |

`TIMEOUT` · 응답 유실은 `mock.timeout-hold-ms`(기본 7000ms) 동안 헤더도 보내지 않고 붙잡았다가 **본문 없는 500 으로
끝낸다.** 워커(읽기 타임아웃 5초)가 먼저 포기해야 결과 불명이 된다. 느린 성공은 붙잡는 것이 아니라 `delayMs` 만큼
기다린 뒤 **정상 처리**한다 — 워커가 포기한 뒤라 그 응답은 아무도 받지 않는다.

> 유지 시간이 워커 읽기 타임아웃 + 2초보다 **짧으면** 워커가 타임아웃 대신 빈 500 을 받아 "일시 실패" 로
> 처리한다. 재현하려던 상황이 아니다. 그래서 그렇게 설정하면 Mock 이 뜨지 않는다(0장).
> 둘이 같던 때(5초 · 5초)는 동시 200건 중 32%, 400건 중 58% 가 빈 500 이었다.

#### 커밋 전 응답 없음 (`TIMEOUT`) — 확인함 (2026-10-01, 유지 7초 · 워커 5초)

실패율을 1.0 으로 올려 매번 걸리게 한다. 5% 로 두면 원하는 순간에 재현되지 않는다.

```bash
curl -X PUT localhost:8081/external/config -H 'Content-Type: application/json' \
  -d '{"registerLatencyMs":0,"failureRate":1.0,"failureMode":"TIMEOUT"}'

curl --max-time 5 -X POST localhost:8081/external/reservations \
  -H 'Idempotency-Key: demo-timeout-1' -H 'Content-Type: application/json' \
  -d '{"ourReservationId":"demo-timeout-1","customerRef":"0199a3f2-7c4e-7a10-8b2d-3f4e5a6b7c8d","itemCode":"0199a3f2-7c4e-7b20-9c3e-4f5a6b7c8d9e","optionCode":"SM-G999-256-BLK","qty":1,"scope":"preorder"}'

curl -i localhost:8081/external/reservations/by-key/demo-timeout-1
```

`--max-time 5` 는 워커 읽기 타임아웃(5초)을 흉내 낸 것이다.

| 확인할 것 | 결과 |
| --- | --- |
| 응답 | **없음** — 5.0초에 클라이언트가 포기 |
| `by-key` 조회 | **404** `NOT_FOUND` — 등록되지 않았다 |
| DB | **0행** |
| 동시 200건 · 400건 (같은 5초 클라이언트) | **전부 타임아웃 · 빈 500 0건.** DB 0행 |
| 실패율 0 으로 되돌리고 같은 키 재요청 | **201 · `X-Idempotent-Replay: false`** — 저장된 게 없으니 새 등록 |

**되돌리는 것을 잊지 않는다.** 실패율 1.0 · `TIMEOUT` 이 남아 있으면 다음 등록이 전부 7초씩 붙잡힌다.

#### 커밋 후 응답 유실 (결함) — 확인함

지연·실패율로는 이 상황을 만들 수 없다. **주입한 실패는 모두 커밋 전이라 등록이 저장되지 않는다.**
"DB 에는 있는데 응답이 없는" 결과 불명을 만들 수 있는 유일한 수단이 결함 주입이다.

```bash
curl -X PUT localhost:8081/external/config -H 'Content-Type: application/json' \
  -d '{"registerLatencyMs":0,"failureRate":0.0}'

curl -X POST localhost:8081/external/faults -H 'Content-Type: application/json' \
  -d '{"externalKey":"demo-lost-1","faultType":"RESPONSE_LOST_AFTER_COMMIT"}'

curl --max-time 5 -X POST localhost:8081/external/reservations \
  -H 'Idempotency-Key: demo-lost-1' -H 'Content-Type: application/json' \
  -d '{"ourReservationId":"demo-lost-1","customerRef":"0199a3f2-7c4e-7a10-8b2d-3f4e5a6b7c8d","itemCode":"0199a3f2-7c4e-7b20-9c3e-4f5a6b7c8d9e","optionCode":"SM-G999-256-BLK","qty":1,"scope":"preorder"}'

curl -i localhost:8081/external/reservations/by-key/demo-lost-1
```

**응답이 오지 않는다.** `mock.timeout-hold-ms`(기본 7000ms) 만큼 붙잡았다가 본문 없는 500 으로
끝낸다. 워커(5초)는 그 전에 포기한다 — `--max-time 5` 가 워커를 흉내 낸 것이다. 빼고 보내면 7초 뒤
빈 500 을 받는데, 그건 워커가 겪는 일이 아니다.

| 확인할 것 | 결과 |
| --- | --- |
| 응답 | 없음 — `--max-time 5` 로 5.0초에 포기 (2026-10-01, 유지 7초) |
| `by-key` 조회 | **200** · `ACTIVE` · `storedOutcome: SUCCESS` |
| DB | **등록돼 있다.** 커밋은 됐고 응답만 유실된 상태 |
| 같은 키 재요청 | **201 재생**, 같은 번호, `X-Idempotent-Replay: true`. 결함은 자동 해제됨 |

#### 워커가 포기한 뒤 늦은 커밋 (느린 성공 결함) — 확인함 (2026-10-06)

위 둘과 다른 세 번째 결과 불명이다. 지정한 키의 등록이 **커밋 전에** 정한 시간만큼 기다린다. 워커가 5초에 포기하고
키 조회를 해도 아직 행이 없어 **404** 인데, 그 뒤에 커밋된다. 같은 키 재시도 · 예약 취소의 취소 표식이 제대로 막는지
키 하나로 보여 준다. 기다리는 동안은 잠금 전이라 다른 요청이 끼어들 수 있다(api.md 결함 절).

- **결함은 아직 등록하지 않은 키에 건다.** 이미 등록 · 취소된 키에 걸어도 기다리긴 하지만 늦은 커밋은 생기지 않는다
- **대기는 15초로 건다.** 원래 요청은 지연(약 0.5초) + 15초 뒤에 깨어난다. 워커가 5초에 포기한 뒤 재시도 · 취소를
  **손으로 쳐도 10초 가까이 여유**가 있다. 6초처럼 짧게 걸면 여유가 1초 남짓이라, 손으로 치는 사이에 원래 요청이
  먼저 커밋해 결과가 뒤집힌다(① 재시도가 재생을 받고, ② 는 등록이 생긴 뒤에 취소된다)

**① 기다리는 동안 같은 키로 재시도 → 행 하나**

```bash
curl -X PUT localhost:8081/external/config -H 'Content-Type: application/json' \
  -d '{"registerLatencyMs":500,"failureRate":0.0}'

curl -X POST localhost:8081/external/faults -H 'Content-Type: application/json' \
  -d '{"externalKey":"demo-slow-1","faultType":"SLOW_SUCCESS","delayMs":15000}'

# 워커처럼 5초에 포기한다
curl --max-time 5 -X POST localhost:8081/external/reservations \
  -H 'Idempotency-Key: demo-slow-1' -H 'Content-Type: application/json' \
  -d '{"ourReservationId":"demo-slow-1","customerRef":"0199a3f2-7c4e-7a10-8b2d-3f4e5a6b7c8d","itemCode":"0199a3f2-7c4e-7b20-9c3e-4f5a6b7c8d9e","optionCode":"SM-G999-256-BLK","qty":1,"scope":"preorder"}'

curl -i localhost:8081/external/reservations/by-key/demo-slow-1          # 404

curl -i -X POST localhost:8081/external/reservations \
  -H 'Idempotency-Key: demo-slow-1' -H 'Content-Type: application/json' \
  -d '{"ourReservationId":"demo-slow-1","customerRef":"0199a3f2-7c4e-7a10-8b2d-3f4e5a6b7c8d","itemCode":"0199a3f2-7c4e-7b20-9c3e-4f5a6b7c8d9e","optionCode":"SM-G999-256-BLK","qty":1,"scope":"preorder"}'         # 재시도

# 원래 요청이 깨어날 때까지(처음 등록부터 약 16초) 기다린 뒤
curl -i localhost:8081/external/reservations/by-key/demo-slow-1
```

| 단계 (처음 등록부터) | 결과 (2026-10-06 · 단계마다 2초 쉬며 실행) |
| --- | --- |
| 원래 요청 | 응답 없음 — `--max-time 5` 로 **5.2초**에 포기 |
| 키 조회 (7.3초) | **404** `NOT_FOUND` — 원래 요청은 아직 기다리는 중이라 행이 없다 |
| 같은 키 재시도 (9.6초) | **201** · `X-Idempotent-Replay: false` · `X-Mock-Injected-Latency-Ms: 501` — 결함은 이미 꺼내져 재시도는 기다리지 않고 **먼저 커밋**한다 |
| 원래 요청이 깬 뒤 키 조회 (22초) | **200** · `ACTIVE` · 등록 **하나**, 재시도가 받은 번호 그대로. 늦게 깬 원래 요청은 그 등록을 재생했다 |
| DB | 행 하나 · 번호 하나 |

**② 기다리는 동안 예약이 취소돼 같은 키로 취소 → 등록 없음**

```bash
curl -X PUT localhost:8081/external/config -H 'Content-Type: application/json' \
  -d '{"registerLatencyMs":500,"failureRate":0.0}'

curl -X POST localhost:8081/external/faults -H 'Content-Type: application/json' \
  -d '{"externalKey":"demo-slow-2","faultType":"SLOW_SUCCESS","delayMs":15000}'

# 원래 요청 — 결과를 보려고 20초 기다린다(워커라면 5초에 포기). 다른 터미널에서 보내거나 & 로 뒤에 둔다
curl --max-time 20 -X POST localhost:8081/external/reservations \
  -H 'Idempotency-Key: demo-slow-2' -H 'Content-Type: application/json' \
  -d '{"ourReservationId":"demo-slow-2","customerRef":"0199a3f2-7c4e-7a10-8b2d-3f4e5a6b7c8d","itemCode":"0199a3f2-7c4e-7b20-9c3e-4f5a6b7c8d9e","optionCode":"SM-G999-256-BLK","qty":1,"scope":"preorder"}' &

sleep 5
curl -i localhost:8081/external/reservations/by-key/demo-slow-2          # 404

curl -i -X POST localhost:8081/external/cancellations -H 'Content-Type: application/json' \
  -d '{"externalKey":"demo-slow-2","reason":"USER_CANCEL"}'               # 예약 취소 — 취소 작업이 보낸다

wait                                                                      # 원래 요청이 끝날 때까지
curl -i localhost:8081/external/reservations/by-key/demo-slow-2
```

| 단계 (처음 등록부터) | 결과 (2026-10-06 · 단계마다 2초 쉬며 실행) |
| --- | --- |
| 키 조회 (7.2초) | **404** — 아직 행이 없다 |
| 같은 키로 취소 (9.4초) | **200** · `hadActiveRegistration: false` · `cancelMarkerAt` 있음 — 등록 전이라 **취소 표식**만 남긴다 |
| 원래 요청이 끝내 받은 응답 (15.6초) | **409** `KEY_CANCELED` — 깨어났지만 표식에 막혀 저장하지 않았다 |
| 그 뒤 키 조회 | **200** · `registrations: []` · `cancelMarkerAt` 있음 |
| DB | `CANCELED` · 번호 `null` — **외부에 등록이 생기지 않았다** |

예약을 취소할 때 Mock 취소를 보내지 않으면 원래 요청이 15초 뒤에 커밋되어, 우리는 취소로 안내했는데 외부에는 등록이 생긴다.
그래서 예약을 취소로 끝낼 때는 **같은 키로 Mock 취소**를 보낸다(본 서비스의 취소 작업). 재시도를 다 썼을 때(DEAD_LETTER)는
보내지 않는다 — 관리자가 같은 키로 재처리해야 하기 때문이다(api.md 오류 분류 계약 「결과 불명」).

### 2-2. 처리 중 강제 종료 · 재기동 — 확인함 (2026-09-27)

```bash
# 1. 등록 몇 건을 넣고 번호를 적어둔다
# 2. 앱을 강제 종료한다 (정상 종료가 아니어야 한다)
#    Windows: Stop-Process -Id <8081 을 잡은 PID> -Force
# 3. 앱이 죽은 상태에서 DB 를 직접 조회한다
docker exec nova-mock-mysql mysql -unova -pnova -N \
  -e "select external_key, external_number, status from external_mock.preorder_registrations"
# 4. 재기동한 뒤 같은 키로 다시 등록 요청한다
```

수행 결과 — 등록 5건, 강제 종료, 재기동:

| 확인할 것 | 결과 |
| --- | --- |
| 죽어 있는 동안 DB | **5건 그대로** |
| 재기동 후 DB | 5건 유지 |
| 같은 키 5개 재요청 | **전부 같은 번호 + `X-Idempotent-Replay: true`** |
| 재요청 후 건수 | 여전히 5건 — **중복 등록 없음** |

> **재기동하면 설정이 기본값으로 돌아간다.** 실측으로 `configVersion` 1 → 0, 지연 500ms · 실패율
> 0.05 로 복귀했다. 설정이 메모리에 있기 때문이며 명세가 정한 동작이다.
> **시연에서 재기동을 보여준 뒤에는 조작 패널 값을 반드시 다시 넣어야 한다.**

### 2-3. 커밋 직후 강제 종료 · 재기동 — 확인함 (2026-09-27)

2-2 는 응답을 정상으로 받은 뒤에 죽인 것이고, 이쪽은 **커밋은 끝났는데 응답이 나가기 전에** 죽인
것이다.

**여기서 죽이는 것은 외부(Mock)다.** 요구사항 5.5 의 "처리 서버 중단" 은 워커 쪽 얘기라 이 절이
재현하는 것이 아니다. 이 절이 보이는 것은 두 가지다.

- **외부 쪽** — 커밋 직후 죽어도 기록이 남고, 재기동 뒤 같은 키에 저장된 성공을 재생한다.
  요구사항 5.4 **기록 보존**(*"등록 결과와 멱등 판정에 필요한 기록을 재기동 후에도 유지"*)이다
- **워커 쪽** — 워커 입장에서는 **외부 성공 응답 유실**이다. 등록은 됐는데 응답을 못 받았으므로
  `by-key` 로 확인하거나 같은 키로 재시도해야 하고, 그래도 중복 등록이 생기지 않는다

그 순간을 손으로 맞출 수는 없다. **결함 주입이 커밋 후 `timeout-hold-ms`(기본 7초) 동안 연결을
붙잡는 창을 열어 주므로**, 그 사이에 죽이면 결정적으로 재현된다. 지연은 커밋 **전**이라 이 용도로
쓸 수 없다.

```bash
# 1. 지연 0 · 실패율 0 으로 맞추고 결함을 건다
curl -X PUT localhost:8081/external/config -H 'Content-Type: application/json' \
  -d '{"registerLatencyMs":0,"failureRate":0.0}'
curl -X POST localhost:8081/external/faults -H 'Content-Type: application/json' \
  -d '{"externalKey":"crash-1","faultType":"RESPONSE_LOST_AFTER_COMMIT"}'

# 2. 등록을 보내고(응답이 안 온다) 2초쯤 뒤에 프로세스를 강제 종료한다
#    Windows: Stop-Process -Id <8081 을 잡은 PID> -Force
# 3. 죽은 상태에서 DB 를 직접 조회한다
# 4. 재기동한 뒤 같은 키로 다시 등록 요청한다
```

수행 결과:

| 확인할 것 | 결과 |
| --- | --- |
| 클라이언트 | **응답 없음** — "기본 연결이 닫혔습니다" |
| 죽은 상태의 DB | **`crash-1` · `R-20260927-9798988821` · ACTIVE · `confirmed_at 11:24:57.106`** |
| 재기동 후 같은 키 재요청 | **201 · `X-Idempotent-Replay: true` · 같은 번호 · 같은 `confirmedAt`** |
| 등록 건수 | **1건** — 새 번호를 발급하지 않았다 |

재요청이 736ms 걸린 것도 의미가 있다. 재기동으로 설정이 기본값(500ms)으로 돌아갔기 때문이다.
2-2 와 같은 이유이며, **시연에서 재기동 뒤에는 설정을 반드시 다시 넣어야 한다.**

### 2-4. 기록 초기화

시험 사이에 이전 실행의 잔량이 다음 대조에 섞이지 않게 한다.

```bash
curl -X POST localhost:8081/external/reset -H 'Content-Type: application/json' \
  -d '{"confirm":"RESET"}'
```

확인 문자열이 틀리면 400 이고 **아무것도 지우지 않는다.** 등록 행과 취소 표식을 지우고 지운 수를
돌려주며, **지연·실패 설정과 `configVersion` 은 남긴다.**

**진행 중인 등록 · 취소가 있으면 409 `RESET_BUSY` 로 거절하고 아무것도 지우지 않는다.** 메시지에 건수가
나온다. 지연 중이던 등록이 초기화 뒤에 커밋되어, 취소한 키가 ACTIVE 로 살아나는 것을 막는다.
`TIMEOUT` · 결함으로 붙잡힌 요청도 진행 중으로 세므로 유지 시간(기본 7초) 동안은 거절된다.

> **시연 중 409 를 받으면** 지연을 0 으로 내리고, 진행 중인 요청이 끝날 만큼(설정해 둔 최대 지연 + 유지
> 시간) 기다린 뒤 다시 부른다. 취소 경합 프리셋(지연 6~14초) 직후에 특히 잘 걸린다.

#### 진행 중 초기화 거절 — 확인함 (2026-10-01)

리뷰가 짚은 재현 순서 그대로다.

```bash
curl -X PUT localhost:8081/external/config -H 'Content-Type: application/json' \
  -d '{"registerLatencyMs":4000,"failureRate":0.0}'
# ① 키 K 등록을 보낸다(지연 4초 동안 기다리게 둔다)  ② 그사이 K 취소  ③ 그사이 초기화
```

| 순서 | 막기 전 (리뷰 실측) | 지금 |
| --- | --- | --- |
| ② K 취소 | 200 · 표식 | 200 · 표식 |
| ③ 초기화 (등록이 지연 중) | 200 · 표식까지 삭제 | **409 `RESET_BUSY`** · "진행 중인 등록 · 취소가 1건" · 아무것도 안 지움 |
| ④ 지연이 끝난 등록 | **201 · ACTIVE** (취소가 사라짐) | **409 `KEY_CANCELED`** — 표식이 남아 있어서 |
| ⑤ 초기화 다시 | — | 200 · `deletedCount: 1` (표식) |
| ⑥ K 키 조회 | 200 · ACTIVE | **404** · DB 0행 |

### 2-5. 5xx 종류별 응답 — `MIXED` (NV-312)

실제 연동에서는 500 말고도 앞단 장비(로드 밸런서 · 게이트웨이)의 502 · 503 · 504 가 온다. 기본 `HTTP_5XX` 는 늘 같은
500 이라, 워커가 다른 5xx 를 어떻게 처리 · 기록하는지는 볼 수 없다. `failureMode: MIXED` 면 실패에 걸린 요청마다
아래 중 하나를 같은 확률로 보낸다. 모두 커밋 전이라 저장되지 않고 키 조회는 404 다.

| 종류 | 응답 | 워커가 해야 할 일 |
| --- | --- | --- |
| `HTTP_500` | 500 + `errorCode: UPSTREAM_UNAVAILABLE` | 일시 실패 → 키 조회 → 같은 키 재시도 |
| `HTTP_500_NO_BODY` | 본문 없는 500 | **결과 불명** → 키 조회 먼저 |
| `HTTP_502` · `503` · `504` | 그 상태 + HTML 본문 (`errorCode` 없음) | **결과 불명** → 키 조회 먼저. 본문을 JSON 으로 읽다가 터지지 않아야 한다 |

```bash
# 다섯 종류를 섞는다 (실패율은 그대로 5%)
curl -X PUT localhost:8081/external/config -H 'Content-Type: application/json' \
  -d '{"registerLatencyMs":500,"failureRate":0.05,"failureMode":"MIXED"}'

# 하나만 — 실패율 100% 에 503 만 (시연 · 확인용)
curl -X PUT localhost:8081/external/config -H 'Content-Type: application/json' \
  -d '{"registerLatencyMs":0,"failureRate":1.0,"failureMode":"MIXED","mixedResponses":["HTTP_503"]}'
```

**be 로그와 맞대 보기** — Mock 은 실패를 주입할 때마다 키와 종류를 로그로 남긴다. 같은 키를 be 로그에서 찾으면
"Mock 이 503 을 줬는데 워커가 어떻게 처리했나" 를 바로 볼 수 있다. 결함도 같은 모양으로 남는다.

```
실패 주입 HTTP_503 key=9f1c2d3e-… failureMode=MIXED configVersion=3
결함 발동 SLOW_SUCCESS key=demo-slow-1 delayMs=15000
```

응답에도 표식 헤더 `X-Mock-Injected-Failure: HTTP_503` 이 붙는다. 부하 판정이 주입 실패를 가르는 데 쓰는 **시험용**
헤더라 실제 장비는 보내지 않는다 — **워커는 이 헤더로 분기하지 않는다.**

되돌릴 때는 `failureMode` 를 빼고(기본 `HTTP_5XX`) 다시 `PUT` 한다. 재기동해도 기본값으로 돌아간다.

## 3. 부하 실행

```bash
./gradlew bootRun                                                                # 터미널 1
./gradlew loadTest --args="http://localhost:8081 baseline classify --warmup"     # 터미널 2 · 예열
./gradlew loadTest --args="http://localhost:8081 baseline classify"              # 터미널 2 · 판정
```

인자는 `<주소> <시나리오> <패스> [건수] [JDBC] [계정] [비밀번호]` 이고, 예열에는 `--warmup` 을 어디든 붙인다.

| | 값 |
| --- | --- |
| 시나리오 | `baseline` · `latency` · `timeout` · `mixed` · `tail` · `tail-over-timeout` (`mixed` 는 baseline 에서 모드만 MIXED — 2-5. 뒤의 둘은 지연 꼬리 — [load-test.md](load-test.md) "지연 꼬리 시나리오") |
| 패스 | `classify`(분류 판정 · 타임아웃 5초 = 워커 읽기 타임아웃) · `latency`(지연 판정 · 10초) |
| 건수 | 생략하면 합의값 5,000 |
| JDBC | 생략하면 `localhost:3307`. **부하를 쏘는 장비가 Mock 과 다르면 반드시 넣는다** |
| `--warmup` | 예열 실행. 보고서 제목과 파일 이름(`warmup-…`)에 표시되고, 판정과 상관없이 0 으로 끝난다 |

**실행 전 준비** — `application.yml` 의 커넥션 풀을 **30 이상**으로 둔다.

```yaml
spring.datasource.hikari.maximum-pool-size: 30
```

20 은 500 RPS 에서 병목이었다. 요청이 커넥션을 기다리며 쌓여 결과 불명이 나왔고(1차 11.2% · 2차 0%),
불명이 없는 회차도 p95 오버헤드가 464ms 였다. 30 부터 70~99ms 로 떨어지고 50 과 차이가 없다.

**MySQL 커밋 동기화가 완화돼 있는지 확인한다.** `compose.yaml` 이 `--innodb-flush-log-at-trx-commit=2
--sync-binlog=0` 으로 띄운다. 예전에 만든 컨테이너면 `docker compose up -d` 로 다시 만든다(데이터 볼륨은 남는다).

```bash
docker exec nova-mock-mysql mysql -unova -pnova -N -e "select @@innodb_flush_log_at_trx_commit, @@sync_binlog"   # 2 0
```

기본값(1 · 1)으로 돌리면 Docker Desktop 디스크에서 COMMIT 이 평균 29ms · p99 200ms 까지 걸려 풀이 막히고, 결과 불명이
수천 건 나온다 — 판정이 Mock 이 아니라 디스크를 잰다(load-test.md "재판정에서 드러난 것"). 보고서의 실행 조건에도 이
설정이 찍히니 거기서 확인할 수 있다. **판정용 로컬 MySQL 에만 해당하고 RDS 는 기본값 그대로다.**

JDBC 주소 끝의 `useLocalSessionState=true` 도 둔다(예시 파일에 있다). 트랜잭션마다 격리 수준을 묻는 `SELECT` 를 없앤다.
판정을 흔든 원인은 아니었지만 비용 없이 명령 하나를 줄인다.

**순서를 지켜야 한다.** 시나리오 × 패스 조합마다 이렇게 돈다.

```
그 시나리오로 예열 2회 (--warmup · 버린다)
  → POST /external/reset → 판정
  → POST /external/reset → 판정
  → POST /external/reset → 판정          ← 3회
```

| | 조합 |
| --- | --- |
| classify | `baseline` · `latency` · `timeout` · `tail` · `tail-over-timeout` |
| latency | `baseline` · `latency` · `tail` |

`tail` · `tail-over-timeout` 조합(3개)은 NV-260 에서 더했다. 2026-10-01 의 15회 판정(앞 5개 조합)과 따로 센다.
`mixed`(NV-312)는 판정 규칙이 `baseline` 과 같고 정식 판정 회차에는 넣지 않았다 — 주입 실패가 문구 없이 와도 판정이
깨지지 않는지 보는 배선 확인용이다([load-test.md](load-test.md) "MIXED 시나리오").

- **예열은 시나리오마다 2회 한다.** `baseline` 만 예열하고 `latency` 를 돌리면 첫 회차가 튄다 — 재기동
  직후 첫 `latency` 가 결과 불명 91.9% 였고, `latency` 를 따로 예열한 뒤에는 0% 였다. 커밋 동기화를 완화해도
  기동 직후 첫 예열은 p95 112ms 로 느렸다(JIT). 2회면 넉넉하다
- **판정마다 초기화한다.** 앞 실행의 행이 남으면 "우리 키가 아닌 행" 으로 잡혀 판정이 실패한다
- **`timeout` 뒤에는 초기화 전에 몇 초 기다린다.** Mock 은 유지 시간(7초)까지 요청을 쥐고 있어서,
  클라이언트가 5초에 포기한 직후 최대 2초는 초기화가 409 `RESET_BUSY` 다. 받으면 잠시 뒤 다시 부른다
- **`tail-over-timeout` 뒤에도 초기화 전에 몇 초 기다린다.** 꼬리 요청은 최대 6.5초 동안 진행 중이라(클라이언트가
  5초에 포기한 뒤에 커밋한다) 그사이 초기화는 409 `RESET_BUSY` 다. 판정이 원장을 뜨기 전에 8초를 기다리므로 판정
  직후라면 대개 이미 끝나 있다
- **`tail-over-timeout` 은 classify 만 판정에 쓰고, 시나리오가 실패율 0 으로 건다.** 10초 패스에서는 꼬리도 제시간이라
  늦은 커밋이 생기지 않는다. 실패율이 0 이 아니면 꼬리 요청 일부가 6초 뒤 500 으로 끝나 "결과 불명 키는 전부 원장에
  있다" 를 단정할 수 없어 판정 불가다
- **3회씩 돌린다.** PC 가 몇 초만 멈칫해도 5,000건짜리 한 회차는 결과가 뒤집힌다. 3회를 전부 남긴다

**판정은 하네스가 한다.** 보고서 맨 위에 **PASS · FAIL · 판정 불가** 와 규칙별 결과 표가 찍히고, 보고서 경로 바로 아래 줄에
`판정: PASS` 처럼 한 번 더 나온다. 하네스 자체의 종료 코드는 0 · 1 · 2 지만 **`./gradlew loadTest` 로 돌리면 Gradle 이
0 이 아닌 값을 모두 1 로 끝낸다**(출력에는 `exit value 2` 처럼 보인다). 그래서 스크립트에서 `$?` 로는 PASS 와 아닌 것만
가르고, **FAIL 과 판정 불가는 그 `판정: …` 줄로 가른다(그 뒤에는 Gradle 의 실패 안내가 붙는다).**

| 결과 | 뜻 | 대표 원인 |
| --- | --- | --- |
| PASS | 모든 규칙 통과 | |
| FAIL | Mock 이 계약을 어겼거나 느리다 | 키 대조 위반 · 주입이 아닌 5xx(교착 등) · 5xx 가 실패율 범위 밖 · `timeout` 결과 불명 키가 원장에 있음 · 오버헤드 초과 · `tail-over-timeout` 결과 불명이 꼬리 비율 범위 밖 · 그 키가 원장에 없음(늦은 커밋이 안 됨) |
| **판정 불가** | 이 실행으로는 판정할 수 없다. Mock 탓으로 읽지 않는다 | 발사 지연 1초 초과 · 미전송 · 시나리오가 타임아웃에 걸침(꼬리가 타임아웃에 걸치는 설정 포함) · `tail-over-timeout` 을 실패율 0 이 아닌 채로 · 원장 조회 실패 · **확인용 등록이 원장에 없음**(엉뚱한 DB) |

하네스는 부하 직전에 **확인용 등록**(`canary-…`) 1건을 넣는다. 끝나고 원장에서 이 키를 찾지 못하면 JDBC 가
다른 DB 를 가리킨 것이라 판정 불가다. 대조에서는 이번 실행의 이 1건만 빠진다 — 앞 실행의 확인용 행이 남아
있으면 초기화를 빠뜨린 것이라 "우리 키가 아닌 행" 으로 FAIL 이다.

오버헤드는 **요청마다** 관측 응답 지연에서 그 요청에 실제로 뽑힌 지연(`X-Mock-Injected-Latency-Ms`)을 빼고,
그 분포의 p95 · p99 로 본다. 지연 판정 패스에서만 합격 조건이다.

보고서는 `build/load/` 에 남는다 — 판정은 `report-<시나리오>-<패스>-<시각>.md`, 예열은 `warmup-…`.
원장을 읽지 못해도 보고서는 남는다.

**돌리고 나서 서버 로그도 본다.** 둘 다 0건이어야 한다. 하네스도 응답의 `errorMessage` 로 같은 것을 세지만,
응답을 쓰기 전에 난 오류는 로그에만 남는다.

```
처리하지 못한 오류          (GlobalExceptionHandler)
중복 키 재시도 상한 초과     (DuplicateKeyRetry)
```

방법과 결과 해석은 [load-test.md](load-test.md) 를 본다.

## 4. 결과 확인

본 서비스의 정합성 검사는 원장 목록 조회 `GET /external/reservations` 로 읽는다(2026-10-06 팀 결정 — Mock DB 를
직접 읽지 않는다). 아래처럼 DB 를 직접 보는 것은 **운영자가 시험 결과를 눈으로 확인하는 용도**다.

```bash
docker exec nova-mock-mysql mysql -unova -pnova -N \
  -e "select status, count(*) from external_mock.preorder_registrations group by status"
```

부하 시험에서는 **클라이언트가 받은 응답과 DB 실제 등록 건수를 반드시 함께 본다.** 둘이 다른 것이
이 프로젝트가 다루는 문제 자체다. 실측에서 이렇게 갈렸다.

| 시나리오 | 결과 불명 | DB 에 남음 |
| --- | --- | --- |
| `baseline` | 559건 | **559건** — 커밋은 끝났고 응답만 늦었다 |
| `timeout` | 5,000건 | **0건** — 커밋 전에 끊겼다 |

(2026-09-28 판정 실행 · 커넥션 풀 20 인 회차의 숫자다.)

클라이언트 눈에는 둘이 똑같은 "응답 없음" 이다. 남은 쪽을 재시도하면 중복 등록이 되고, 안 남은
쪽은 재시도해야 한다. `by-key` 조회가 왜 필요한지가 이 표다.

> **집계는 서버가 잦아든 뒤에 한다.** 클라이언트가 멈춰도 서버는 계속 커밋한다. 행 수가 8초쯤
> 변하지 않을 때까지 기다린 뒤 센다. 부하 하네스는 이걸 스스로 하고 보고서에 대기 시간을 적는다.

---

## 시연 대본

| 순서 | 보여줄 것 | 조작 | 상태 |
| --- | --- | --- | --- |
| 1 | 정상 등록 | 지연 500 · 실패율 0 으로 등록 1건 | 절차 확인함 |
| 2 | 멱등 재생 | 같은 키 재요청 → 같은 번호 + 재생 헤더 | 절차 확인함 |
| 3 | 같은 키 다른 내용 | 422 `KEY_PAYLOAD_MISMATCH` → 기존 등록 그대로 | 절차 확인함 |
| 4 | 일시 실패와 재시도 | 실패율 1.0 → 500 → 키 조회 404 → 0.0 → 즉시 성공 | 절차 확인함 |
| 5 | **결과 불명 재현 — 세 가지** | `TIMEOUT` → 응답 없음 → 키 조회 404 · 재요청 새 등록 / 응답 유실 → 응답 없음 → 키 조회 200 · 재요청 재생 / **느린 성공** → 응답 없음 → 키 조회 404 · 재요청이 먼저 커밋(원래 요청은 늦게 재생) · 그사이 예약이 취소되면(같은 키로 취소) 원래 요청 409 | 절차 확인함 (2-1) |
| 6 | **커밋 직후 중단·재기동** | 결함이 붙잡은 창에서 강제 종료 → DB 확인 → 재기동 → 재생 | 절차 확인함 (2-3) |
| 7 | **재기동 후 설정 재입력** | 6번 뒤 `configVersion` 0 확인 → 프리셋 다시 넣기 | 필수 |
| 8 | 부하 | 10초 5,000건 → 5분류 집계 → DB 대조 | 판정 통과 ([load-test.md](load-test.md)) |

> 7번을 빼먹으면 그다음 시연이 기본값(500ms · 5%)으로 돌아간 상태에서 진행된다.

### 1~4. 등록 기본 흐름 — 확인함 (2026-09-29)

키 하나로 이어서 보여준다. 키는 읽기 쉬운 문자열을 쓴다 — 실제 워커는 `preorder_token`(UUID)을 보내지만
Mock 은 영문 · 숫자 · `. _ -` 로 된 1~100자면 받는다.

**준비** — 기록을 비우고 **실패율을 0 으로** 둔다. 기본 5% 로 두면 1~3번 세 요청 중 한 번이라도 500 이 날
확률이 약 14% 라 흐름이 끊긴다. 지연은 500ms 그대로 둬 응답이 0.5초 안팎 걸리는 게 보이게 한다.

```bash
curl -X POST localhost:8081/external/reset -H 'Content-Type: application/json' -d '{"confirm":"RESET"}'
curl -X PUT localhost:8081/external/config -H 'Content-Type: application/json' \
  -d '{"registerLatencyMs":500,"failureRate":0.0}'
```

**1. 정상 등록**

```bash
curl -i -X POST localhost:8081/external/reservations \
  -H 'Idempotency-Key: demo-1' -H 'Content-Type: application/json' \
  -d '{"ourReservationId":"demo-1","customerRef":"0199a3f2-7c4e-7a10-8b2d-3f4e5a6b7c8d","itemCode":"0199a3f2-7c4e-7b20-9c3e-4f5a6b7c8d9e","optionCode":"SM-G999-256-BLK","qty":1,"scope":"preorder"}'
```

**2. 멱등 재생** — 1번과 **똑같은 요청**을 한 번 더 보낸다. 이어서 키로 조회한다.

```bash
curl localhost:8081/external/reservations/by-key/demo-1
```

**3. 같은 키 다른 내용** — 키는 그대로 두고 `optionCode` 만 `SM-G999-512-BLK` 로 바꿔 보낸다. 이어서 1번에서 받은
번호로 조회해 기존 등록이 그대로인지 본다.

```bash
curl localhost:8081/external/reservations/<1번의 externalNumber>
```

**4. 일시 실패와 재시도** — 실패율을 1.0 으로 올리고 **새 키** `demo-2` 로 등록한다. 500 을 받은 뒤 키로
조회해 저장되지 않은 것을 보이고, 실패율을 0 으로 내려 같은 요청을 다시 보낸다.

```bash
curl -X PUT localhost:8081/external/config -H 'Content-Type: application/json' \
  -d '{"registerLatencyMs":500,"failureRate":1.0}'
curl -i -X POST localhost:8081/external/reservations \
  -H 'Idempotency-Key: demo-2' -H 'Content-Type: application/json' \
  -d '{"ourReservationId":"demo-2","customerRef":"0199a3f2-7c4e-7a11-8b2d-3f4e5a6b7c8e","itemCode":"0199a3f2-7c4e-7b20-9c3e-4f5a6b7c8d9e","optionCode":"SM-G999-256-BLK","qty":1,"scope":"preorder"}'
curl -i localhost:8081/external/reservations/by-key/demo-2
curl -X PUT localhost:8081/external/config -H 'Content-Type: application/json' \
  -d '{"registerLatencyMs":500,"failureRate":0.0}'
# 위의 등록 요청(demo-2)을 그대로 다시 보낸다
```

수행 결과:

| 순서 | 보여줄 것 | 결과 |
| --- | --- | --- |
| 1 | 201 · 번호 발급 | **201** · `X-Idempotent-Replay: false` · `R-20260929-8855269550` · `ACTIVE` · 0.75초. DB 1행 |
| 2 | 같은 번호 · 재생 헤더 | **201** · `X-Idempotent-Replay: true` · **같은 번호 · 같은 `confirmedAt`** · 0.57초. 키 조회 `storedOutcome: SUCCESS`. DB 여전히 1행 |
| 3 | 422 · 기존 등록 보존 | **422 `KEY_PAYLOAD_MISMATCH`** · "optionCode 이(가) 다릅니다" · `externalNumber` 에 기존 번호. 번호 조회의 `sku` 는 `256-BLK` 그대로 |
| 4 | 실패는 저장되지 않는다 | **500 `UPSTREAM_UNAVAILABLE`**(`replayable: false`) → 키 조회 **404** → 0.0 으로 내리고 재시도 **201 · `X-Idempotent-Replay: false`** · 새 번호. DB 2행 |

**말로 짚을 것**

- **2번** — 재생도 201 이다. 워커는 상태가 아니라 `X-Idempotent-Replay` 로 새 등록과 재생을 가른다
- **3번** — 422 는 본 서비스 버그라는 신호라 워커가 재시도하지 않는다. 응답에 기존 번호가 실려 무엇과 부딪혔는지 안다
- **4번** — **실패를 저장하지 않았기 때문에** 실패율을 내리자마자 같은 키가 성공한다. 저장했다면 500 이 영원히
  재생된다. 재시도가 재생(`true`)이 아니라 새 등록(`false`)인 것이 그 증거다
- **4번의 404** — 5xx 는 미등록의 증거가 아니라서 워커는 다시 보내기 전에 키 조회로 확인한다. 404 는 "지금
  등록이 없다" 는 뜻이라 같은 키로 다시 보낸다. 지연 중인 등록도 404 로 보이므로 404 만 보고 포기하지는 않는다.
  재시도를 다 써도 Mock 취소는 보내지 않는다 — 관리자가 같은 키로 재처리해 이어 간다

4번이 끝나면 지연 500 · 실패율 0 상태다. 5번은 2-1 절차대로 지연까지 0 으로 다시 넣는다.

## 예상 질문

| 질문 | 답 |
| --- | --- |
| 정합성 검사는 Mock 을 어떻게 읽나 | 원장 목록 조회 `GET /external/reservations`(외부 키 커서 페이지)로 받는다(2026-10-06 팀 결정). 실제 외부 시스템이면 DB 를 볼 수 없으니 API 로만 읽는다. 키 조회는 본 서비스가 아는 키만 물을 수 있어 "Mock 에만 있는 등록" 을 찾으려면 목록이 필요하다 |
| 지연이 평균 500ms 인가, 고정인가 | **평균이다**(요구사항 그대로, NV-121). 균등분포로 300~700ms 사이에서 흔든다. 흔들어도 부하 판정이 되는 이유는 균등분포라 주입한 지연의 p95(680ms)를 미리 알 수 있어서다. 아래 참고 |
| Mock 을 여러 개 띄우면 안 되나 | 설정·결함이 메모리라 2개 이상이면 설정 변경이 한쪽에만 적용되고, 결함을 건 키의 요청이 다른 쪽으로 가면 발동하지 않는다. 등록 기록은 DB 라 영향이 없다 |
| 5xx 를 접수 실패 중 어디에 세나 | **"일시 실패" 로 따로 센다.** 400·409·422 는 다시 보내도 결과가 같은 확정 거절이지만 5xx 는 재시도 대상이라 뜻이 정반대다. 합쳐 두면 설정한 실패율이 거절 수에 묻힌다 — 나눠 보니 `baseline` 의 일시 실패가 251건(5.0%)으로 `failureRate` 0.05 와 정확히 맞았다 |
| 결함 주입은 과제에 없는데 왜 넣었나 | 요구사항 8장이 *"외부 성공 응답 유실을 재현해 원래 신청의 처리 지속 확인"* 을 요구한다. 과제의 5% 실패는 모두 **커밋 전**이라 "등록은 됐는데 응답이 사라진" 상황을 만들 수 없고, 확률이라 시연 중 원하는 순간에 일어나지도 않는다. 결함은 키를 지정해 **커밋 후**에 한 번 확실히 일으킨다(2-1) |
| 과제는 실패 5% 인데 시연 1~3번은 왜 실패율 0 인가 | 1~3번은 멱등성을 보여주는 순서라 무작위 500 이 끼면 설명과 상관없는 장면이 된다. 5% 로 두면 세 요청 중 한 번이라도 500 이 날 확률이 1 − 0.95³ ≈ 14% 다. **조절이 되는 것**은 4번(실패율 1.0 → 0.0)에서, **5% 가 실제로 나오는 것**은 부하 결과(`baseline` 5xx 4.8 ~ 5.5%, [load-test.md](load-test.md))로 보여준다 |

### 지연은 평균 500ms 다

주제와 요구사항 2장이 Mock 동작을 **"평균 500ms 지연 · 5% 실패"** 로 정했다. 그래서 지연은
평균값이고, 요청마다 흔들린다.

> 처음에는 고정이었다(`Thread.sleep(500)`, 실측 0.538s · 0.540s). 요구사항과 달라 **NV-121 에서
> 평균으로 바꿨다.** be 쪽 시험 요청도 분포였다. 바꾼 뒤 실측은 20회에 372 ~ 720ms, 평균 538ms 다
> (설정 500 + 처리 오버헤드).

**어떻게 흔드나** — 균등분포다. 설정값을 평균으로 삼아 `평균 × (1 ∓ 0.4)` 사이에서 고르게 뽑는다.
평균 500ms 면 **300 ~ 700ms**, 평균은 500ms 다. 지터는 설정 파일의 `mock.latency-jitter` 로 정하고
**0 ~ 1 만 받는다** — 1 을 넘으면 하한만 0 에서 잘려 평균이 조용히 올라가므로, 벗어나면 Mock 이 기동하지
않는다.

**왜 균등분포인가** — 부하 판정을 살리기 위해서다. 판정은 관측 지연에서 "주입한 몫" 을 빼서 Mock 이
실제로 쓴 시간(오버헤드)을 본다. 균등분포는 주입한 지연의 백분위를 미리 계산할 수 있다.

| | 고정 500ms (이전) | 평균 500ms (균등 ±40%) |
| --- | --- | --- |
| 주입한 지연의 p95 | 500ms | **680ms** (300 + 400 × 0.95) |
| 주입한 지연의 p99 | 500ms | **696ms** |
| 오버헤드 | 관측 p95 − 500 | 관측 p95 − **680** |

지수분포가 현실에 더 가깝지만 꼬리가 길어 백분위가 요동친다. 그러면 뺄 값이 실행마다 달라져
"Mock 이 느린 것" 과 "지연이 길게 뽑힌 것" 을 가를 수 없다.

**판정도 평균으로 돈다.** 고정 모드를 따로 두지 않는다 — 요구사항이 평균이라 판정도 요구사항대로의
Mock 을 재야 한다. 부하 하네스는 설정의 지터를 읽어 뺄 값을 스스로 계산한다.
