package com.grandis.nova.mockapi.global.chaos;

import com.grandis.nova.mockapi.global.config.MockProperties;
import com.grandis.nova.mockapi.global.error.ErrorCode;
import com.grandis.nova.mockapi.global.error.MockException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 설정된 지연을 평균으로 기다리고, 확률에 걸리면 일시 실패를 만든다.
 *
 * <p>느리고 가끔 실패하는 외부 시스템을 흉내 내는 부분이다. 요구사항이 Mock 동작으로 정한
 * <b>"평균 500ms 지연 · 5% 확률 실패"</b> 가 여기서 나온다.
 *
 * <p>지연은 <b>모든 요청</b>이 겪고, 실패는 <b>확률에 걸린 일부</b>만 겪는다. 둘은 배타적이지
 * 않다 — 실패한 요청도 지연은 이미 겪은 뒤다.
 *
 * <p>DB 를 건드리지 않는다. 트랜잭션 밖에서 불리므로 여기서 커넥션을 빌리면 대기 시간만큼
 * 커넥션이 묶여 풀이 마른다.
 */
@Component
public class DefaultFailureInjector implements FailureInjector {

    /** 이 요청에 실제로 뽑힌 지연(ms). 부하 판정이 요청마다 오버헤드를 계산하는 데 쓴다. */
    public static final String INJECTED_LATENCY_HEADER = "X-Mock-Injected-Latency-Ms";

    /**
     * 주입한 실패의 종류({@link MixedResponse} 이름 · {@code TIMEOUT}). 주입한 실패 응답에만 붙는다.
     *
     * <p><b>시험용 표식이다.</b> 본문 없는 500 · HTML 502~504 에는 Mock 의 문구가 없어 부하 판정이 "주입한 실패" 와
     * "Mock 의 진짜 오류" 를 문구로 가를 수 없다. 이 헤더로 가르고, be 로그와 대조할 때도 쓴다. 실제 앞단 장비는 이 헤더를
     * 보내지 않으므로 <b>워커는 이 헤더로 분기하지 않는다.</b>
     */
    public static final String INJECTED_FAILURE_HEADER = "X-Mock-Injected-Failure";

    private static final Logger log = LoggerFactory.getLogger(DefaultFailureInjector.class);

    private final ConnectionDropper dropper;
    private final MockProperties properties;

    public DefaultFailureInjector(ConnectionDropper dropper, MockProperties properties) {
        this.dropper = dropper;
        this.properties = properties;
    }

    @Override
    public void apply(ConfigSnapshot snapshot) {
        sleep(drawn(snapshot.registerLatencyMs(), snapshot.latencyTail()));

        if (!rolledFailure(snapshot.failureRate())) {
            return;
        }

        switch (snapshot.failureMode()) {
            // 워커는 이것을 "일시 실패" 로 읽고 그대로 재시도한다. 커밋 전이라 아무것도 저장되지 않는다.
            case HTTP_5XX -> respond(MixedResponse.HTTP_500);
            // 워커는 결과를 모른다(UNKNOWN). by-key 조회로 확인한 뒤에 재시도해야 한다.
            case TIMEOUT -> {
                mark("TIMEOUT");
                dropper.drop("failureMode=TIMEOUT 주사위에 걸렸다");
            }
            // 설정한 종류 중 하나를 같은 확률로. 스냅샷이 MIXED 면 목록이 비어 있지 않다(ConfigSnapshot).
            case MIXED -> respond(pick(snapshot.mixedResponses()));
        }
    }

