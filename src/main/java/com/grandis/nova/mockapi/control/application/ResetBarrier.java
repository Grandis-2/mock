package com.grandis.nova.mockapi.control.application;

import com.grandis.nova.mockapi.global.error.ErrorCode;
import com.grandis.nova.mockapi.global.error.MockException;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/**
 * 초기화와 진행 중인 등록 · 취소를 서로 막는 장벽.
 *
 * <p>막지 않으면 이렇게 된다 — 지연 중인 등록 K → K 취소(표식) → 초기화(표식까지 삭제) → 지연이 끝난
 * 등록이 커밋 → <b>취소 성공을 받은 K 가 ACTIVE 로 살아난다.</b> 지연 · 결함의 유지 시간은 트랜잭션
 * 밖이라 DB 잠금으로는 막을 수 없다.
 *
 * <p>읽기 · 쓰기 잠금 하나로 막는다. 등록 · 취소는 처리하는 동안 읽기 잠금을 잡고(서로는 막지 않는다),
 * 초기화는 쓰기 잠금을 <b>기다리지 않고</b> 시도한다. 잡히지 않으면 진행 중인 요청이 있다는 뜻이라
 * 바로 거절한다. "진행 중 0건인지 세고 지운다" 로 하면 세는 순간과 지우는 순간 사이에 요청이 끼어들 수
 * 있는데, 잠금은 확인과 삭제를 한 번에 묶는다. 초기화가 잠금을 쥔 동안 들어온 요청은 삭제가 끝날 때까지
 * 기다렸다가 진행한다(수 ms).
 *
 * <p>{@code TIMEOUT} · 결함으로 붙잡힌 요청도 진행 중으로 센다. 요청 전체를 감싸는 쪽이 단순하고 확실하다.
 * 그만큼 붙잡힌 요청이 있으면 유지 시간(기본 7초) 동안 초기화가 거절된다.
 */
@Component
public class ResetBarrier {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** 등록 · 취소 한 건이 시작할 때. 초기화가 진행 중이면 끝날 때까지 기다린다. */
    public void enter() {
        lock.readLock().lock();
    }

    /** 등록 · 취소 한 건이 끝날 때. 같은 스레드에서 {@link #enter()} 와 짝을 맞춘다. */
    public void exit() {
        lock.readLock().unlock();
    }

    /** 지금 진행 중인 등록 · 취소 건수. 거절 메시지와 시험에 쓴다. */
    public int inFlight() {
        return lock.getReadLockCount();
    }

    /**
     * 진행 중인 등록 · 취소가 없을 때만 {@code reset} 을 실행한다. 끝날 때까지 새 등록 · 취소를 붙잡아 둔다.
     *
     * <p>{@code reset} 은 트랜잭션 경계 바깥에서 감싸야 한다. 안에서 잡으면 잠금이 커밋보다 먼저 풀린다.
     *
     * @throws MockException 진행 중인 요청이 있으면 409 {@link ErrorCode#RESET_BUSY}
     */
    public long exclusively(LongSupplier reset) {
        if (!lock.writeLock().tryLock()) {
            throw new MockException(ErrorCode.RESET_BUSY,
                    "진행 중인 등록 · 취소가 %d건 있어 초기화할 수 없습니다. 끝난 뒤 다시 부르세요."
                            .formatted(inFlight()));
        }
        try {
            return reset.getAsLong();
        } finally {
            lock.writeLock().unlock();
        }
    }
}
