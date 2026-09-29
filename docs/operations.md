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

```bash
docker compose up -d      # 처음 뜰 때 docs/schema.sql 이 자동 실행된다
./gradlew bootRun         # http://localhost:8081
curl http://localhost:8081/external/config
```

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

**취소 경합**은 지연 동안 취소를 끼워 넣는 시험이라 창이 넉넉해야 한다. 가장 짧게 뽑혀도 6초라
손으로 취소를 보내기에 충분하다.

**지연 감도**를 시연할 때는 워커 타임아웃(3초 가정)과 가까워진다는 걸 염두에 둔다. 최대 2.8초에 처리
시간이 붙으면 일부 요청이 워커에게는 결과 불명으로 보일 수 있다 — Mock 이 느린 게 아니라 설정이
타임아웃에 걸친 것이다. 부하 판정의 `latency` 시나리오를 1500ms 로 둔 이유다.

결함 주입을 확인할 때 **실패율을 0 으로 내리는 이유** — 지연·실패 판정이 결함 발동보다 앞서므로,
기본 5% 로 두면 재시도가 결함에 닿기 전에 일시 실패로 끝날 수 있다.

## 2. 장애 재현

### 2-1. 커밋 후 응답 유실 (결과 불명 만들기) — 확인함

지연·실패율로는 이 상황을 만들 수 없다. **주입한 실패는 모두 커밋 전이라 등록이 저장되지 않는다.**
결과 불명을 만들 수 있는 유일한 수단이 결함 주입이다.

```bash
curl -X PUT localhost:8081/external/config -H 'Content-Type: application/json' \
  -d '{"registerLatencyMs":0,"failureRate":0.0}'

curl -X POST localhost:8081/external/faults -H 'Content-Type: application/json' \
  -d '{"externalKey":"demo-lost-1","faultType":"RESPONSE_LOST_AFTER_COMMIT"}'

curl -X POST localhost:8081/external/reservations \
  -H 'Idempotency-Key: demo-lost-1' -H 'Content-Type: application/json' \
  -d '{"customerId":1001,"productId":12,"sku":"SM-G999-256-BLK"}'
```

**응답이 오지 않는다.** `mock.timeout-hold-ms`(기본 5000ms) 만큼 붙잡았다가 끊는다.

| 확인할 것 | 결과 |
| --- | --- |
| 응답 | 없음. 워커의 읽기 타임아웃이 유지 시간보다 짧아야 타임아웃으로 보인다 |
| DB | **등록돼 있다.** 커밋은 됐고 응답만 유실된 상태 |
| 같은 키 재요청 | **201 재생**, 같은 번호, `X-Idempotent-Replay: true`. 결함은 자동 해제됨 |

> 워커 읽기 타임아웃보다 유지 시간이 **짧으면** 워커가 타임아웃 대신 500 을 받아 "일시 실패" 로
> 처리한다. 재현하려던 상황이 아니다. 명세가 "워커 타임아웃 + 2초" 로 정한 이유다.

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

그 순간을 손으로 맞출 수는 없다. **결함 주입이 커밋 후 `timeout-hold-ms`(기본 5초) 동안 연결을
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

## 3. 부하 실행

```bash
./gradlew bootRun                                                     # 터미널 1
./gradlew loadTest --args="http://localhost:8081 baseline classify"   # 터미널 2
```

인자는 `<주소> <시나리오> <패스> [건수] [JDBC] [계정] [비밀번호]` 다.

| | 값 |
| --- | --- |
| 시나리오 | `baseline` · `latency` · `timeout` |
| 패스 | `classify`(분류 판정 · 타임아웃 3초) · `latency`(지연 판정 · 10초) |
| 건수 | 생략하면 합의값 5,000 |
| JDBC | 생략하면 `localhost:3307`. **부하를 쏘는 장비가 Mock 과 다르면 반드시 넣는다** |

**실행 전 준비** — `application.yml` 의 커넥션 풀을 **30 이상**으로 둔다.

