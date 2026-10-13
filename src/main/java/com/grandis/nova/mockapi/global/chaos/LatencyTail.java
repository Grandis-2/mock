package com.grandis.nova.mockapi.global.chaos;

/**
 * 지연의 느린 꼬리. 요청마다 {@code rate} 확률로 [{@code minMs}, {@code maxMs}] 균등에서 뽑고, 나머지는 지금처럼
 * 몸통({@code 평균 × (1 ∓ 지터)})에서 뽑는다.
 *
 * <p>왜 두는가 — 몸통만으로는 "대부분 빠르고 드물게 수 초" 를 만들 수 없고, 특히 <b>워커 타임아웃 뒤에 늦게
 * 커밋되는 등록</b>을 만들 수 없다. 워커는 포기한 뒤 키 조회에서 404 를 보는데 그 뒤에 커밋되는 경우로, 같은 키
 * 재시도 · 예약 취소 때의 취소 표식 규칙이 바로 이 경우를 위한 것이다. 응답 유실 결함은 커밋 뒤에 끊어 이 경우가 아니다.
 *
 * <p><b>전체 평균은 설정한 평균 그대로다</b>(요구사항 "평균 500ms"). 꼬리가 평균을 올리는 만큼 몸통 평균을 낮춘다
 * — {@link #bodyMeanMs}. 계산식을 여기 한 곳에만 둔다. 지연 뽑기 · 설정 조회 · 부하 하네스가 같은 값을 써야 한다.
 *
 * <p>값의 범위는 설정 API 가 검사한다. 이 record 는 받은 값을 그대로 담는다.
 *
 * @param rate  꼬리에 걸릴 확률. 0 이면 꼬리 없음
 * @param minMs 꼬리 구간 하한
 * @param maxMs 꼬리 구간 상한(포함)
 */
public record LatencyTail(double rate, int minMs, int maxMs) {

    /** 꼬리 없음. 기동할 때와 설정 API 에서 꼬리 칸을 생략했을 때다. */
    public static final LatencyTail NONE = new LatencyTail(0.0, 0, 0);

    public boolean isNone() {
        return rate <= 0;
    }

    /** 꼬리 구간의 평균. 균등분포라 양 끝의 가운데다. */
    public double meanMs() {
        return (minMs + maxMs) / 2.0;
    }

    /**
     * 전체 평균이 {@code meanMs} 가 되게 하는 몸통 평균. {@code (평균 − 비율 × 꼬리 평균) ÷ (1 − 비율)}.
     * 예: 평균 500 · 2% 가 2~4초면 (500 − 0.02 × 3000) ÷ 0.98 ≈ 449.
     *
     * <p>꼬리가 없으면 평균 그대로다. 0 이하가 나오는 설정은 설정 API 가 거절한다 — 꼬리만으로 평균을 넘긴 것이다.
     */
    public double bodyMeanMs(int meanMs) {
        if (isNone()) {
            return meanMs;
        }
        return (meanMs - rate * meanMs()) / (1 - rate);
    }
}
