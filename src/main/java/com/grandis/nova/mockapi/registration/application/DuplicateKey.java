package com.grandis.nova.mockapi.registration.application;

import java.sql.SQLException;
import org.springframework.dao.DuplicateKeyException;

/**
 * 예외의 원인이 중복 키인지 알아본다.
 *
 * <p>같은 새 키로 등록과 취소가 동시에 들어오면 READ COMMITTED 에는 갭 락이 없어 둘 다 "없음" 을
 * 보고 INSERT 로 간다. 진 쪽이 받는 중복 키는 오류가 아니라 "다른 요청이 먼저 만들었다" 는 정보다.
 * 번호가 겹쳤을 때도 같은 예외가 난다.
 *
 * <p>그 밖의 무결성 위반(CHECK · NOT NULL)은 Mock 의 버그라 재시도하면 안 된다. 그래서 무결성
 * 위반 전체가 아니라 중복 키만 골라낸다.
 */
final class DuplicateKey {

    /** MySQL ER_DUP_ENTRY. */
    private static final int MYSQL_DUPLICATE_ENTRY = 1062;

    /** 표준 SQLSTATE 유니크 위반. H2 가 쓴다. MySQL 은 23000 이라 NOT NULL 위반과 구분되지 않는다. */
    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    private DuplicateKey() {
    }

    static boolean isCause(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof DuplicateKeyException) {
                return true;
            }
            if (t instanceof SQLException sql
                    && (sql.getErrorCode() == MYSQL_DUPLICATE_ENTRY
                    || SQLSTATE_UNIQUE_VIOLATION.equals(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }
}
