package com.grandis.nova.mockapi.registration;

import com.grandis.nova.mockapi.global.error.ErrorCode;
import com.grandis.nova.mockapi.global.error.MockException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 중복 키(1062)를 받으면 새 트랜잭션으로 다시 시도한다. 등록과 취소가 같이 쓴다.
 *
 * <p>같은 새 키로 등록과 취소가 동시에 들어오면 READ COMMITTED 에는 갭 락이 없어 둘 다 "없음" 을 보고
 * INSERT 로 간다. 진 쪽의 중복 키는 오류가 아니라 정상 분기다. 그 트랜잭션은 롤백 전용이 되므로
 * 트랜잭션을 나와 새로 시작해야 한다 — 그래서 이 루프는 {@code @Transactional} 밖에서 돈다.
 */
final class DuplicateKeyRetry {

    private static final Logger log = LoggerFactory.getLogger(DuplicateKeyRetry.class);

    /**
     * 재시도 상한. 키가 겹친 경우는 다음 시도에서 행을 찾아 끝나고, 번호가 겹칠 확률은
     * 하루 180만 건에서도 0.02% 이하라 한 번 더 하면 끝난다. 상한은 무한 루프를 막는 안전장치다.
     */
    static final int MAX_ATTEMPTS = 5;

    static final String RETRY_EXHAUSTED = "중복 키 재시도 상한(" + MAX_ATTEMPTS + "회)을 넘었습니다.";

    private DuplicateKeyRetry() {
    }

    /**
     * @param key     로그용
     * @param attempt 한 번의 시도. 트랜잭션 하나로 끝나야 한다
     */
    static <T> T run(String key, Supplier<T> attempt) {
        for (int count = 1; ; count++) {
            try {
                return attempt.get();
            } catch (DataIntegrityViolationException e) {
                // 중복 키가 아닌 무결성 위반(CHECK · NOT NULL)은 Mock 의 버그다. 그대로 올려 로그에 남긴다.
                if (!DuplicateKey.isCause(e)) {
                    throw e;
                }
                // 상한을 넘기면 이름 있는 500 으로 바꾼다. 그냥 올리면 catch-all 의 기본 문장이 나가
                // 부하 시험 결과에서 "주입한 실패" 와 구분되지 않는다. 워커는 똑같이 재시도한다.
                if (count >= MAX_ATTEMPTS) {
                    log.warn("중복 키 재시도 상한 초과 (key={}, attempts={})", key, count, e);
                    throw new MockException(ErrorCode.UPSTREAM_UNAVAILABLE, RETRY_EXHAUSTED);
                }
                // 오류가 아니라 정상 분기다. 같은 키를 다른 요청이 먼저 만들었거나 번호가 겹쳤다.
                log.debug("중복 키 — 새 트랜잭션으로 다시 시도한다 (key={}, attempt={})", key, count);
            }
        }
    }
}
