# Mock 설계 · 시험

API 계약은 [api.md](api.md), 스키마는 [schema.sql](schema.sql) 이 정본이다. 이 문서는 그 계약을
**왜 이렇게 구현했는지**와 **어떻게 시험하는지**를 적는다.

## 실행 전제

**기본은 1개로 띄운다(`mock.shared-state=memory`).** 지연·실패 설정과 결함이 메모리에 있어 2개 이상이면 설정 변경이
받은 서버에만 적용되고, 결함을 건 키의 요청이 다른 서버로 가면 발동하지 않는다. 등록 기록은 DB 라 영향이 없다.
**여러 대로 띄울 때는 `redis`** 로 설정 · 결함을 Redis 에 둔다(아래 「설정 · 결함 공유」).

저장소는 MySQL `external_mock` database 다. 본 서비스와 **같은 인스턴스의 다른 database** 이며
cross-database 조회나 물리 FK 를 두지 않는다. 재기동해도 등록 기록이 유지된다(5.4 기록 보존).
`compose.yaml` 은 Mock 만 따로 개발할 때 쓰는 것이고, 통합할 때는 본 서비스 쪽 인스턴스에
`external_mock` database 를 만들어 붙인다.

지연·실패율 설정은 테이블에 두지 않으므로 한 대(memory)면 **재기동하면 기본값(500ms / 5%)으로 돌아간다.**
요구사항이 요구하는 것은 "재기동 없이 변경"이지 "재기동 후 유지"가 아니고, 시연은 매번 같은 초기
상태에서 시작하는 편이 낫기 때문이다. 기본값은 `application.yml` 의 `mock.*` 다. 여러 대(redis)면 재기동해도
공유된 설정을 따른다 — 한 대만 기본값으로 돌아가면 그 서버만 다른 실패율로 돈다.

업무 호출은 HTTP 로만 한다. **정합성 검사도 예외가 아니다** — 본 서비스는 `external_mock` 스키마를 직접
읽지 않고 원장 목록 조회(`GET /external/reservations`, 외부 키 커서 페이지)로 받는다(2026-10-06 팀 결정, 실제
외부 시스템처럼). 예전에는 요구사항 6.1 의 기준안대로 스키마를 직접 조회하기로 했었다.

## 구조

```
com.grandis.nova.mockapi/
├── global/
│   ├── chaos/      설정 스냅샷 · 지연(몸통 · 느린 꼬리)·실패 주입 · 결함
│   ├── config/     MockProperties — 지연 평균·지터·실패율·유지 시간·워커 타임아웃 기본값, 유지 ≥ 워커 + 2초 기동 검증
│   │               VirtualThreadGuard — 가상 스레드가 꺼져 있으면 기동 실패
│   │               엄격한 JSON(모르는 필드 · 값이 바뀌는 타입 변환 · 중복 필드 거절, 정수 → 실수만 받음) · 응답 시각 형식
│   ├── error/      오류 코드 · 응답 형식 · 예외 핸들러
│   └── validation/ 키 · 번호 형식 규칙(Identifiers) — 등록 파트 · 제어 파트(결함 키)가 같이 쓴다
├── registration/   등록 원장 — 등록 · 조회 · 취소 · 멱등 판정 · 채번
│   ├── api/          컨트롤러 · 요청·응답 DTO (키 · 번호 형식은 global/validation 의 Identifiers 를 부른다)
│   ├── application/  처리 순서 · 트랜잭션 경계 — Service · Writer · Reader · 중복 키 재시도
│   └── domain/       원장 행(엔티티) · 리포지토리 · 채번
└── control/        Mock 조작 — 설정 · 결함 주입 · 초기화 API
    ├── api/          컨트롤러 · 요청·응답 DTO · 초기화 장벽 필터(등록 · 취소 POST 만)
    └── application/  초기화 · 초기화 장벽(진행 중인 등록 · 취소가 있으면 409 RESET_BUSY)
```

