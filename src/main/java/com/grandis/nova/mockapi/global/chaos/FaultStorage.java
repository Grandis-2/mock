package com.grandis.nova.mockapi.global.chaos;

/**
 * 키마다 건 결함을 두는 곳. 한 대면 메모리({@link MemoryFaultStorage}), 여러 대면 Redis({@link RedisFaultStorage}).
 *
 * <p>여러 대를 메모리로 띄우면 결함을 건 서버와 그 키의 등록을 받은 서버가 달라 발동하지 않는다. 그래서 여러 대일 때는
 * 모든 서버가 같이 보는 곳에 둔다.
 */
public interface FaultStorage {

    /** 걸어 둔 결함 하나. 기다리는 시간은 느린 성공에만 쓴다(응답 유실은 0). */
    record StoredFault(FaultType type, long delayMs) {
    }

    /** 같은 키에 이미 결함이 있으면 새 결함으로 바꾼다. */
    void put(String externalKey, StoredFault fault);

    /**
     * 그 종류의 결함이 걸려 있으면 <b>꺼내면서 지운다</b>. 다른 종류의 결함은 그대로 둔다. 동시에 꺼내도 한 쪽만 받는다 —
     * 나눠 하면 같은 키의 동시 등록 두 건이 모두 받아 결함이 두 번 발동한다.
     *
     * @return 꺼낸 결함. 없으면 null
     */
    StoredFault take(String externalKey, FaultType type);

    /** 결함이 걸려 있는가. 시험이 쓴다. */
    boolean contains(String externalKey);

    /** 걸려 있는 결함을 모두 버린다. 초기화가 부른다. */
    void clear();
}
