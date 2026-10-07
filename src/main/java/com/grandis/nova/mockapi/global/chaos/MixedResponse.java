package com.grandis.nova.mockapi.global.chaos;

/**
 * {@link FailureMode#MIXED} 에서 섞어 보내는 실패 응답의 종류. 실패 판정에 걸리면 설정한 종류 중 하나를 같은 확률로 고른다.
 *
 * <p><b>워커가 갈라야 하는 것은 상태 번호가 아니라 {@code errorCode} 의 유무다.</b> {@code errorCode} 가 있는 500 은
 * Mock 이 "처리 전에 실패했다" 고 알려 준 일시 실패이고, 없는 5xx 는 무슨 일이 있었는지 모르는 결과 불명이다
 * (오류 분류 계약 D6 · H5~H7). 그래서 같은 500 을 두 모양으로 둔다.
 *
 * <p>502 · 503 · 504 는 실제로는 Mock 앞의 장비(로드 밸런서 · 게이트웨이)가 보내는 응답이라 Mock 의 JSON 이 아니라
 * HTML 이다. 본문을 무조건 JSON 으로 읽는 워커가 여기서 터지는지도 같이 드러난다. 문구는 장비마다 달라 정답이 없으므로
 * 대표 모양(nginx 와 비슷한 짧은 HTML)을 쓴다 — 워커는 문구가 아니라 상태와 {@code errorCode} 유무로 분기해야 한다.
 *
 * <p>모두 <b>커밋 전</b>에 보낸다. 저장된 것이 없어 키 조회는 404 다. "5xx 인데 저장된" 경우는 이 모드로 만들 수 없다.
 */
public enum MixedResponse {

    /** 500 + {@code errorCode: UPSTREAM_UNAVAILABLE}. {@link FailureMode#HTTP_5XX} 와 같은 응답이다. */
    HTTP_500(500, null),

    /** 본문 없는 500. Mock 이 연결을 정리할 때 내는 모양(D6)이다. */
    HTTP_500_NO_BODY(500, null),

    /** 앞단 장비가 뒤 서버에서 이상한 응답을 받았을 때(H5). */
    HTTP_502(502, "502 Bad Gateway"),

    /** 뒤에 살아 있는 서버가 없을 때 — 기동 · 종료 · 배포 중(H6). */
    HTTP_503(503, "503 Service Unavailable"),

    /** 앞단 장비가 뒤 서버를 기다리다 먼저 포기했을 때(H7). */
    HTTP_504(504, "504 Gateway Timeout");

    private final int status;
    private final String title;

    MixedResponse(int status, String title) {
        this.status = status;
        this.title = title;
    }

    public int status() {
        return status;
    }

    /** 앞단 장비 흉내의 HTML 본문. 앞단 장비 응답이 아니면 null. */
    public String html() {
        if (title == null) {
            return null;
        }
        return "<html>\r\n<head><title>" + title + "</title></head>\r\n<body>\r\n<center><h1>" + title
                + "</h1></center>\r\n</body>\r\n</html>\r\n";
    }
}
