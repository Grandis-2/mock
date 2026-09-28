package com.grandis.nova.mockapi.global.config;

import com.grandis.nova.mockapi.global.chaos.FailureMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 지연·실패 설정의 기본값. 환경변수나 application.yml 로 준다.
 *
 * <p>ERD 가 설정 테이블을 두지 않기로 했으므로 실행 중 변경값은 메모리에만 있고,
 * 재기동하면 여기 값으로 돌아간다.
 */
@ConfigurationProperties(prefix = "mock")
public record MockProperties(

        /** 등록 요청이 일부러 기다리는 시간의 <b>평균</b>. 실제 대기는 아래 지터만큼 흔들린다. */
        @DefaultValue("500") int registerLatencyMs,

        /**
         * 지연을 흔드는 폭. 실제 대기는 <b>평균 × (1 ∓ 이 값)</b> 범위의 균등분포다.
         *
         * <p>주제와 요구사항 2장이 Mock 동작을 <b>"평균 500ms 지연"</b> 으로 정했다. 고정으로 두면
         * 평균이 아니라 상수라서 요구사항과 다르다. 그래서 운영 · 시연 · 부하 판정 모두 흔든다.
         *
         * <p><b>균등분포를 쓰는 이유.</b> 지수분포가 현실에 가깝지만 꼬리가 길어 부하 판정에서
         * 백분위가 요동친다. 균등분포는 주입한 지연의 백분위를 미리 계산할 수 있어(p95 는
         * {@code 평균 × (1 + 지터 × 0.9)}), 관측값에서 그 몫을 빼내 Mock 이 실제로 쓴 시간을 볼 수 있다.
         *
         * <p>0 이면 흔들지 않는다. 대기 시간을 단정해야 하는 단위 시험에서만 그렇게 둔다.
         */
        @DefaultValue("0.4") double latencyJitter,

        /** 등록 요청이 일시 실패할 확률. 0 ~ 1. */
        @DefaultValue("0.05") double failureRate,

        @DefaultValue("HTTP_5XX") FailureMode failureMode,

        /** TIMEOUT 모드에서 응답 없이 연결을 유지하는 시간. */
        @DefaultValue("5000") long timeoutHoldMs
) {
}
