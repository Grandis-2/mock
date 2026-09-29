package com.grandis.nova.mockapi.control.api;

/**
 * 초기화 결과.
 *
 * @param deletedCount 지운 행 수. 등록과 취소 표식이 같은 표의 행이라 둘을 합한 수다
 */
public record ResetResponse(long deletedCount) {
}