**패키지는 같이 바뀌는 것끼리 묶는다.** `api` 는 [api.md](api.md) 가 바뀔 때, `application` 은 아래 처리
순서가 바뀔 때, `domain` 은 [schema.sql](schema.sql) 이 바뀔 때 고치는 곳이다. 의존은 `api → application →
domain` 방향으로만 흐르고 거꾸로 가리키지 않는다. 위층은 아래층 어디든 쓸 수 있어 응답 DTO 는 엔티티
(`Registration`)를 직접 읽는다. 거꾸로는 안 되므로 처리 층은 요청 DTO 대신 `RegisterCommand` 를 받는다.
`control` 에는 자기 테이블이 없어 `domain` 이 없다 — 설정 · 결함 보관소는 등록 파트와의 계약이라
`global/chaos` 에 있고, 초기화가 지우는 것은 등록 원장이다.

층 밖에서 쓰지 않는 클래스는 package-private 으로 숨긴다. 여러 파트가 같은 규칙을 써야 하는 것만 `global` 에 공개로
둔다 — 키 형식 규칙(`Identifiers`)은 제어 파트도 결함을 거는 키에 써야 해서 `global/validation` 에, 중복 키 재시도
(`DuplicateKeyRetry` · `DuplicateKey`)는 럭키드로우 응모도 같은 상한으로 다시 시도해야 해서 `global/persistence` 에
있다(NV-505 에서 `registration.application` 에서 옮김). 결함 키도 이 규칙으로 검사한다(`FaultController`) — 등록이 받지 않는 키에 걸면 영원히 발동하지 않는다.

지연·실패 주입은 **등록 처리 안에서** 부른다. 필터에 두면 조회·취소에도 걸리는데, 과제가 취소를
"항상 성공" 으로 가정하고 정합성 조회까지 느려지기 때문이다.

## 등록 처리 순서

```
[트랜잭션 밖]  1. 지연 대기   2. 실패율 판정(커밋 전이라 저장 없음)     ← FailureInjector
               2-1. 느린 성공 결함이 걸린 키면 정한 시간 기다리기(잠금 전)  ← FaultHook
[트랜잭션 안]  3. 키 행 잠금   4. 취소 표식이면 KEY_CANCELED            ← RegistrationWriter
               5. 이미 등록됐으면 재생 / 내용 다르면 422
               6. 커밋 후 201
               중복 키(1062)면 트랜잭션을 나와 3단계부터 다시
[커밋 후]      7. 결함이 걸려 있으면 응답 없이 연결 끊기               ← FaultHook · ConnectionDropper
```

**대기는 트랜잭션 밖에서 한다.** 트랜잭션 안에서 기다리면 DB 커넥션이 그만큼 묶여 풀이 마른다.
지연 2초 · 풀 20개면 10 RPS 에서도 마른다. 그래서 `RegistrationService` 에는 `@Transactional` 을
붙이지 않고, 한 번의 시도만 `RegistrationWriter` 에서 트랜잭션으로 묶는다.

같은 새 키로 등록과 취소가 동시에 들어오면 한쪽이 중복 키(1062)를 받는다. **오류가 아니라
정상 분기다** — 그 트랜잭션은 롤백 전용이 되므로 트랜잭션을 나와 새로 3단계부터 한다. 행이 없을
때는 잠금이 걸리지 않고(READ COMMITTED 에는 갭 락이 없다) INSERT 에서 직렬화되는 것이 전제라,
**격리 수준은 트랜잭션 경계에 선언한다** — `RegistrationWriter.attemptOnce` · `CancellationWriter.cancelOnce` ·
`RegistrationReader.findByKey` 의 `@Transactional(isolation = READ_COMMITTED)`. 설정 파일에만 두면 사본을
놓친 사람과 RDS(기본 REPEATABLE READ)에서 빠지고, 그러면 서로 다른 새 키의 동시 등록도 갭 락끼리 막혀
교착한다(리뷰 재현: 동시 50건 × 5회 중 85% 가 500). 그래서 동시성 시험은 서버를 RR 그대로 두고 돈다.
교착(1213)은 재시도하지 않고 500 으로 낸다. 저장된 것이 없고 워커가 by-key 로 확인한 뒤 다시 보낸다.

