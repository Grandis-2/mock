# Mock 운영·시연 가이드

요구사항 8장이 요구하는 산출물이다. **Mock 조절 · 장애 재현 · 부하 실행 · 결과 확인** 네 가지를
넘겨받는 사람이 그대로 따라 할 수 있게 적는다.

> **작성 중.** 아래 절차 중 확인 표시가 있는 것은 실제로 수행해 본 것이고, 나머지는 아직이다.

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

**설정·조회·취소 경로에는 지연·실패를 주입하지 않는다.** 그래서 실패율 100% 상태에서도 되돌릴 수
있다. (확인함 — 등록은 500 인데 설정 변경과 초기화는 200)

### 시험 프리셋

| 용도 | registerLatencyMs | failureRate | failureMode |
| --- | --- | --- | --- |
| 기본 | 500 | 0.05 | HTTP_5XX |
| 지연 감도 | 2000 | 0.0 | HTTP_5XX |
| 재시도 시험 | 500 | 0.5 | HTTP_5XX |
| **결함 시험** | 500 | **0.0** | HTTP_5XX |
| 취소 경합 시험 | 10000 | 0.0 | HTTP_5XX |
| 타임아웃 부하 | 0 | 1.0 | **TIMEOUT** |

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
것이다. 과제가 요구하는 *"성공 응답을 받은 예약은 처리 서버가 도중 중단됐다 재기동되어도 유실 없이
끝까지 처리"* 의 가장 나쁜 경우다.

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
./gradlew bootRun                                          # 터미널 1
./gradlew loadTest --args="http://localhost:8081 baseline" # 터미널 2
```

시나리오는 `baseline` · `latency` · `timeout` 세 가지다. 보고서는 `build/load/` 에 남는다.

조건(요청 규모 · 발생 구간 · 응답 타임아웃 · 관찰 종료 조건 · 허용 실패율 · 지연 기준)은
`LoadPlan.draft()` 한 곳에 모여 있다. **아직 팀 합의 전이라 보고서에 "미합의" 로 표시된다.**

방법과 결과 해석은 [load-test.md](load-test.md) 를 본다.

## 4. 결과 확인

Mock 은 대조용 목록 API 를 제공하지 않는다. **`external_mock` 스키마를 읽기 전용으로 직접
조회한다**(팀 결정, 요구사항 2.3 · 6.1).

```bash
docker exec nova-mock-mysql mysql -unova -pnova -N \
  -e "select status, count(*) from external_mock.preorder_registrations group by status"
```

부하 시험에서는 **클라이언트가 받은 응답과 DB 실제 등록 건수를 반드시 함께 본다.** 둘이 다른 것이
이 프로젝트가 다루는 문제 자체다 — 실측에서 응답 396건인데 DB 에는 5,000건이 있었다.

---

## 시연 대본 (뼈대 — 작성 중)

| 순서 | 보여줄 것 | 조작 | 상태 |
| --- | --- | --- | --- |
| 1 | 정상 등록 | 기본 프리셋으로 등록 1건 | 뼈대 |
| 2 | 멱등 재생 | 같은 키 재요청 → 같은 번호 + 재생 헤더 | 뼈대 |
| 3 | 같은 키 다른 내용 | 422 `KEY_PAYLOAD_MISMATCH` | 뼈대 |
| 4 | 일시 실패와 재시도 | 실패율 1.0 → 0.0 → 즉시 성공 | 뼈대 |
| 5 | **결과 불명 재현** | 결함 주입 → 응답 없음 → DB 에는 있음 → 재요청 재생 | 절차 확인함 |
| 6 | **커밋 직후 중단·재기동** | 결함이 붙잡은 창에서 강제 종료 → DB 확인 → 재기동 → 재생 | 절차 확인함 |
| 7 | **재기동 후 설정 재입력** | 6번 뒤 `configVersion` 0 확인 → 프리셋 다시 넣기 | 필수 |
| 8 | 부하 | 10초 5,000건 → 5분류 집계 → DB 대조 | 조건 합의 후 |

> 7번을 빼먹으면 그다음 시연이 기본값(500ms · 5%)으로 돌아간 상태에서 진행된다.

## 예상 질문

| 질문 | 답 |
| --- | --- |
| 왜 대조용 목록 API 가 없나 | 요구사항 2.3 이 *"대조를 위해 원격 목록 API 를 반드시 만들 필요는 없음"* 으로 정했다. 대신 검사가 실패한 실행도 보고서로 남겨 "조회 실패" 를 "불일치 0건" 으로 표시하지 않는다 |
| 주제는 "평균 500ms" 인데 왜 고정 지연인가 | **Mock 은 흉내내는 게 아니라 재는 도구라서.** 고정이면 `관측 − 설정값` 이 곧 Mock 이 쓴 시간이다. 아래 참고 |
| Mock 을 여러 개 띄우면 안 되나 | 설정·결함이 메모리라 2개 이상이면 설정 변경이 한쪽에만 적용되고, 결함을 건 키의 요청이 다른 쪽으로 가면 발동하지 않는다. 등록 기록은 DB 라 영향이 없다 |
| 5xx 를 접수 실패 중 어디에 세나 | **"일시 실패" 로 따로 센다.** 400·409·422 는 다시 보내도 결과가 같은 확정 거절이지만 5xx 는 재시도 대상이라 뜻이 정반대다. 합쳐 두면 설정한 실패율이 거절 수에 묻힌다 — 나눠 보니 `baseline` 의 일시 실패가 251건(5.0%)으로 `failureRate` 0.05 와 정확히 맞았다. A 가 PR #9 에서 제안, 반영 완료 |

### 고정 지연을 택한 이유

`registerLatencyMs` 는 평균이 아니라 **고정값**이다. 분포도 지터도 없다.

```java
// DefaultFailureInjector
sleep(snapshot.registerLatencyMs());   // Thread.sleep(500) — 요청마다 정확히
```

실측으로 0.538s · 0.540s 가 나온다. 주제는 외부 시스템을 *"평균 500ms"* 로 말하므로 현실성은
분포 쪽이 높다. 그래도 고정을 택한 이유는 **Mock 의 목적이 느린 외부를 흉내내는 게 아니라, 본
서비스가 느린 외부에서 버티는지 재는 것**이기 때문이다. 계측기의 눈금은 흔들리면 안 된다.

| | 고정 500ms | 평균 500ms (분포) |
| --- | --- | --- |
| 오버헤드 판정 | **관측 − 500 = Mock 이 쓴 시간** | 분포의 백분위를 알아야 빼낼 수 있다 |
| 재현성 | 같은 조건이면 같은 결과 | 실행마다 달라진다 |
| 시연 | "2초로 올리면 2초 걸린다" 가 눈에 보인다 | 설명이 길어진다 |
| 현실성 | 낮다 | 높다 |

부하 시험에서 이 차이가 그대로 드러났다. 100 RPS 실행의 p95 가 579ms 였고, 고정값이라 바로
**오버헤드 79ms** 로 갈렸다. 분포였다면 그 79ms 를 뽑아낼 수 없다.

바꾸려면 `sleep()` 한 줄이면 된다. 다만 **오버헤드 판정 기준을 다시 짜야 한다** — 관측 p95 에서
뺄 값이 상수가 아니라 그 분포의 p95 가 되고, 그건 실행마다 다르다. 판정이 흐려지는 대가를
치르고 얻는 것이 "현실성" 하나라서, 지금은 고정으로 둔다.
