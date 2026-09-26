package com.grandis.nova.mockapi.admin.dto;

import com.grandis.nova.mockapi.global.chaos.FaultType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 결함 주입 요청.
 *
 * <p>키 길이는 등록·조회와 같은 1~100자다. ERD 의 키 칸이 {@code varchar(100)} 이므로 100자를
 * 넘는 키에 결함을 걸어두면 그 키로는 등록 자체가 안 돼 영원히 발동하지 않는다.
 *
 * @param externalKey 결함을 걸 키
 * @param faultType   결함 종류. 오타는 역직렬화 단계에서 400 이 되고 허용값이 메시지에 담긴다
 */
public record FaultRequest(

        @NotBlank(message = "은(는) 필수입니다.")
        @Size(max = 100, message = "은(는) 100자 이하여야 합니다.")
        String externalKey,

        @NotNull(message = "은(는) 필수입니다.")
        FaultType faultType
) {
}