키 조회(by-key)는 **공유 잠금(`FOR SHARE`)** 으로 읽는다. 진행 중인 등록이 커밋될 때까지 기다려야
트랜잭션 안의 등록을 놓치지 않는다. 지연은 락 밖이라 등록 지연을 크게 잡아도 키 조회가 그만큼
기다리지 않고, 지연 중인 등록은 404 로 보인다. 그래서 404 는 "지금 없음" 이지 "앞으로도 없음" 이 아니다
(명세의 포기 규칙).

취소도 같은 구조다. `CancellationService`(트랜잭션 밖)가 중복 키면 다시 시도하고, 한 번의 시도는
`CancellationWriter` 가 키 행을 잠근 트랜잭션으로 한다. 1062 재시도 루프는 `DuplicateKeyRetry` 하나를
등록 · 럭키드로우 응모와 같이 쓴다. 지연 중인 등록은 아직 잠금을 쥐지 않았으므로 취소는 기다리지 않고 표식을 남기고,
뒤늦게 진행된 등록은 그 표식에 막혀 `KEY_CANCELED` 가 된다.

번호로 등록을 찾을 때(번호 조회 · 번호만 받은 취소)는 키 조회와 달리 잠금 없이 읽는다. 번호는 등록
트랜잭션 안에서 만들어지고 201 은 커밋한 뒤에 나가므로, 워커가 아는 번호는 모두 이미 커밋된 번호다.
진행 중인 등록의 번호를 워커가 알 방법이 없으니 "못 본 채 404" 가 생기지 않는다.

## 등록 파트 ↔ 제어 파트 계약

등록 처리(`registration/`)가 제어 파트(`global/chaos/`)에서 가져다 쓰는 것은 이 다섯뿐이다.
**시그니처나 아래 약속을 바꾸려면 양쪽이 합의한다.**

| 쓰는 것 | 언제 | 약속 |
| --- | --- | --- |
| `ConfigProvider.snapshot()` | 1단계 전 | 이 시도가 끝까지 쓸 설정을 얼려 준다. 버전은 `X-Mock-Config-Version` 으로 나간다 |
| `FailureInjector.apply(snapshot)` | 1~2단계 | **트랜잭션 밖**에서 부른다. 실패는 `MockException`, `TIMEOUT` 은 응답 없이 끝난다. `MIXED` 의 본문 없는 500 · HTML 502~504 는 응답을 직접 쓰고 `InjectedResponseException` 으로 멈춘다 — 등록 쪽은 잡지 않는다(`ResponseLostAdvice` 가 삼킨다) |
| `FaultHook.holdBeforeCommit(key)` | 2단계 뒤 · 3단계 전 | **트랜잭션 밖 · 키 행 잠금 전**에 부른다(지연 주입 뒤, 중복 키 재시도 앞). 느린 성공 결함이 걸린 키면 **결함을 먼저 꺼내고** 정한 시간 기다린 뒤 돌아온다 — 그사이 같은 키 재시도는 기다리지 않는다. 기다린 시간은 `X-Mock-Injected-Latency-Ms` 에 더한다. 중단되면 500(저장 없음) |
| `FaultHook.consumeResponseLost(key)` | 7단계 | **새로 커밋한 직후에만** 부른다. true 면 결함을 소비한 것이다 |
| `ConnectionDropper.drop(reason)` | 7단계 | 응답 없이 연결을 붙잡다 끝낸다. **정상 반환하지 않는다** — 항상 `ResponseLostException` |

`drop()` 이 정상 반환하지 않는다는 것도 계약이다. 등록 처리가 여기에 기대어 뒤이은 201 응답을 쓰지 않는다.

