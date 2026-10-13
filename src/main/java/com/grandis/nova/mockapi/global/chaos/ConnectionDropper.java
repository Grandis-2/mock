package com.grandis.nova.mockapi.global.chaos;

import com.grandis.nova.mockapi.global.config.MockProperties;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 응답을 주지 않고 연결을 붙잡아 둔다. 호출한 쪽은 끝내 아무 응답도 받지 못한다.
 *
 * <p>워커가 결과를 <b>모르는</b> 상황(UNKNOWN)을 만드는 유일한 수단이다. 두 곳이 쓴다.
 * <ul>
 *   <li>{@code failureMode=TIMEOUT} — 커밋 전, 주사위에 걸렸을 때</li>
 *   <li>{@code RESPONSE_LOST_AFTER_COMMIT} 결함 — 커밋 직후</li>
 * </ul>
 *
 * <p><b>방법:</b> {@code mock.timeout-hold-ms} 만큼 <b>아무것도 보내지 않고</b> 붙잡는다. 상태만
 * 미리 정해두고 헤더조차 내보내지 않으므로, 클라이언트는 그동안 읽을 것이 없어 자기 읽기
 * 타임아웃에 걸린다.
 *
 * <p>헤더를 먼저 내보내면 안 된다. 상태 줄이 이미 도착하므로 RestClient 같은 클라이언트는
 * "500 을 받았다" 로 처리한다 — 본문을 읽다 타임아웃이 나도 그것을 삼키고 본문 없는 500 으로
 * 넘긴다. 워커에게 500 은 "일시 실패" 라는 분명한 뜻이라 UNKNOWN 이 되지 않는다.
 *
 * <p>다른 방법도 전부 결국 응답을 보내서 쓸 수 없었다. 대기만 하면 시간이 지난 뒤 200 이 나가고,
 * 비동기로 열어두면 500 이 나가며, 예외를 던지면 {@code GlobalExceptionHandler} 가 잡아 500 으로
 * 바꾼다.
 *
 * <p>유지 시간이 지나면 빈 본문으로 응답을 끝낸다. 계속 붙잡고만 있으면 클라이언트가 끊어 줄
 * 때까지 연결이 회수되지 않아 부하 시험에서 쌓인다. 1,000 RPS 에 유지 10초면 약 1만 개로 톰캣
 * 상한(8,192)을 넘는다.
 *
 * <p>유지 시간은 <b>워커 읽기 타임아웃보다 길어야 한다.</b> 짧으면 워커가 타임아웃 대신 500 을
 * 받아 일시 실패로 처리한다 — 안전한 쪽이지만 재현하려던 상황은 아니다. 명세가 "워커 타임아웃
 * + 2초" 로 정한 이유다.
 *
 * <p>붙잡는 동안 스레드 하나가 잔다. 가상 스레드라 수천 개가 동시에 자도 부담이 적다.
 */
@Component
public class ConnectionDropper {

    private static final Logger log = LoggerFactory.getLogger(ConnectionDropper.class);

    private final MockProperties properties;

    public ConnectionDropper(MockProperties properties) {
        this.properties = properties;
    }

    /**
     * 지금 처리 중인 요청의 응답을 영영 완성하지 않는다.
     *
     * <p>붙잡는 동안 아무것도 보내지 않고, 끝나면 빈 본문으로 마무리한 뒤
     * {@link ResponseLostException} 을 던져 이후 처리를 멈춘다. 던지지 않으면 호출한 쪽이 계속
     * 진행해 본문을 써버린다.
     *
     * @param reason 로그에 남길 이유
     * @throws ResponseLostException 언제나 던진다. 이 메서드는 정상 반환하지 않는다
     */
    public void drop(String reason) {
        HttpServletResponse response = currentResponse();

        // 상태만 정해두고 보내지 않는다. setStatus 는 응답이 확정될 때 나간다.
        // 먼저 정해두면 붙잡는 도중 중단돼도 200 빈 응답(= 성공)이 나갈 일이 없다.
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);

        hold();

        try {
            response.setContentLength(0);
            response.flushBuffer();
        } catch (IOException e) {
            // 클라이언트가 먼저 끊었다는 뜻이고, 목적은 이미 달성됐다.
            log.debug("연결 끊기 중 입출력 오류 — 이미 끊긴 연결로 본다", e);
        }
        throw new ResponseLostException(reason);
    }

    private void hold() {
        try {
            Thread.sleep(properties.timeoutHoldMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseLostException("붙잡는 도중 중단됐다");
        }
    }

    private HttpServletResponse currentResponse() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet && servlet.getResponse() != null) {
            return servlet.getResponse();
        }
        throw new IllegalStateException("서블릿 요청 안에서만 쓸 수 있다");
    }
}