```yaml
spring.datasource.hikari.maximum-pool-size: 30
```

20 은 500 RPS 에서 병목이었다. 요청이 커넥션을 기다리며 쌓여 결과 불명이 나왔고(1차 11.2% · 2차 0%),
불명이 없는 회차도 p95 오버헤드가 464ms 였다. 30 부터 70~99ms 로 떨어지고 50 과 차이가 없다.

**순서를 지켜야 한다.** 시나리오 × 패스 조합마다 이렇게 돈다.

```
그 시나리오로 예열 1회 (버린다)
  → POST /external/reset → 판정
  → POST /external/reset → 판정
  → POST /external/reset → 판정          ← 3회
```

| | 조합 |
| --- | --- |
| classify | `baseline` · `latency` · `timeout` |
| latency | `baseline` · `latency` |

- **예열은 시나리오마다 한다.** `baseline` 만 예열하고 `latency` 를 돌리면 첫 회차가 튄다 — 재기동
  직후 첫 `latency` 가 결과 불명 91.9% 였고, `latency` 를 따로 예열한 뒤에는 0% 였다
- **판정마다 초기화한다.** 앞 실행의 행이 남으면 "우리 키가 아닌 행" 으로 잡혀 판정이 실패한다
- **3회씩 돌린다.** PC 가 몇 초만 멈칫해도 5,000건짜리 한 회차는 결과가 뒤집힌다. 3회를 전부 남기고,
  보고서 맨 앞에 경고가 뜬 회차는 원인을 함께 적는다

보고서 맨 앞에 뜰 수 있는 경고는 셋이다.

| 경고 | 뜻 |
| --- | --- |
| 발사 지연 | 클라이언트가 제때 쏘지 못했다 — PC 전체 정지. 성능 판정에서 뺀다 |
| 미전송 | 연결을 못 맺었다. 원인(클라이언트 · Mock)을 적기 전엔 판정에 안 쓴다 |
| 타임아웃에 걸침 | 주입한 지연 최대 + 허용 오버헤드가 응답 타임아웃을 넘는다. **쏘기 전에 콘솔에도 뜬다** |

보고서는 `build/load/` 에 남고, 클라이언트가 본 결과를 **등록 원장과 키로 대조한 판정**이 함께
찍힌다. 지연이 평균이라 **주입한 지연**도 같이 찍히고, 오버헤드는 관측 백분위에서 주입한 지연의 같은
백분위를 뺀 값이다. 조건은 2026-09-28 합의됐다.

**돌리고 나서 서버 로그도 본다.** 둘 다 0건이어야 한다.

```
처리하지 못한 오류          (GlobalExceptionHandler)
중복 키 재시도 상한 초과     (DuplicateKeyRetry)
```

방법과 결과 해석은 [load-test.md](load-test.md) 를 본다.

## 4. 결과 확인

Mock 은 대조용 목록 API 를 제공하지 않는다. **`external_mock` 스키마를 읽기 전용으로 직접
조회한다**(팀 결정, 요구사항 2.3 · 6.1).

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
| 5 | **결과 불명 재현** | 결함 주입 → 응답 없음 → DB 에는 있음 → 재요청 재생 | 절차 확인함 (2-1) |
| 6 | **커밋 직후 중단·재기동** | 결함이 붙잡은 창에서 강제 종료 → DB 확인 → 재기동 → 재생 | 절차 확인함 (2-3) |
| 7 | **재기동 후 설정 재입력** | 6번 뒤 `configVersion` 0 확인 → 프리셋 다시 넣기 | 필수 |
| 8 | 부하 | 10초 5,000건 → 5분류 집계 → DB 대조 | 판정 통과 ([load-test.md](load-test.md)) |

> 7번을 빼먹으면 그다음 시연이 기본값(500ms · 5%)으로 돌아간 상태에서 진행된다.

### 1~4. 등록 기본 흐름 — 확인함 (2026-09-29)

