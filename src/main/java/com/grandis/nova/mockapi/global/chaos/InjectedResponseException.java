package com.grandis.nova.mockapi.global.chaos;

/**
 * 주입한 실패 응답을 이미 직접 써서 보냈다는 신호. {@link ResponseLostAdvice} 가 아무것도 쓰지 않고 삼킨다.
 *
 * <p>{@link FailureMode#MIXED} 의 본문 없는 500 · HTML 502~504 는 Mock 의 JSON 오류 형식이 아니라 오류 처리기를 거칠 수
 * 없다 — 거치면 JSON 본문이 붙거나, {@code Accept: application/json} 요청에 HTML 을 쓰려다 다른 오류가 된다. 그래서
 * 응답을 서블릿에 직접 쓰고 이 예외로 등록 처리를 멈춘다. {@link ResponseLostException} 과 같은 방식이고, 다른 점은
 * 응답을 붙잡지 않고 바로 보냈다는 것이다.
 */
public class InjectedResponseException extends RuntimeException {

    public InjectedResponseException(String message) {
        super(message);
    }
}
