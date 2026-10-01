# mock

외부 예약 시스템(통신사 예약 서버)을 대신하는 Mock 서버. 사전예약 시스템([be](https://github.com/Grandis-Nova/be))의
워커가 HTTP 로 부른다. 평균 500ms 지연과 5% 실패를 재현하고, 시연 중 재기동 없이 바꿀 수 있다.

프로젝트 전체 소개 · 업무 정책 · 브랜치 · 커밋 규칙은 [be README](https://github.com/Grandis-Nova/be#readme) 를 따른다.

## 실행

Java 25 와 Docker 가 필요하다.

```bash
cp .env.example .env                                                               # DB 계정 · 포트
cp src/main/resources/application.yml.example src/main/resources/application.yml   # 접속 정보
docker compose up -d mysql       # external_mock database (로컬 전용, 포트 3307)
./gradlew bootRun                # http://localhost:8081
```

**그대로 복사하면 그대로 뜬다.** 접속 정보가 `.env`(컨테이너가 만드는 계정)와 `application.yml`(앱이
접속하는 계정) 두 군데에 나뉘어 있으니, 포트나 비밀번호를 바꾸려면 **양쪽을 같이** 고친다.

```bash
curl -X POST localhost:8081/external/reservations \
  -H 'Idempotency-Key: 9f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f' -H 'Content-Type: application/json' \
  -d '{"customerId":1001,"productId":12,"sku":"SM-G999-256-BLK"}'
curl localhost:8081/external/reservations/by-key/9f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f
curl -X PUT localhost:8081/external/config -H 'Content-Type: application/json' \
  -d '{"registerLatencyMs":2000,"failureRate":0.5,"failureMode":"HTTP_5XX"}'
```

## 시험

```bash
./gradlew build
```

**Docker 가 켜져 있어야 MySQL 시험(Testcontainers)까지 돈다.** 꺼져 있으면 그 시험만 조용히 건너뛰고
빌드는 초록색이다. 동시성을 건드렸으면 결과에서 건너뜀이 0 인지 본다.

## API — 8개

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| POST | `/external/reservations` | 예약 등록 (멱등) |
| GET | `/external/reservations/{externalNumber}` | 번호로 단건 조회 |
| GET | `/external/reservations/by-key/{externalKey}` | 키로 등록 상태 조회 |
| POST | `/external/cancellations` | 예약 취소 |
| GET · PUT | `/external/config` | 지연·실패 설정 조회 · 변경 |
| POST | `/external/faults` | 결함 주입 (응답 유실) |
| POST | `/external/reset` | 기록 초기화 |

## 꼭 알아야 할 계약

- 워커는 HTTP 상태가 아니라 `errorCode` 로 분기한다. **5xx · 타임아웃은 미등록의 증거가 아니다** —
  재시도 전에 키 조회로 확인한다. 키 조회의 404 는 "지금 등록이 없다" 는 뜻이라 같은 키로 다시 보낸다.
  지연 중인 등록은 404 로 보이므로, 그만둘 때는 같은 키로 먼저 취소한다
- 멱등 키는 본 서비스의 `preorder_token` 이다. 대소문자를 구별하고 영문 · 숫자 · `. _ -` 로 된 1~100자다
- 요청 본문에 계약에 없는 필드가 있으면 400 이다
- 지연 · 실패는 등록에만 주입한다. 조회 · 취소 · 설정에는 없어서 실패율 100% 에서도 되돌릴 수 있다
- **프로세스 1개로 띄운다.** 설정 · 결함이 메모리라 2개 이상이면 갈린다
- 정합성 검사는 API 가 아니라 `external_mock` 스키마를 읽기 전용으로 직접 조회한다

## 문서

| 문서 | 내용 |
| --- | --- |
| [docs/api.md](docs/api.md) | **API 명세 정본** — 요청 · 응답 · 오류 계약 · 확인 시나리오 |
| [docs/design.md](docs/design.md) | 처리 순서 · 동시성 · 등록 ↔ 제어 파트 계약 · 시험 방법 |
| [docs/operations.md](docs/operations.md) | 운영 · 시연 — Mock 조절 · 장애 재현 · 부하 실행 · 결과 확인 |
| [docs/load-test.md](docs/load-test.md) | 부하 시험 — 판정 기준과 결과 |
| [docs/schema.sql](docs/schema.sql) | 스키마 정본 |
