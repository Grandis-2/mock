package com.grandis.nova.mockapi.global.chaos;

/**
 * 키를 지정해 걸어둔 결함이 발동할 차례인지 알려준다.
 *
 * <p>지금 있는 결함은 "커밋 후 응답 유실" 하나다. 지연·실패율로는 이 상황을 만들 수 없다.
 * 주입한 실패는 모두 커밋 전이라 등록이 저장되지 않기 때문이다.
 *
 * <p>구현은 {@link InMemoryFaultStore} 다.
 */
public interface FaultHook {

    /**
     * 등록을 커밋한 직후에 부른다. true 면 응답 없이 연결을 끊는다.
     * 결함은 한 번 발동하면 자동 해제된다.
     *
     * @param externalKey 방금 등록한 키
     */
    boolean consumeResponseLost(String externalKey);
}
