package com.grandis.nova.mockapi.load;

/**
 * Mock 이 요청마다 일부러 넣은 지연의 분포. 관측 지연에서 이것을 빼야 Mock 이 실제로 쓴 시간이 남는다.
 *
 * <p>Mock 의 지연은 평균값이다(요구사항 2장 "평균 500ms 지연"). 요청마다
 * {@code 평균 × (1 ∓ 지터)} 사이에서 <b>균등하게</b> 뽑으므로, 주입한 지연의 백분위를 미리 계산할
 * 수 있다. 균등분포 [low, high] 의 p 백분위는 {@code low + (high − low) × p / 100} 이다.
 *
 * <p>Mock 이 지터를 알려주지 않으면(평균화 이전 버전) 지터 0 으로 본다. 그때는 백분위가 전부 평균과
 * 같아져 "관측 − 설정값" 과 같은 계산이 된다.
 *
 * @param meanMs 설정한 지연의 평균
 * @param jitter 흔드는 폭. 0 이면 고정
 */
public record InjectedLatency(int meanMs, double jitter) {

    /**
     * 주입한 지연의 p 백분위. 경계는 Mock 이 쓰는 식과 같게 반올림한다 — 여기서 어긋나면 뺄 값이
     * 몇 ms 씩 틀어진다.
     */
    public long percentileMs(double p) {
        if (meanMs <= 0 || jitter <= 0) {
            return Math.max(meanMs, 0);
        }
        long low = Math.max(0, Math.round(meanMs * (1 - jitter)));
        long high = Math.round(meanMs * (1 + jitter));
        return Math.round(low + (high - low) * p / 100.0);
    }

    /** 보고서에 적을 한 줄. */
    public String describe() {
        if (jitter <= 0) {
            return meanMs + "ms 고정";
        }
        long low = Math.max(0, Math.round(meanMs * (1 - jitter)));
        long high = Math.round(meanMs * (1 + jitter));
        return "평균 " + meanMs + "ms · " + low + " ~ " + high + "ms 균등 (지터 " + jitter + ")";
    }
}