`X-Mock-Injected-Latency-Ms` 응답 헤더는 **`FailureInjector.apply` 가 대기 직전에 직접 붙인다**(`RequestContextHolder`).
등록 쪽 코드에는 이 헤더가 보이지 않지만, 위 시그니처를 바꾸지 않으려고 고른 방식이다. 1단계라 원장을 보기 전이므로
그 뒤 결과가 무엇이든(201 · 재생 · 409 · 422 · 500) 같이 나간다(api.md 등록 Response).

주입 표식 헤더 `X-Mock-Injected-Failure` 와 실패 주입 로그(`실패 주입 HTTP_503 key=…`)도 같은 방식이다. 키가 계약에 없어
`apply` 가 지금 요청의 `Idempotency-Key` 헤더에서 읽는다 — 등록 입구가 형식을 이미 확인한 뒤다.

## 설정 · 결함 공유 (여러 대, NV-467)

위 계약(`ConfigProvider` · `FaultHook`)은 그대로다. 그 뒤에서 **어디에 두는지만** `mock.shared-state` 로 갈린다. 그래서 등록
파트 · 설정 API · 결함 API · 초기화 코드는 바뀌지 않았다.

| | memory (기본) | redis |
| --- | --- | --- |
| 설정 | `MockConfigStore` 메모리 | `MockConfigStore` 메모리 + `ConfigSync`(`RedisConfigSync`) 가 맞춘다 |
| 결함 | `FaultStorage` = `MemoryFaultStorage` | `FaultStorage` = `RedisFaultStorage` |

**설정** — 요청 처리는 늘 각 서버의 메모리 값을 읽는다(요청마다 Redis 를 보지 않는다). Redis 에는 해시 `mock:config`
(`version` · `changeId` · `body` · `appliedAt`) 하나를 둔다. 설정 하나는 **버전 + 변경 ID** 로 가린다 — 바꿀 때마다 새 변경
ID 를 붙인다.

| 일 | 방법 | 이유 |
| --- | --- | --- |
| 기동 | Lua 로 "없으면 기본값을 넣고, 저장된 것을 돌려준다" | 두 서버가 동시에 처음 떠도 둘 다 기본값을 쓰지 않는다. 재기동한 서버가 공유 설정을 덮지 않는다 |
| 변경 | Lua 로 "버전 = max(저장된 버전, 이 서버가 아는 버전) + 1 · 새 변경 ID 와 저장 · `mock:config:changed` 로 `버전:변경ID` 알림" | 동시에 바꿔도 버전이 겹치지 않는다. Redis 가 설정을 잃어도(재시작) 버전이 거꾸로 가지 않는다 — 거꾸로 가면 다른 서버가 새 설정을 옛것으로 보고 버린다 |
| 알림 받기 | 알린 것이 더 새 버전이거나, **같은 버전의 다른 변경**이면 다시 읽어 바꾼다 | 수 ms 안에 맞는다 |
| 안전망 (10초) | 버전 · 변경 ID 만 읽는다. Redis 가 더 새것이거나 같은 버전의 다른 변경이면 다시 읽고, 없거나 더 옛것이면 마지막 설정을 그 버전 · 변경 ID 그대로 다시 올린다("없거나 더 낮을 때만" 덮어써 가장 새 버전이 남는다) | 구독이 늦게 붙거나 연결이 끊긴 사이의 알림은 다시 오지 않는다. 옛 버전을 아는 서버가 먼저 다시 올려도 새 버전을 아는 서버가 바로잡는다 |
| 받기 | 더 작은 버전은 받지 않는다 | 알림 순서가 뒤바뀌어 옛 버전이 늦게 와도 되돌리지 않는다 |

**왜 변경 ID 까지 보나.** Redis 가 설정을 잃은 틈에 늦어 있던 서버(버전 1)가 바꾸면 그 설정도 버전 2 가 되어, 이미 버전 2 를
아는 서버와 번호가 같아진다. 버전만 보면 그 서버는 옛 버전 2 를 계속 쓴다. 같은 버전이어도 변경 ID 가 다르면 **Redis 에 있는
것을 따른다** — Redis 가 기준이다.

