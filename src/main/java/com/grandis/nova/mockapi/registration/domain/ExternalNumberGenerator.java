package com.grandis.nova.mockapi.registration.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * 외부 예약번호를 만든다. {@code R-yyyyMMdd-NNNNNNNNNN} — UTC 발급일 + 10자리 난수.
 *
 * <p>카운터 대신 난수를 쓴다. 카운터는 재기동하면 시드를 다시 잡아야 하고 자정에 되돌려야 하며,
 * Mock 이 한 프로세스라는 전제에 기댄다. 난수는 그런 게 없고, 겹치면 번호 유니크 제약이 중복 키로
 * 떨어뜨려 등록 재시도 루프가 새 번호로 다시 시도한다.
 *
 * <p>10자리인 이유 — 6자리면 하루 100만 개라 부하 시험에서 동난다(하루 18만 건이면 새 번호가 겹칠
 * 확률이 18%). 또 {@code reset} 으로 기록을 지우면 전에 준 번호가 다시 나올 수 있는데, 본 서비스는 받은
 * 번호를 UNIQUE 로 저장하므로 Mock 만 초기화하고 다시 돌리면 그쪽 저장이 깨진다. 10자리면 하루 180만 건을
 * 넣어도 겹칠 확률이 0.02% 이하다.
 *
 * <p><b>재시도할 때마다 새로 불러야 한다.</b> 한 번 뽑은 번호로 다시 시도하면 같은 자리에서 영원히 부딪힌다.
 */
@Component
public class ExternalNumberGenerator {

    private static final DateTimeFormatter ISSUE_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private static final long SEQUENCE_BOUND = 10_000_000_000L;

    public String next(Instant now) {
        long sequence = ThreadLocalRandom.current().nextLong(SEQUENCE_BOUND);
        return "R-" + ISSUE_DATE.format(now) + "-" + String.format("%010d", sequence);
    }
}
