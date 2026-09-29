package com.grandis.nova.mockapi.registration.api;

import com.grandis.nova.mockapi.registration.application.CancelResult;
import com.grandis.nova.mockapi.registration.domain.Registration;
import java.time.Instant;
import java.util.List;

/**
 * 예약 취소 결과.
 *
 * <p>{@code cancelMarkerAt} 과 {@code canceledAt} 은 ERD 의 같은 칸({@code canceled_at})이라 항상 같은 값이다.
 * 이미 취소된 대상이면 처음 취소한 시각이 그대로 나간다.
 *
 * @param externalNumbers       이 키로 만들어진 등록의 번호. 키가 PK 라 0~1개
 * @param hadActiveRegistration 이번 호출이 활성 등록을 실제로 껐는지
 */
public record CancelResponse(
        String externalKey,
        List<String> externalNumbers,
        boolean hadActiveRegistration,
        Instant cancelMarkerAt,
        Instant canceledAt
) {

    public static CancelResponse from(CancelResult result) {
        Registration registration = result.registration();
        // 번호가 없는 행은 등록이 아니라 등록 전에 남긴 취소 표식이다
        List<String> numbers = registration.externalNumber() == null
                ? List.of()
                : List.of(registration.externalNumber());
        return new CancelResponse(
                registration.externalKey(),
                numbers,
                result.hadActiveRegistration(),
                registration.canceledAt(),
                registration.canceledAt());
    }
}
