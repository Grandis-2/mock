package com.grandis.nova.mockapi.registration.api;

import com.grandis.nova.mockapi.registration.domain.Registration;
import com.grandis.nova.mockapi.registration.domain.RegistrationStatus;
import java.time.Instant;

/**
 * 등록 한 건. 등록 응답과 번호 조회 응답이 같은 형식이다.
 *
 * <p>신청 내용 칸은 요청과 같은 이름이다. 예약번호는 {@code externalNumber} 로 둔다 — 본 서비스 워커가
 * 아직 없어 읽는 쪽 이름이 정해지지 않았고, be 의 성공 이벤트도 같은 이름을 쓴다.
 *
 * <p>시각은 {@link Instant} 라 끝에 {@code Z} 가 붙고, 밀리초는 0 이어도 세 자리로 나간다(JsonTimeConfig).
 * 값이 없어도 필드를 생략하지 않고 null 로 내보낸다.
 */
public record RegistrationResponse(
        String externalKey,
        String externalNumber,
        String customerRef,
        String itemCode,
        String optionCode,
        Integer qty,
        String scope,
        RegistrationStatus status,
        Instant confirmedAt,
        Instant canceledAt
) {

    public static RegistrationResponse from(Registration registration) {
        return new RegistrationResponse(
                registration.externalKey(),
                registration.externalNumber(),
                registration.customerRef(),
                registration.itemCode(),
                registration.optionCode(),
                registration.qty(),
                registration.scope(),
                registration.status(),
                registration.confirmedAt(),
                registration.canceledAt());
    }
}
