package com.grandis.nova.mockapi.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 기록 초기화 요청.
 *
 * <p>확인 문자열을 요구하는 이유는 실수 실행을 막기 위해서다. 경로만으로 지워지면 부하 시험을
 * 준비하다 손이 미끄러진 한 번에 대조 자료가 사라진다.
 *
 * @param confirm {@code RESET} 이어야 한다. 다르면 400 이고 아무것도 지우지 않는다
 */
public record ResetRequest(

        @NotBlank(message = "은(는) 필수입니다.")
        @Pattern(regexp = "RESET", message = "은(는) RESET 이어야 합니다.")
        String confirm
) {
}
