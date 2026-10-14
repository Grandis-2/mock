package com.grandis.nova.mockapi.control.api;

import com.grandis.nova.mockapi.control.application.ResetResult;

/**
 * 초기화 결과.
 *
 * <p>{@code deletedCount} 는 럭키드로우 전부터 있던 칸이라 뜻을 바꾸지 않는다(등록 원장만). 럭키드로우 건수는 칸을 따로 둔다 —
 * 합치면 부하 시험 · 시연에서 "등록 몇 건을 지웠나" 를 읽던 쪽이 엉뚱한 수를 본다.
 *
 * @param deletedCount       지운 등록 원장 행 수. 등록과 취소 표식이 같은 표의 행이라 둘을 합한 수다
 * @param deletedDrawEntries 지운 럭키드로우 응모 수
 * @param deletedDraws       지운 럭키드로우 회차 수
 */
public record ResetResponse(long deletedCount, long deletedDrawEntries, long deletedDraws) {

    static ResetResponse from(ResetResult result) {
        return new ResetResponse(result.registrations(), result.drawEntries(), result.draws());
    }
}
