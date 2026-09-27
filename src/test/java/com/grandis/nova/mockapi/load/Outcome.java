package com.grandis.nova.mockapi.load;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

/**
 * 부하 시험에서 요청 하나가 끝난 모양. 요구사항 8장이 요구하는 네 분류다.
 *
 * <p><b>실패와 미전송을 결과에서 빼지 않는다.</b> 성공률을 "응답받은 것 중 성공" 으로 세면 보내지도
 * 못한 요청과 결과를 모르는 요청이 분모에서 사라져, 서버가 버티지 못한 상황이 오히려 좋은 숫자로
 * 나온다. 네 분류의 합이 항상 보낸 요청 수와 같아야 한다.
 *
 * <p>가장 중요한 구분은 {@link #REJECTED} 와 {@link #UNKNOWN} 이다. 둘 다 "성공하지 못했다" 지만
 * 본 서비스가 할 일이 정반대다 — 거절은 결과가 정해진 것이고, 결과 불명은 등록됐을 수도 있어
 * {@code by-key} 조회로 확인한 뒤에야 재시도할 수 있다. 일반 부하 도구는 이 둘을 모두 "error" 로
 * 묶으므로 직접 판정한다.
 */
public enum Outcome {

    /** 접수 성공. 201 을 받았다(재생 포함). */
    ACCEPTED,

    /**
     * 명시적 거절. 서버가 응답으로 결과를 분명히 알려줬다.
     *
     * <p>업무 거절(409 · 422)과 계약 오류(400), 주입된 일시 실패(500)가 모두 여기다. 셋 다 응답을
     * 받았으므로 <b>등록되지 않았음이 확정</b>이다 — 500 은 커밋 전에 발생하는 것이 명세의 약속이다.
     * 세부 상태 코드는 따로 집계해 보고서에서 나눠 본다.
     */
    REJECTED,

    /**
     * 결과 불명. 요청은 나갔는데 응답을 받지 못했다.
     *
     * <p>{@code failureMode=TIMEOUT} 이나 커밋 후 응답 유실이 만드는 상태이고, 부하 시험에서 가장
     * 주의 깊게 봐야 할 숫자다. 이게 늘어난다는 것은 본 서비스가 재확인해야 할 예약이 그만큼
     * 쌓인다는 뜻이다.
     */
    UNKNOWN,

    /**
     * 미전송. 요청을 보내지도 못했다.
     *
     * <p>커넥션 거부, 연결 타임아웃, 또는 계획한 시각에 발사하지 못한 경우다. 서버가 받지 못했으므로
     * 등록될 수 없지만, <b>부하를 걸지 못한 것</b>이므로 성공률 계산에서 빼면 안 된다.
     */
    NOT_SENT;

    /**
     * 응답 상태 코드로 판정한다.
     *
     * <p>2xx 외에는 전부 거절이다. Mock 은 계약에 있는 상태(400 · 409 · 422 · 500 · 404)만 내므로
     * 그 밖의 값이 오면 그것도 거절로 세고 세부 집계에서 드러나게 둔다.
     */
    static Outcome ofStatus(int status) {
        return status / 100 == 2 ? ACCEPTED : REJECTED;
    }

    /**
     * 요청이 예외로 끝났을 때 판정한다.
     *
     * <p>연결조차 맺지 못했으면 미전송이고, 연결은 됐는데 응답을 못 받았으면 결과 불명이다.
     * <b>이 구분을 뭉개면 안 된다</b> — 미전송은 서버가 받지 못한 것이고 결과 불명은 서버가 받아서
     * 처리했을 수도 있는 것이다. 순서가 중요하다: 연결 타임아웃은 응답 타임아웃의 하위 타입이다.
     */
    static Outcome ofFailure(Throwable e) {
        if (e instanceof HttpConnectTimeoutException || e instanceof ConnectException) {
            return NOT_SENT;
        }
        if (e instanceof HttpTimeoutException) {
            return UNKNOWN;
        }
        // 연결을 맺은 뒤의 입출력 오류는 요청이 이미 나갔을 수 있다. 모르는 쪽으로 센다.
        return e instanceof IOException ? UNKNOWN : NOT_SENT;
    }
}