키 하나로 이어서 보여준다. 키는 읽기 쉬운 문자열을 쓴다 — 실제 워커는 `preorder_token`(UUID)을 보내지만
Mock 은 1~100자만 본다.

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
  -d '{"customerId":1001,"productId":12,"sku":"SM-G999-256-BLK"}'
```

**2. 멱등 재생** — 1번과 **똑같은 요청**을 한 번 더 보낸다. 이어서 키로 조회한다.

```bash
curl localhost:8081/external/reservations/by-key/demo-1
```

**3. 같은 키 다른 내용** — 키는 그대로 두고 `sku` 만 `SM-G999-512-BLK` 로 바꿔 보낸다. 이어서 1번에서 받은
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
  -d '{"customerId":1002,"productId":12,"sku":"SM-G999-256-BLK"}'
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
| 3 | 422 · 기존 등록 보존 | **422 `KEY_PAYLOAD_MISMATCH`** · "sku 이(가) 다릅니다" · `externalNumber` 에 기존 번호. 번호 조회의 `sku` 는 `256-BLK` 그대로 |
| 4 | 실패는 저장되지 않는다 | **500 `UPSTREAM_UNAVAILABLE`**(`replayable: false`) → 키 조회 **404** → 0.0 으로 내리고 재시도 **201 · `X-Idempotent-Replay: false`** · 새 번호. DB 2행 |

**말로 짚을 것**

- **2번** — 재생도 201 이다. 워커는 상태가 아니라 `X-Idempotent-Replay` 로 새 등록과 재생을 가른다
- **3번** — 422 는 본 서비스 버그라는 신호라 워커가 재시도하지 않는다. 응답에 기존 번호가 실려 무엇과 부딪혔는지 안다
- **4번** — **실패를 저장하지 않았기 때문에** 실패율을 내리자마자 같은 키가 성공한다. 저장했다면 500 이 영원히
  재생된다. 재시도가 재생(`true`)이 아니라 새 등록(`false`)인 것이 그 증거다
- **4번의 404** — 5xx 는 미등록의 증거가 아니라서 워커는 다시 보내기 전에 키 조회로 확인한다. 키 조회는 진행 중인
  등록을 기다린 뒤 답하므로 404 가 "등록 안 됨" 의 확정 근거다

4번이 끝나면 지연 500 · 실패율 0 상태다. 5번은 2-1 절차대로 지연까지 0 으로 다시 넣는다.

## 예상 질문

| 질문 | 답 |
| --- | --- |
| 왜 대조용 목록 API 가 없나 | 요구사항 2.3 이 *"대조를 위해 원격 목록 API 를 반드시 만들 필요는 없음"* 으로 정했다. 대신 검사가 실패한 실행도 보고서로 남겨 "조회 실패" 를 "불일치 0건" 으로 표시하지 않는다 |
| 지연이 평균 500ms 인가, 고정인가 | **평균이다**(요구사항 그대로, NV-121). 균등분포로 300~700ms 사이에서 흔든다. 흔들어도 부하 판정이 되는 이유는 균등분포라 주입한 지연의 p95(680ms)를 미리 알 수 있어서다. 아래 참고 |
| Mock 을 여러 개 띄우면 안 되나 | 설정·결함이 메모리라 2개 이상이면 설정 변경이 한쪽에만 적용되고, 결함을 건 키의 요청이 다른 쪽으로 가면 발동하지 않는다. 등록 기록은 DB 라 영향이 없다 |
| 5xx 를 접수 실패 중 어디에 세나 | **"일시 실패" 로 따로 센다.** 400·409·422 는 다시 보내도 결과가 같은 확정 거절이지만 5xx 는 재시도 대상이라 뜻이 정반대다. 합쳐 두면 설정한 실패율이 거절 수에 묻힌다 — 나눠 보니 `baseline` 의 일시 실패가 251건(5.0%)으로 `failureRate` 0.05 와 정확히 맞았다 |

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