    private static MixedResponse pick(List<MixedResponse> candidates) {
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    /**
     * 고른 실패 응답을 보낸다. 정상 반환하지 않는다.
     *
     * <p>{@code errorCode} 가 있는 500 은 Mock 의 오류 형식이라 오류 처리기로 보낸다. 나머지(본문 없는 500 · HTML)는 그
     * 형식이 아니라 직접 쓴다({@link InjectedResponseException}).
     */
    private static void respond(MixedResponse kind) {
        mark(kind.name());
        if (kind == MixedResponse.HTTP_500) {
            throw new MockException(ErrorCode.UPSTREAM_UNAVAILABLE);
        }
        HttpServletResponse response = currentResponse();
        response.setStatus(kind.status());
        String html = kind.html();
        try {
            if (html == null) {
                response.setContentLength(0);
            } else {
                byte[] body = html.getBytes(StandardCharsets.UTF_8);
                response.setContentType("text/html;charset=UTF-8");
                response.setContentLength(body.length);
                response.getOutputStream().write(body);
            }
            response.flushBuffer();
        } catch (IOException e) {
            // 클라이언트가 먼저 끊었다는 뜻이다. 저장된 것이 없으니 다를 것이 없다.
            log.debug("주입한 실패 응답을 쓰는 중 입출력 오류 — 이미 끊긴 연결로 본다", e);
        }
        throw new InjectedResponseException(kind.name() + " 를 보냈다");
    }

    /** 주입한 실패의 종류를 표식 헤더로 남긴다. 응답이 확정되기 전이라 어느 모양에도 실린다. */
    private static void mark(String kind) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servlet
                && servlet.getResponse() != null) {
            servlet.getResponse().setHeader(INJECTED_FAILURE_HEADER, kind);
        }
    }

    private static HttpServletResponse currentResponse() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servlet
                && servlet.getResponse() != null) {
            return servlet.getResponse();
        }
        throw new IllegalStateException("서블릿 요청 안에서만 쓸 수 있다");
    }

    /**
     * 주사위를 굴린다.
     *
     * <p>{@code nextDouble()} 은 [0, 1) 이라 1.0 이 나오지 않는다. 그래서 난수를 왼쪽에 둔다 —
     * 뒤집으면 실패율 0 일 때 {@code 0.xx < 0} 이 아니라 {@code 0 < 0.xx} 가 되어 전부 실패한다.
     */
    private boolean rolledFailure(double failureRate) {
        return ThreadLocalRandom.current().nextDouble() < failureRate;
    }

    /**
     * 이 요청이 기다릴 시간을 뽑는다. 꼬리에 걸리면 꼬리 구간에서 균등, 아니면 몸통에서 뽑는다.
     *
     * <p>몸통 평균은 설정한 평균이 아니라 꼬리만큼 낮춘 값이다({@link LatencyTail#bodyMeanMs}) — 그래야 전체
     * 평균이 설정값 그대로다. 지터는 몸통에만 건다. 꼬리는 구간 자체가 퍼져 있다.
     *
     * <p>시험이 직접 부를 수 있게 package-private 로 둔다({@link #jittered} 와 같은 이유).
     */
    long drawn(int meanMs, LatencyTail tail) {
        if (!tail.isNone() && ThreadLocalRandom.current().nextDouble() < tail.rate()) {
            return ThreadLocalRandom.current().nextLong(tail.minMs(), tail.maxMs() + 1L);
        }
        double bodyMeanMs = tail.bodyMeanMs(meanMs);
        return bodyMeanMs <= 0 ? 0 : jittered(bodyMeanMs);
    }

    private void sleep(long waitMs) {
        announce(waitMs);
        pause(waitMs, "지연 대기 중 중단됐다");
    }

    /**
     * 일부러 기다린다. 지연 주입과 느린 성공 결함({@link InMemoryFaultStore#holdBeforeCommit})이 같이 쓴다 — 대기 중
     * 중단을 같은 방식으로 다룬다.
     *
     * <p>중단되면 500 이다. 트랜잭션 밖 · 커밋 전이라 저장된 것이 없으니 워커에게는 일시 실패가 맞다.
     */
    static void pause(long waitMs, String interruptedMessage) {
        if (waitMs <= 0) {
            return;
        }
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException e) {
            // 인터럽트 상태를 되살려 둔다. 삼키면 종료 신호가 묻힌다.
            Thread.currentThread().interrupt();
            throw new MockException(ErrorCode.UPSTREAM_UNAVAILABLE, interruptedMessage);
        }
    }

    /**
     * 지연 주입 뒤에 더 기다린 만큼 {@code X-Mock-Injected-Latency-Ms} 에 더한다. 느린 성공 결함이 쓴다.
     *
     * <p>더하지 않으면 그 대기가 부하 판정에서 "Mock 의 오버헤드" 로 잡힌다. 헤더가 아직 없으면(지연 주입을 거치지
     * 않은 호출) 이 값만 싣는다.
     */
    static void announceMore(long extraMs) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servlet
                && servlet.getResponse() != null) {
            String current = servlet.getResponse().getHeader(INJECTED_LATENCY_HEADER);
            long base = current == null ? 0 : Long.parseLong(current);
            servlet.getResponse().setHeader(INJECTED_LATENCY_HEADER, Long.toString(base + extraMs));
        }
    }

    /**
     * 이 요청에 실제로 뽑힌 대기 시간을 응답 헤더로 알린다. 지연이 0 이어도 {@code 0} 을 붙인다.
     *
     * <p>부하 판정이 쓴다. 지연을 흔들면 관측 백분위에서 주입 백분위를 빼는 것은 오버헤드의 백분위가
     * 아니다 — 분위수는 더하거나 빼지지 않는다(p99 오버헤드가 p95 보다 작게 나온 적이 있다). 요청마다
     * {@code 관측 − 이 값} 을 구해야 Mock 이 실제로 쓴 시간의 꼬리를 볼 수 있다.
     *
     * <p>응답이 확정되기 전이라 여기서 붙여도 201 · 500 모두에 실린다. 서블릿 요청 밖(단위 시험)에서는
     * 붙일 곳이 없어 건너뛴다.
     */
    private static void announce(long waitMs) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servlet
                && servlet.getResponse() != null) {
            servlet.getResponse().setHeader(INJECTED_LATENCY_HEADER, Long.toString(waitMs));
        }
    }

    /**
     * 설정값을 <b>평균</b>으로 삼아 균등분포로 흔든다. 범위는 {@code 평균 × (1 ∓ 지터)} 다.
     *
     * <p>지터가 0 이면 흔들지 않는다. 대기 시간을 단정해야 하는 단위 시험에서만 그렇게 둔다.
     *
     * <p>지터는 설정에서 0 ~ 1 로 막는다({@link MockProperties}). 1 이면 하한이 정확히 0 이고, 그보다
     * 크면 하한만 잘려 평균이 올라가기 때문이다. 하한을 0 에서 한 번 더 자르는 것은 방어일 뿐이다.
     *
     * <p>시험이 직접 부를 수 있게 package-private 로 둔다. {@code Thread.sleep} 을 수만 번 재면
     * 스케줄러 오차가 분포보다 커져 무엇을 본 것인지 알 수 없다.
     */
    long jittered(double meanMs) {
        double jitter = properties.latencyJitter();
        if (jitter <= 0) {
            return Math.round(meanMs);
        }
        long low = Math.max(0, Math.round(meanMs * (1 - jitter)));
        long high = Math.round(meanMs * (1 + jitter));
        // 상한을 포함시켜야 분포가 평균을 중심으로 대칭이 된다.
        return ThreadLocalRandom.current().nextLong(low, high + 1);
    }
}
