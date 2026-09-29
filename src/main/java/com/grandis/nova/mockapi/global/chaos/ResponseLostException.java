package com.grandis.nova.mockapi.global.chaos;

/**
 * 응답을 주지 않기로 한 요청이라는 표시.
 *
 * <p>{@link ConnectionDropper} 가 헤더도 내보내지 않고 붙잡았다가 빈 응답으로 끝낸 뒤 던진다.
 * 이후 처리를 멈추려는 것이 목적이고 오류가 아니다. {@link ResponseLostAdvice} 가 아무것도 쓰지
 * 않고 삼킨다.
 */
public class ResponseLostException extends RuntimeException {

    public ResponseLostException(String message) {
        super(message);
    }
}
