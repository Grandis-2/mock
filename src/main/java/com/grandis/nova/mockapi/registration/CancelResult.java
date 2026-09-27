package com.grandis.nova.mockapi.registration;

/**
 * 취소 처리 결과.
 *
 * @param registration          취소된 키의 행. 등록 행이거나 이번에 남긴 취소 표식이다
 * @param hadActiveRegistration 이번 호출이 활성 등록을 실제로 껐는지. 이미 취소된 행이거나 표식만 남겼으면 false
 */
public record CancelResult(Registration registration, boolean hadActiveRegistration) {
}
