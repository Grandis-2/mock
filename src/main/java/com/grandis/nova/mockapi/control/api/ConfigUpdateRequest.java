package com.grandis.nova.mockapi.control.api;

import com.grandis.nova.mockapi.global.chaos.FailureMode;
import com.grandis.nova.mockapi.global.chaos.LatencyTail;
import com.grandis.nova.mockapi.global.error.ErrorCode;
import com.grandis.nova.mockapi.global.error.MockException;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;

/**
 * 설정 변경 요청.
 *
 * <p>기본형(int·double)이 아니라 래퍼형으로 받는다. 기본형이면 필드를 빼먹고 보냈을 때 0 이 되어
 * "지연 0ms · 실패율 0%" 로 조용히 적용된다. 래퍼형 + {@code @NotNull} 이어야 400 으로 거절한다.
 *
 * <p>범위를 벗어난 값도 400 이다. 설정 테이블이 없어 DB 제약이 없으므로 여기가 유일한 방어선이다.
 *
 * <p><b>꼬리 칸 세 개는 선택이지만 묶음이다.</b> 다 빼면 꼬리 없음, 다 주면 그 꼬리, 일부만 주면 400 이다. 비율만
 * 주고 구간을 빠뜨린 실수가 조용히 "꼬리 없음" 으로 적용되지 않게 한다. 생략을 "앞 값 유지" 가 아니라 "꼬리 없음"
 * 으로 두는 것은 이 PUT 이 통째 교체이기 때문이다 — 그래야 시나리오를 바꿀 때 앞 시나리오의 꼬리가 남지 않는다.
 * 칸 하나의 범위는 애너테이션으로, 칸 사이의 관계는 {@link #latencyTailOrNone()} 이 본다.
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
        FailureMode failureMode,

        /** 선택(꼬리 묶음). 꼬리에 걸릴 확률. 1 이면 몸통 평균 식의 분모가 0 이라 1 미만만 받는다. */
        @DecimalMin(value = "0.0", message = "은(는) 0 이상이어야 합니다.")
        @DecimalMax(value = "1.0", inclusive = false, message = "은(는) 1 미만이어야 합니다.")
        Double latencyTailRate,

        /** 선택(꼬리 묶음). 꼬리 구간 하한. */
        @Min(value = 0, message = "은(는) 0 이상이어야 합니다.")
        @Max(value = 60000, message = "은(는) 60000 이하여야 합니다.")
        Integer latencyTailMinMs,

        /** 선택(꼬리 묶음). 꼬리 구간 상한(포함). */
        @Min(value = 0, message = "은(는) 0 이상이어야 합니다.")
        @Max(value = 60000, message = "은(는) 60000 이하여야 합니다.")
        Integer latencyTailMaxMs
) {

    public FailureMode failureModeOrDefault() {
        return failureMode == null ? FailureMode.HTTP_5XX : failureMode;
    }

    /**
     * 적용할 꼬리. 칸 범위는 애너테이션이 이미 봤으므로 칸 사이의 관계만 본다.
     *
     * <ul>
     *   <li>세 칸을 다 빼면 꼬리 없음. 일부만 주면 400</li>
     *   <li>최소 ≤ 최대</li>
     *   <li>비율이 0 이면 꼬리 없음 — 몸통 평균 검사도 건너뛴다. 지연 0 · 꼬리 0(확인용 등록 · 시험 설정)이
     *       "꼬리가 평균을 넘는다" 로 걸리면 안 된다</li>
     *   <li>비율이 0 보다 크면 몸통 평균 > 0. 아니면 꼬리만으로 평균을 넘긴 것이라 평균을 지킬 수 없다</li>
     * </ul>
     *
     * @throws MockException 위 관계가 어긋나면 400
     */
    public LatencyTail latencyTailOrNone() {
        List<String> missing = new ArrayList<>();
        if (latencyTailRate == null) {
            missing.add("latencyTailRate");
        }
        if (latencyTailMinMs == null) {
            missing.add("latencyTailMinMs");
        }
        if (latencyTailMaxMs == null) {
            missing.add("latencyTailMaxMs");
        }
        if (missing.size() == 3) {
            return LatencyTail.NONE;
        }
        if (!missing.isEmpty()) {
            throw invalid("latencyTailRate · latencyTailMinMs · latencyTailMaxMs 는 함께 보내거나 함께 빼야 합니다. "
                    + "빠진 칸: " + String.join(" · ", missing));
        }
        if (latencyTailMinMs > latencyTailMaxMs) {
            throw invalid("latencyTailMinMs 은(는) latencyTailMaxMs 이하여야 합니다.");
        }
        LatencyTail tail = new LatencyTail(latencyTailRate, latencyTailMinMs, latencyTailMaxMs);
        if (tail.isNone()) {
            return LatencyTail.NONE;
        }
        if (tail.bodyMeanMs(registerLatencyMs) <= 0) {
            throw invalid("꼬리만으로 평균 지연을 넘습니다. latencyTailRate × 꼬리 평균(" + Math.round(tail.meanMs())
                    + "ms) 이 registerLatencyMs(" + registerLatencyMs + ") 보다 작아야 나머지 요청으로 평균을 맞출 수 있습니다.");
        }
        return tail;
    }

    private static MockException invalid(String message) {
        return new MockException(ErrorCode.INVALID_REQUEST, message);
    }
}