알림을 받는 쪽은 Spring Boot 4 가 자동으로 만드는 `redisMessageListenerContainer`(가상 스레드)를 쓴다. 따로 만들면 둘이
되어 기동하지 못한다.

**결함** — 해시 `mock:faults` 하나(칸 = 외부 키, 값 = `종류|대기ms`). 꺼내기는 Lua 로 "종류가 맞으면 지우고 돌려준다"
를 한 번에 해 여러 서버가 같은 키를 동시에 꺼내도 한 번만 발동한다(시험: 서버 3대 × 30회 동시 → 1회). 알림은 없다 —
결함은 그 키의 등록을 받은 서버가 그때 꺼내 보면 된다. 그래서 설정과 달리 **등록마다 Redis 를 본다**(결함이 없어도
느린 성공 확인 1번 · 새로 커밋하면 응답 유실 확인 1번). 둘 다 DB 트랜잭션 밖이라 Redis 가 느려도 DB 커넥션은 묶이지 않는다.
초기화는 키 하나 지우기다.

**Redis 장애** — 설정은 마지막으로 받은 값으로 요청을 계속 처리하고, 바꾸기는 실패를 알린다(몇 대만 바뀌는 어긋남을
막는다). 결함 꺼내기는 결함 없음으로 넘어간다 — 결함은 시험용 장치라 Redis 때문에 등록이 실패하면 안 된다. 결함 걸기 ·
초기화는 실패를 알린다(걸렸다고 믿고 시험하면 안 되므로).

넘어가는 것은 **Redis 명령 시간 초과가 지난 뒤**다. 연결 거부는 바로 실패하지만, 멈춘 Redis(장애 조치 중 등)는 답을
기다린다. 시간 초과를 정하지 않으면 Lettuce 기본 60초라 그동안 모든 등록이 멈춘다 — 그래서 `spring.data.redis.timeout`
을 500ms, `connect-timeout` 을 1s 로 둔다(설정 예시 · 운영 문서). 새 등록 하나는 Redis 를 두 번 보므로 시간 초과도 두 번
쌓인다 — 멈춘 Redis 면 약 1.1초(500ms × 2, 시험이 2초 안을 확인), 연결부터 안 되면 약 2초(1s × 2). 둘 다 워커 읽기 5초 안이다.

## 스키마

`docs/schema.sql` 이 정본이다. Hibernate 가 테이블을 만들면 CHECK 제약이 빠지므로 `ddl-auto` 는
`validate` 로 둔다. 칸을 바꿀 때는 엔티티와 이 파일을 함께 고치고 ERD 담당에게 알린다.

시각 칸은 `Instant` 다. 시간대 없는 `LocalDateTime` 을 쓰면 Hibernate 의 `jdbc.time_zone` 과 PC
시간대에 따라 DB 에 9시간 밀린 값이 들어간다. 응답은 UTC `Z` 이고 밀리초까지다.

## 시험

```bash
./gradlew build
```

시험에서는 `src/test/resources/application.yml` 이 H2 인메모리로 지연·실패율을 0 으로 덮어쓴다.

**H2 는 컨텍스트 로딩과 순서대로 부를 때의 판정용이다.** 멱등 등록과 키 조회는 잠금 · 중복 키 동작에
기대므로 H2 에서 통과해도 MySQL 에서 통과한다는 보장이 없다. 그런 시험은 `RegistrationConcurrencyTest`
처럼 Testcontainers 로 진짜 MySQL 8.4 를 띄워 돌린다. 스키마는 `docs/schema.sql` 을 그대로 넣는다.

**Docker 가 없으면 MySQL 시험만 조용히 건너뛴다.** 로컬 빌드는 초록색이라 놓치기 쉽다. 동시성을 건드렸으면
Docker 를 켜고, 결과에서 건너뜀이 0 인지 본다.

