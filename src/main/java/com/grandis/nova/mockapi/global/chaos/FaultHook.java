package com.grandis.nova.mockapi.global.chaos;

/**
 * 키를 지정해 걸어둔 결함이 발동할 차례인지 알려준다.
 *
 * <p>결함은 둘이다. "커밋 후 응답 유실" 은 지연·실패율로 만들 수 없다 — 주입한 실패는 모두 커밋 전이라
 * 등록이 저장되지 않기 때문이다. "느린 성공" 은 워커가 포기한 뒤 늦게 커밋되는 등록을 지정한 키 하나로 만든다.
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

    /**
     * 느린 성공 결함이 걸린 키면 정한 시간만큼 기다렸다가 돌아온다. 걸려 있지 않으면 바로 돌아온다.
     *
     * <p><b>트랜잭션 밖 · 키 행 잠금 전에 부른다</b> — 지연 주입 뒤, 중복 키 재시도({@code DuplicateKeyRetry}) 앞이다.
     * 잠금 전이어야 기다리는 동안 키 조회가 404 이고, 같은 키 재시도 · 취소 표식이 끼어들 수 있다. 지연 주입보다
     * 앞이면 기다린 뒤 주사위에 걸려 "느린 실패" 가 된다.
     *
     * <p>결함은 <b>기다리기 전에 꺼낸다</b> — 꺼내는 순간 한 번만 발동한다. 기다린 뒤 풀면 그사이 들어온 같은 키
     * 재시도도 결함을 보고 같이 기다린다. 기다린 시간은 {@code X-Mock-Injected-Latency-Ms} 에 더한다.
     *
     * @param externalKey 등록하려는 키
     * @return 기다린 시간(ms). 결함이 없으면 0
     * @throws com.grandis.nova.mockapi.global.error.MockException 기다리는 중 중단되면 500(저장 없음)
     */
    long holdBeforeCommit(String externalKey);
}
