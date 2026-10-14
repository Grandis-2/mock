package com.grandis.nova.mockapi.control.application;

/**
 * 초기화가 지운 행 수.
 *
 * @param registrations 등록 원장 행. 등록과 취소 표식이 같은 표의 행이라 둘을 합한 수다
 * @param drawEntries   럭키드로우 응모
 * @param draws         럭키드로우 회차
 */
public record ResetResult(long registrations, long drawEntries, long draws) {
}
