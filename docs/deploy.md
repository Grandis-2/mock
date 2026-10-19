# 빌드 · 배포

`main` 에 들어간 커밋만 이미지가 되고, 운영 DB(NCP `external_mock`)에 마이그레이션을 먼저 적용한 뒤 ECS 의 Mock 이
그 이미지로 다시 뜬다. be 의 release 와 같은 방식이다(ECR 에 올리고 `force-new-deployment`).

## 워크플로

하는 일은 `_` 로 시작하는 파일에 역할별로 있고, `build.yml` · `release.yml` 은 순서만 정한다.
권한은 파일마다 그 역할에 필요한 만큼만 있다. AWS 자격을 받는 것은 `_migrate` · `_deploy` 둘뿐이다.

| 파일 | 역할 | 저장소 권한 | AWS |
| --- | --- | --- | --- |
| `build.yml` | PR · dev 검증. `_build` · `_database` · `_image` 를 부른다 | 없음(부르는 job 에만 준다) | — |
| `release.yml` | main 배포. 다섯을 순서대로 부른다 | 없음(부르는 job 에만 준다) | — |
| `_build.yml` | 컴파일 · 시험 → 실행 jar | `contents: read` | — |
| `_database.yml` | 마이그레이션 검증(`db/verify.sh`) | `contents: read` | — |
| `_image.yml` | 이미지 빌드 · AOT 학습(`docker/train.sh`) · 취약점 훑기 | `contents: read` | — |
| `_migrate.yml` | 운영 DB 에 마이그레이션 적용 | `contents: read` · `id-token: write` | 마이그레이션 저장소에 올리기 · 태스크 띄우기 |
| `_deploy.yml` | 이미지 올리기 · ECS 다시 배포 | `contents: read` · `id-token: write` | Mock 저장소에 올리기 · 서비스 배포 |

```
release:  build ──→ image ──┬─→ migrate ──→ deploy
          database ─────────┘
```

- 이미지는 시험을 통과한 그 jar 로 만든다. `_image` 가 띄워 보고 훑은 그 이미지를 `_deploy` 가 그대로 올린다.
- `_image` 는 마이그레이션 이미지가 만든 표로 Mock 을 띄워 등록 · 조회 · 취소 요청의 응답 코드까지 본다. 표와 엔티티가
  다르면(`ddl-auto: validate`) 여기서 멈춘다 — 운영 DB 에 적용하기 전이다.
- 배포는 새 태스크가 자리 잡을 때까지 기다리고, 그 태스크가 이번에 올린 이미지(digest)로 도는지 확인한다.
  운영은 Mock 을 여러 대(2 ~ 5대, 인프라 `mock/service.yml`)로 두고 rolling 으로 배포한다.
- 액션은 커밋 SHA 로, 컨테이너 이미지는 digest 로 고정한다.

## 이미지

`docker/Dockerfile`(기반) → `docker/train.sh`(학습) → `docker/Dockerfile.aot`(캐시 얹기) 순서로, be 와 같다.
OpenTelemetry Java 에이전트(`/otel/javaagent.jar`)는 싣기만 한다. 켜는 것은 인프라의 작업 정의다.
헬스 체크는 관리 포트 `9081` 의 `/actuator/health/readiness` 다(앱 기본값 `MockDefaults`). 학습도 이 경로로 기동을 확인한다.

```bash
./gradlew bootJar && bash docker/train.sh     # nova-mock:local
```

설정은 이미지에 없다. 인프라의 작업 정의가 `SPRING_APPLICATION_JSON` 과 DB 계정을 넣는다.

## 스키마 변경

`db/migrations` 에 `V<yyyyMMddHHmm>__<소문자_설명>.sql` 을 더한다. 운영 DB 의 표는 이 마이그레이션이 정한다 —
`docs/schema.sql` 은 같은 표를 한 파일로 둔 것으로 로컬 compose 와 MySQL 시험(Testcontainers)이 쓴다.
마이그레이션과 맞춰 보는 검사는 없으니 표를 바꾸면 둘 다 고친다.

```bash
bash db/verify.sh
```

- 이름 · 버전 규칙을 보고, 빈 MySQL 8.4 에 모두 적용해 본다.
- 합쳐진 마이그레이션은 고치지 않는다. 이미 적용한 DB 와 체크섬이 어긋나 다음 배포가 멈춘다. 새 파일을 더한다.
- `shop` 은 be 가 관리한다. 여기서는 `external_mock.flyway_schema_history` 만 쓴다.
- 마이그레이션은 Mock 이 떠 있는 채로 적용된다. 옛 코드가 새 표로도 돌 수 있게 쓴다(칸을 지우거나 형을 바꾸는 변경은
  두 번에 나눈다). NV-328 처럼 그럴 수 없는 변경은 `docs/operations.md` 0장의 순서로 사람이 맞춘다.

### 운영 DB 에 처음 적용할 때

`baselineOnMigrate` 를 껐다. `external_mock` 에 표가 이미 있으면 첫 마이그레이션이 "표가 이미 있다" 로 실패한다 —
옛 정의의 표를 조용히 기준선으로 삼지 않기 위해서다. 원장은 Mock 기록이라 지워도 되므로, 표를 지운 뒤 release 를 다시 돌린다.

## 인프라가 준비할 것

이 저장소의 워크플로는 아래가 있다고 보고 돈다. 이름은 `release.yml` 에만 적혀 있다.
지금은 모두 있다 — 첫 release(2026-10-14)가 운영 DB 에 마이그레이션을 적용했고, 다음 release(2026-10-15, NV-533)가 배포까지 마쳤다.

| 무엇 | 이름 | 지금 |
| --- | --- | --- |
| GitHub 변수 · 시크릿 | 변수 `AWS_REGION` · 시크릿 `AWS_ROLE_ARN` | 있다 |
| 배포 역할의 신뢰 대상 | 이 저장소의 `main` — `repo:Grandis-2@336932992/mock@1365107413:ref:refs/heads/main` | 있다 |
| Mock 이미지 저장소 | `nova/mock` | 있다(`application/mock/service.yml` 로 생긴다) |
| Mock 서비스 | `nova-prod` 클러스터의 `nova-prod-mock` | 있다(`deploy: true`). 없으면 배포를 건너뛰고 이미지만 올린다 |
| 마이그레이션 이미지 저장소 | `nova/mock-migration` | 있다 |
| 마이그레이션 태스크 정의 | be 의 `nova-prod-migration` 과 같되 이미지와 접속 주소(`/external_mock`)만 다르다 | 있다 |
| 태스크를 띄울 값 | SSM `/nova-prod/mock-migration/run-task` (be 의 `/nova-prod/migration/run-task` 와 같은 모양) | 있다 |
| 배포 역할의 권한 | 위 두 저장소에 올리기 · 위 태스크 띄우기 · 위 파라미터와 그 로그 읽기 | 있다 |

실행 역할 · 보안 그룹 · 서브넷 · `nova_migrator` 계정은 be 의 마이그레이션 태스크 것을 그대로 쓰면 된다.
태스크 정의만 따로 둔다 — 이미지와 접속 주소가 태스크 정의에 들어 있고, 띄울 때 이미지는 바꿀 수 없다.

클러스터가 있는데 SSM 파라미터가 없으면 release 는 `migrate` 에서 실패하고 아무것도 올리지 않는다.
마이그레이션 없이 Mock 을 올리면 표가 없어 뜨지 않기 때문이다.
