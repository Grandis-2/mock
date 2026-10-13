package com.grandis.nova.mockapi.global.chaos;

/**
 * 설정된 지연만큼 기다리고, 확률에 걸리면 일시 실패를 던진다.
 *
 * <p><strong>트랜잭션 밖에서</strong> 부른다. 트랜잭션 안에서 기다리면 DB 커넥션이 그만큼 묶여
 * 풀이 마른다. 실패는 커밋 전이라 아무것도 저장되지 않는다.
 *
 * <p>구현은 제어 파트가 맡는다.
 */
public interface FailureInjector {

    /**
     * @param snapshot 이 시도가 시작할 때 고정한 설정
     * @throws com.grandis.nova.mockapi.global.error.MockException 실패에 걸렸을 때
     */
    void apply(ConfigSnapshot snapshot);
}
