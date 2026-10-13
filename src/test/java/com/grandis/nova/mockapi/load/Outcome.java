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
 * <p>분류의 기준은 "응답을 받았는가" 가 아니라 <b>본 서비스가 무엇을 해야 하는가</b> 다. 그래서
 * 응답을 받은 것도 {@link #REJECTED} 와 {@link #TRANSIENT_FAILURE} 로 갈리고, 응답을 못 받은 것도
 * {@link #UNKNOWN} 과 {@link #NOT_SENT} 로 갈린다. 일반 부하 도구는 이것들을 모두 "error" 로
 * 묶으므로 직접 판정한다.
 *
 * <p>요구사항 8장이 말하는 네 분류로 볼 때는 {@code REJECTED + TRANSIENT_FAILURE} 가 "명시적 거절"
 * 이다. 다섯으로 나눠도 <b>합이 보낸 요청 수와 같다는 성질은 그대로</b>다.
 */
public enum Outcome {

    /** 접수 성공. 201 을 받았다(재생 포함). */
    ACCEPTED,

    /**
     * 명시적 거절. 다시 보내도 같은 결과가 나오는 확정 거절이다.
     *
     * <p>계약 오류(400)와 업무 거절(409 · 422)이다. 본 서비스는 재시도하지 않고 오류로 남긴다.
     */
    REJECTED,

    /**
     * 일시 실패. 응답은 받았지만 <b>재시도 대상</b>이다.
     *
     * <p>주입된 5xx 다. {@link #REJECTED} 와 합치면 안 된다 — 둘 다 응답을 받았지만 본 서비스가
     * 할 일이 정반대이고, 명세도 "5xx 는 미등록의 증거가 아니다" 로 둔다. 합쳐 두면 설정한 실패율이
     * 실제로 몇 % 나왔는지가 거절 수에 묻힌다.
     */
    TRANSIENT_FAILURE,

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
     * <p>5xx 는 재시도 대상이라 일시 실패이고, 그 밖의 비 2xx 는 확정 거절이다. Mock 은 계약에 있는
     * 상태(400 · 404 · 409 · 422 · 500)만 내므로 그 밖의 값이 와도 4xx/5xx 중 하나로 갈린다.
     * 세부 상태 코드는 따로 집계해 보고서에서 나눠 본다.
     */
    static Outcome ofStatus(int status) {
        return switch (status / 100) {
            case 2 -> ACCEPTED;
            case 5 -> TRANSIENT_FAILURE;
            default -> REJECTED;
        };
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
