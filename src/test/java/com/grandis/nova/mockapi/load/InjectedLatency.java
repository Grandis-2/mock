package com.grandis.nova.mockapi.load;

import com.grandis.nova.mockapi.global.chaos.LatencyTail;
import java.time.Duration;

/**
 * Mock 이 요청마다 일부러 넣은 지연의 <b>분포</b>. 시나리오가 응답 타임아웃에 걸치는지, 동시 요청이
 * 몇 개쯤 떠 있어야 하는지를 <b>쏘기 전에</b> 계산하는 데 쓴다.
 *
 * <p><b>오버헤드 판정에는 쓰지 않는다.</b> 예전에는 관측 백분위에서 이 분포의 같은 백분위를 뺐는데,
 * 분위수는 빼지지 않아 꼬리를 가렸다. 지금은 요청마다 Mock 이 알려준 실제 지연
 * ({@code X-Mock-Injected-Latency-Ms})을 빼서 그 분포의 백분위를 본다({@link LoadReport#overheadPercentile}).
 *
 * <p>Mock 의 지연은 평균값이다(요구사항 2장 "평균 500ms 지연"). 꼬리가 없으면 요청마다
 * {@code 평균 × (1 ∓ 지터)} 사이에서 <b>균등하게</b> 뽑으므로, 주입한 지연의 백분위를 미리 계산할
 * 수 있다. 균등분포 [low, high] 의 p 백분위는 {@code low + (high − low) × p / 100} 이다.
 *
 * <p>꼬리가 있으면(NV-260) 요청마다 꼬리 비율로 꼬리 구간에서, 나머지는 몸통에서 뽑는다. 몸통 평균은 전체 평균이
 * 그대로이도록 낮춘 값이다 — 식은 Mock 의 {@link LatencyTail#bodyMeanMs} 를 그대로 쓴다. 여기서 따로 계산하면
 * Mock 과 어긋난다.
 *
 * <p>Mock 이 지터를 알려주지 않으면(평균화 이전 버전) 지터 0 으로 본다. 꼬리를 알려주지 않으면 꼬리 없음이다.
 *
 * @param meanMs 설정한 지연의 평균. 꼬리가 있어도 전체 평균이다
 * @param jitter 몸통을 흔드는 폭. 0 이면 고정
 * @param tail   느린 꼬리. 없으면 {@link LatencyTail#NONE}
 */
public record InjectedLatency(int meanMs, double jitter, LatencyTail tail) {

    public InjectedLatency {
        if (tail == null) {
            tail = LatencyTail.NONE;
        }
    }

    /** 꼬리 없는 분포. */
    public InjectedLatency(int meanMs, double jitter) {
        this(meanMs, jitter, LatencyTail.NONE);
    }

    /**
     * 몸통에서 뽑은 지연의 p 백분위. 꼬리가 없으면 전체 분포의 백분위다. 경계는 Mock 이 쓰는 식과 같게 반올림한다 —
     * 여기서 어긋나면 사전 검사가 몇 ms 씩 틀어진다.
     */
    public long percentileMs(double p) {
        double bodyMeanMs = bodyMeanMs();
        if (bodyMeanMs <= 0) {
            return 0;
        }
        if (jitter <= 0) {
            return Math.round(bodyMeanMs);
        }
        long low = Math.max(0, Math.round(bodyMeanMs * (1 - jitter)));
        long high = Math.round(bodyMeanMs * (1 + jitter));
        return Math.round(low + (high - low) * p / 100.0);
    }

    /** 몸통 평균. 꼬리가 없으면 설정한 평균 그대로다. */
    public double bodyMeanMs() {
        return tail.bodyMeanMs(meanMs);
    }

    /** 몸통에서 나올 수 있는 최대 지연. */
    public long bodyMaxMs() {
        return percentileMs(100);
    }

    /** 나올 수 있는 최대 지연. 꼬리가 있으면 몸통 최대와 꼬리 상한 중 큰 쪽이다. */
    public long maxMs() {
        return tail.isNone() ? bodyMaxMs() : Math.max(bodyMaxMs(), tail.maxMs());
    }

    /**
     * 꼬리가 <b>일부러 타임아웃을 넘기는</b> 꼬리인가 — 꼬리 하한이 타임아웃보다 길다. 그러면 꼬리에 걸린 요청은
     * 오버헤드와 상관없이 전부 결과 불명이고, 몸통이 제시간이면 결과 불명 수가 꼬리 수와 정확히 같다.
     *
     * <p>꼬리가 타임아웃에 <b>걸치면</b>(하한 ≤ 타임아웃 < 상한) 이것이 아니다. 그때는 일부만 결과 불명이 되어
     * 무엇을 본 것인지 가를 수 없으므로 사전 검사가 막는다({@link LoadPlan#leavesRoomFor}).
     */
    public boolean tailOverTimeout(Duration responseTimeout) {
        return !tail.isNone() && tail.minMs() > responseTimeout.toMillis();
    }

    /** 보고서에 적을 한 줄. */
    public String describe() {
        if (meanMs <= 0 && tail.isNone()) {
            return "지연 없음";
        }
        String body;
        if (jitter <= 0) {
            body = Math.round(bodyMeanMs()) + "ms 고정";
        } else {
            body = percentileMs(0) + " ~ " + percentileMs(100) + "ms 균등 (지터 " + jitter + ")";
        }
        if (tail.isNone()) {
            return "평균 " + meanMs + "ms · " + body;
        }
        return "평균 " + meanMs + "ms · 몸통 평균 " + Math.round(bodyMeanMs()) + "ms · " + body
                + " · 꼬리 " + percentOf(tail.rate()) + " " + tail.minMs() + " ~ " + tail.maxMs() + "ms";
    }

    private static String percentOf(double rate) {
        return String.format("%.1f%%", rate * 100);
    }
}
