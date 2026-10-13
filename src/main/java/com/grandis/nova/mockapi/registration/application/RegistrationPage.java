package com.grandis.nova.mockapi.registration.application;

import com.grandis.nova.mockapi.registration.domain.Registration;
import java.util.List;

/**
 * 목록 조회 한 페이지.
 *
 * @param items      외부 키 순서의 행. 등록 행과 취소 표식 행이 섞여 있다
 * @param nextCursor 다음 페이지를 부를 때 보낼 커서(이 페이지 마지막 행의 키). 더 없으면 {@code null}
 */
public record RegistrationPage(List<Registration> items, String nextCursor) {
}
