package com.grandis.nova.mockapi.global.config;

import com.grandis.nova.mockapi.global.chaos.FailureMode;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 지연·실패 설정의 기본값. 환경변수나 application.yml 로 준다.
 *
 * <p>ERD 가 설정 테이블을 두지 않기로 했으므로 실행 중 변경값은 메모리에만 있고,
 * 재기동하면 여기 값으로 돌아간다.
 *
 * <p>{@code @Validated} 라 범위를 벗어난 값이 있으면 <b>Mock 이 기동하지 않는다.</b> 틀린 값으로
 * 조용히 도는 것보다 낫다.
 *
 * <p>지연과 실패율은 <b>설정 API 와 같은 범위</b>다({@code ConfigUpdateRequest}). 실행 중에는 막는 값을
 * 기동할 때는 받으면, 재기동 한 번에 API 로는 넣을 수 없는 상태가 된다. 흔한 실수는 5% 를
 * {@code failure-rate: 5} 로 적는 것이다 — 막지 않으면 그대로 떠서 등록이 전부 실패한다.
 */
@Validated
@ConfigurationProperties(prefix = "mock")
public record MockProperties(

        /** 등록 요청이 일부러 기다리는 시간의 <b>평균</b>. 실제 대기는 아래 지터만큼 흔들린다. */
        @Min(0) @Max(60000) @DefaultValue("500") int registerLatencyMs,

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
         *
         * <p><b>0 ~ 1 만 받는다.</b> 1 을 넘으면 하한이 0 에서 잘리고 상한만 늘어나 평균이 조용히
         * 올라간다 — 지터 1.5 면 범위가 0 ~ 1250 이라 평균이 500 이 아니라 625 가 된다. 설정 API 로는
         * 바꿀 수 없어 이 파일이 유일한 입구라서 여기서 막는다.
         */
        @DecimalMin("0.0") @DecimalMax("1.0") @DefaultValue("0.4") double latencyJitter,

        /** 등록 요청이 일시 실패할 확률. 0 ~ 1. 5% 는 5 가 아니라 0.05 다. */
        @DecimalMin("0.0") @DecimalMax("1.0") @DefaultValue("0.05") double failureRate,

        @DefaultValue("HTTP_5XX") FailureMode failureMode,

        /**
         * TIMEOUT 모드와 응답 유실 결함에서 응답 없이 연결을 유지하는 시간.
         *
         * <p><b>워커 읽기 타임아웃보다 2초 이상 길어야 한다</b>(docs/api.md). 유지가 끝나면 본문 없는
         * 500 으로 마감하므로, 워커가 먼저 포기해야 결과 불명이 된다. 둘이 같으면 결과가 갈린다 —
         * 워커 5초 · 유지 5초에서 결과 불명을 만들려던 요청의 32~58% 가 빈 500 을 받았다.
         *
         * <p>0 은 시험 전용이다 — 붙잡지 않고 바로 빈 500 으로 끝낸다. 워커에게는 결과 불명이 아니라
         * 일시 실패로 보이므로 운영 · 시연에서는 쓰지 않는다. 그래서 위 규칙에서 뺀다.
         *
         * <p>음수는 뜻이 없는 값이라 막는다. {@code Thread.sleep} 이 예외를 던져 "처리하지 못한 오류" 로
         * 찍히고, 부하 판정이 0건이어야 한다고 세는 서버 버그 로그와 섞인다.
         */
        @Min(0) @DefaultValue("7000") long timeoutHoldMs,

        /**
         * 본 서비스 워커의 HTTP 읽기 타임아웃. Mock 이 정하는 값이 아니라 be 워커 설정을 옮겨 적는 값이다.
         *
         * <p>Mock 은 이 값을 쓰지 않고, 유지 시간이 이보다 충분히 긴지 기동할 때 확인하는 데만 쓴다.
         * 따로 적어 두지 않으면 워커 타임아웃이 바뀌었을 때 유지 시간이 조용히 어긋난다.
         */
        @Min(1) @DefaultValue("5000") long workerReadTimeoutMs
) {

    /** 유지 시간이 워커 읽기 타임아웃보다 이만큼은 길어야 한다. 네트워크 · 처리 시간에 따른 경계를 피한다. */
    public static final long HOLD_MARGIN_MS = 2000;

    /** 두 값의 관계라 필드 하나의 범위로는 막을 수 없다. 어긋나면 범위 검사와 똑같이 기동하지 않는다. */
    @AssertTrue(message = "timeoutHoldMs 는 0(시험 전용)이거나 workerReadTimeoutMs + 2000 이상이어야 합니다."
            + " 같거나 짧으면 워커가 타임아웃 대신 빈 500 을 받아 결과 불명이 되지 않습니다.")
    public boolean isTimeoutHoldLongerThanWorker() {
        return timeoutHoldMs == 0 || timeoutHoldMs >= workerReadTimeoutMs + HOLD_MARGIN_MS;
    }
}