**CI 는 건너뛴 시험이 하나라도 있으면 실패한다**(`.github/workflows/build.yml`). 로컬 빌드 성공과 CI 성공은
다르다 — 로컬은 Docker 가 꺼져 있어도 초록이지만 CI 는 MySQL 시험까지 돌아야 초록이다. **`@Disabled` 도
건너뜀이라 CI 가 막는다.** 의도한 것이다. 시험을 꺼야 하면 끄지 말고 고치거나, 이유를 PR 에 적고 지운다.

**부하 시험은 빌드와 따로 돈다**(`./gradlew loadTest`, Mock 을 먼저 띄운다). 판정 기준과 결과는
[load-test.md](load-test.md), 순서대로 돌리는 방법은 [operations.md](operations.md) 3장에 있다.

구현 시 최소한 다음은 검증한다.

- 같은 키·같은 내용의 재요청이 같은 예약번호를 반환하는가
- 같은 키·다른 내용을 422 로 거절하는가
- 같은 키로 동시에 10건을 보내도 등록이 1건인가 (**100회 반복**해야 의미가 있다)
- 커밋 전 등록이 있을 때 키 조회가 기다렸다가 찾는가
- 취소를 반복해도, 미등록 키를 취소해도 성공으로 처리하는가
- 취소 뒤 늦게 도착한 등록이 `KEY_CANCELED` 로 막히는가
- 실패율 100% 에서도 설정 API 로 되돌릴 수 있는가

### 시험을 쓸 때 알아둘 것

| 할 것 | 이유 |
| --- | --- |
| **고친 것을 일부러 빼고 돌려 시험이 깨지는지 본다** | 통과만 보면 아무것도 증명하지 않는 시험을 놓친다. 동시성 버그는 100회 반복으로도 안 걸리는 게 있어, 그 상황을 직접 만드는 시험이 따로 필요하다 |
| 설정 · 결함을 바꾼 시험은 `@AfterEach` 로 되돌린다 | 스프링이 시험 컨텍스트를 재사용해 앞 시험이 바꾼 설정이 다음 시험 클래스로 샌다(팀 규칙) |
| 고정 키 대신 매번 새 키를 쓴다 | H2 인메모리 DB 는 컨텍스트가 달라도 같은 JVM 에서 공유된다. 다른 클래스가 같은 키로 만든 행이 남아 있다 |
| 시간대 버그는 DB 에 적힌 글자로 본다 | 앱 안에서는 읽을 때 되돌아가 응답이 멀쩡하다. 시험 JVM 은 `Asia/Seoul` 로 고정해 UTC 기계에서도 잡히게 한다(`build.gradle`) |
| 응답 유실은 실제 소켓으로 본다 | MockMvc 에는 소켓이 없어 연결 끊기가 500 으로 보인다. 워커가 정말 타임아웃을 겪는지는 `ConnectionDropperE2eTest`(`RANDOM_PORT`)가 본다 |
| 시험 패키지는 main 과 같게 나누고, package-private 을 단정하는 시험은 그 층에 둔다 | 숨긴 클래스는 같은 패키지에서만 보인다. `RegistrationApiTest` 는 이름과 달리 `registration.application` 에 있다 — 숨겨 두었던 중복 키 재시도(`DuplicateKeyRetry`)를 직접 단정하던 자리다. 재시도는 NV-505 에서 `global/persistence` 로 옮겨 공개가 됐고 시험은 자리를 그대로 둔다. 앱 전체를 띄우는 시험이라 패키지가 시험 범위를 바꾸지는 않는다 |

### 구현할 때 알아둘 것

- 스프링 부트 4 라 Jackson 3 다. 패키지가 `tools.jackson` 이라 인터넷 예제(`com.fasterxml.jackson.databind`)를
  베끼면 컴파일이 안 된다. 애너테이션만 옛 이름이다
- 모두에게 같아야 하는 설정은 `application.yml` 이 아니라 코드에 둔다. `application.yml` 은 각자 예제를
  복사해 쓰는 파일이라 예제에 한 줄 넣어도 이미 복사한 사본에는 들어가지 않는다(`StrictJsonConfig`)
