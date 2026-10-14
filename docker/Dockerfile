# Mock 기반 이미지. jar 는 CI 가 시험까지 돌려 만든 것을 받는다 — 여기서 gradle 을 다시 돌리지 않는다.
# be 의 docker/Dockerfile 과 같은 구조다. 이 이미지는 AOT 캐시가 없는 기반이고, 학습(docker/train.sh)이 이 이미지를 띄워
# 학습한 뒤 그 위에 Dockerfile.aot 로 캐시를 얹는다 — 그래서 학습한 JDK 와 실행할 JDK 가 같다. 올리는 것은 캐시를 얹은 쪽이다.
#
# 설정은 싣지 않는다. application.yml 은 추적하지 않고 jar 에도 없다 — 인프라의 작업 정의가 SPRING_APPLICATION_JSON 과
# 접속 계정을 넣는다 (nova-infra application/mock). 설정 없이 띄우면 가상 스레드 확인(VirtualThreadGuard)에서 멈춘다.
#
# 베이스는 digest 로 고정한다. 태그만 쓰면 같은 커밋이 날마다 다른 이미지가 된다. 올릴 때는 태그와 digest 를 함께 바꾼다.
ARG BASE_IMAGE=eclipse-temurin:25-jre@sha256:fcd7fd7b387f94bb2ac461478a7436ad8e349924c374ea8313919624dceae636

# OpenTelemetry Java 에이전트. jar 만 싣고 켜지 않는다 — 켜는 쪽은 nova-infra 의 작업 정의(JDK_JAVA_OPTIONS=-javaagent:/otel/javaagent.jar 와 OTEL_*)다.
# JAVA_TOOL_OPTIONS 로 켜지 않는다 — 아래 ENV 의 힙 옵션이 그 변수에 있어, 작업 정의가 덮으면 힙 옵션이 조용히 사라진다.
# 에이전트를 붙여도 AOT 캐시가 받아지도록 Dockerfile.aot 와 학습(train.sh)이 java.instrument 모듈을 늘 더한다.
# be 와 같은 버전 · digest 다. 올릴 때는 be 와 함께 올린다.
FROM ghcr.io/open-telemetry/opentelemetry-operator/autoinstrumentation-java:2.32.0@sha256:9dad1c6e3e2ecee48fc164a19bcfde703510bee972c8cb421689fde89b5f89e9 AS otel

# jar 를 풀어 둔 형태(app.jar + lib/)로 싣는다. fat jar 의 중첩 jar 에서 읽은 클래스는 JVM AOT 캐시에 들어가지 않는다.
FROM ${BASE_IMAGE} AS extract
WORKDIR /build
COPY build/libs/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --destination /extracted

FROM ${BASE_IMAGE}
WORKDIR /app

# 루트로 돌리지 않는다.
RUN groupadd --system spring && useradd --system --gid spring spring
USER spring

COPY --from=extract --chown=spring:spring /extracted/ /app/
COPY --from=otel /javaagent.jar /otel/javaagent.jar

# 컨테이너에 준 메모리를 기준으로 힙을 잡는다. ECS 태스크 메모리를 바꾸면 따라간다.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# 8081 서비스 · 9081 관리(헬스 체크 /actuator/health/readiness, MockDefaults)
EXPOSE 8081 9081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
