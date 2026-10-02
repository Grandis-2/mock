package com.grandis.nova.mockapi.control.api;

import com.grandis.nova.mockapi.global.chaos.FaultType;
import com.grandis.nova.mockapi.global.error.ErrorCode;
import com.grandis.nova.mockapi.global.error.MockException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 결함 주입 요청.
 *
 * <p>키 형식(영문 · 숫자 · {@code . _ -} 1~100자)은 컨트롤러가 등록과 같은 규칙({@code Identifiers})으로 검사한다.
 * 등록이 받지 않는 키에 결함을 걸어두면 그 키로는 등록 자체가 안 돼 영원히 발동하지 않는다.
 *
 * @param externalKey 결함을 걸 키
 * @param faultType   결함 종류. 오타는 역직렬화 단계에서 400 이 되고 허용값이 메시지에 담긴다
 * @param delayMs     느린 성공이 커밋 전에 기다릴 시간. {@code SLOW_SUCCESS} 에만 쓴다 — {@link #delayMsOrZero()}
 */
public record FaultRequest(

        @NotBlank(message = "은(는) 필수입니다.")
        String externalKey,

        @NotNull(message = "은(는) 필수입니다.")
        FaultType faultType,

        @Min(value = 1, message = "은(는) 1 이상이어야 합니다.")
        @Max(value = 60000, message = "은(는) 60000 이하여야 합니다.")
        Long delayMs
) {

    /**
     * 걸 결함의 대기 시간. 결함 종류와 짝이 맞는지만 본다(범위는 애너테이션이 봤다).
     *
     * <ul>
     *   <li>{@code SLOW_SUCCESS} 는 {@code delayMs} 가 필수다 — 빠뜨리면 0초 대기라 결함을 건 의미가 없다</li>
     *   <li>{@code RESPONSE_LOST_AFTER_COMMIT} 에 {@code delayMs} 를 주면 400 — 조용히 버리면 보낸 사람은 "커밋 전에
     *       기다렸다 응답을 잃는다" 고 믿는다</li>
     * </ul>
     *
     * @throws MockException 짝이 맞지 않으면 400
     */
    public long delayMsOrZero() {
        if (faultType == FaultType.SLOW_SUCCESS) {
            if (delayMs == null) {
                throw invalid("delayMs 은(는) SLOW_SUCCESS 에 필수입니다. 커밋 전에 기다릴 시간(ms)입니다.");
            }
            return delayMs;
        }
        if (delayMs != null) {
            throw invalid("delayMs 은(는) SLOW_SUCCESS 에만 씁니다. 받은 faultType: " + faultType);
        }
        return 0;
    }

    private static MockException invalid(String message) {
        return new MockException(ErrorCode.INVALID_REQUEST, message);
    }
}
