package com.grandis.nova.mockapi.control.api;

import com.grandis.nova.mockapi.global.chaos.FailureMode;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 설정 변경 요청.
 *
 * <p>기본형(int·double)이 아니라 래퍼형으로 받는다. 기본형이면 필드를 빼먹고 보냈을 때 0 이 되어
 * "지연 0ms · 실패율 0%" 로 조용히 적용된다. 래퍼형 + {@code @NotNull} 이어야 400 으로 거절한다.
 *
 * <p>범위를 벗어난 값도 400 이다. 설정 테이블이 없어 DB 제약이 없으므로 여기가 유일한 방어선이다.
 */
public record ConfigUpdateRequest(

        @NotNull(message = "은(는) 필수입니다.")
        @Min(value = 0, message = "은(는) 0 이상이어야 합니다.")
        @Max(value = 60000, message = "은(는) 60000 이하여야 합니다.")
        Integer registerLatencyMs,

        @NotNull(message = "은(는) 필수입니다.")
        @DecimalMin(value = "0.0", message = "은(는) 0 이상이어야 합니다.")
        @DecimalMax(value = "1.0", message = "은(는) 1 이하여야 합니다.")
        Double failureRate,

        /** 선택. 생략하면 HTTP_5XX 다. 값이 잘못되면 역직렬화 단계에서 400 이 된다. */
        FailureMode failureMode
) {

    public FailureMode failureModeOrDefault() {
        return failureMode == null ? FailureMode.HTTP_5XX : failureMode;
    }
}
