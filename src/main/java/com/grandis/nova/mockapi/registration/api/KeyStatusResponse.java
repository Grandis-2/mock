package com.grandis.nova.mockapi.registration.api;

import com.grandis.nova.mockapi.registration.domain.Registration;
import com.grandis.nova.mockapi.registration.domain.RegistrationStatus;
import java.time.Instant;
import java.util.List;

/**
 * 키로 조회한 등록 상태. 응답 유실 뒤 재시도 전에 워커가 부른다.
 *
 * <p>키가 PK 라 행은 하나뿐이고, 그 행은 셋 중 하나다.
 * <pre>
 *                    cancelMarkerAt   registrations   storedOutcome   워커
 *   등록됨            null             1개 (ACTIVE)    SUCCESS         그 번호로 확정
 *   등록 후 취소       취소 시각         1개 (CANCELED)  null            정리
 *   취소 표식만        취소 시각         0개             null            정리
 * </pre>
 *
 * <p>값이 없어도 필드를 생략하지 않고 null 로 내보낸다.
 *
 * @param cancelMarkerAt 이 키가 취소됐으면 그 시각. 등록 후 취소도 채운다 — {@code registrations[].canceledAt}
 *                       과 같은 칸({@code canceled_at})이라 워커는 이것 하나로 "취소된 키" 를 안다
 * @param registrations  이 키로 만들어진 등록. 0~1개
 * @param storedOutcome  같은 키로 다시 등록하면 재생될 저장된 결과. 등록 후 취소된 키는 다시 보내면
 *                       201 재생이 아니라 409 {@code KEY_CANCELED} 라서 null 이다
 */
public record KeyStatusResponse(
        String externalKey,
        Instant cancelMarkerAt,
        List<RegistrationSummary> registrations,
        StoredOutcome storedOutcome
) {

    public static KeyStatusResponse from(Registration registration) {
        // 번호가 없는 행은 등록이 아니라 등록 전에 남긴 취소 표식이다
        List<RegistrationSummary> registrations = registration.externalNumber() == null
                ? List.of()
                : List.of(RegistrationSummary.from(registration));
        return new KeyStatusResponse(
                registration.externalKey(),
                registration.canceledAt(),
                registrations,
                registration.isActive() ? StoredOutcome.SUCCESS : null);
    }

    /** 등록 한 건의 요약. 명세의 by-key 응답은 등록 응답의 전 필드가 아니라 이 넷만 담는다. */
    public record RegistrationSummary(
            String externalNumber,
            RegistrationStatus status,
            Instant confirmedAt,
            Instant canceledAt
    ) {

        static RegistrationSummary from(Registration registration) {
            return new RegistrationSummary(
                    registration.externalNumber(),
                    registration.status(),
                    registration.confirmedAt(),
                    registration.canceledAt());
        }
    }

    /**
     * 재생 대상으로 저장된 결과. 일시 실패는 저장하지 않으므로 성공뿐이다.
     * 업무 거절(REJECTED)은 두지 않기로 했다 — Mock 은 영구 실패를 만들지 않는다.
     */
    public enum StoredOutcome {
        SUCCESS
    }
}
