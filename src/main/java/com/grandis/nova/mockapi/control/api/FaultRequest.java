package com.grandis.nova.mockapi.control.api;

import com.grandis.nova.mockapi.global.chaos.FaultType;
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
 */
public record FaultRequest(

        @NotBlank(message = "은(는) 필수입니다.")
        String externalKey,

        @NotNull(message = "은(는) 필수입니다.")
        FaultType faultType
) {
}
