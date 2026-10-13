package com.grandis.nova.mockapi.load;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 부하가 끝난 뒤 등록 원장을 통째로 떠 온다. 키 → 예약번호(취소 표식이면 null).
 *
 * <p><b>왜 DB 를 읽는가.</b> 클라이언트가 본 것만으로는 합격을 판정할 수 없다. 요구사항 8장이
 * "건수 합계만 비교하지 않음" 으로 정했고, 실제로 201 키 하나가 빠지고 5xx 키 하나가 들어가도
 * 개수는 같다. 키로 맞춰야 그 뒤집힘이 드러난다.
 *
 * <p><b>왜 목록 API 가 아니라 DB 인가.</b> 목록 API({@code GET /external/reservations})는 정합성
 * 검사용이다. 하네스는 Mock 을 채점하는 쪽이라 시험 대상(Mock)의 응답에 기대지 않고 원장 자체를 본다 —
 * API 로 읽으면 Mock 의 버그가 판정에도 같이 숨는다. 그래서 {@code external_mock} 스키마를
 * <b>읽기 전용으로</b> 직접 조회한다. 새 의존성은 필요 없다 — MySQL 드라이버가 {@code runtimeOnly} 라
 * Gradle 이 시험 런타임에 물려준다.
 *
 * <p>판정은 이 맵을 받아 {@link LoadReport} 가 한다. DB 접속과 판정을 한 클래스에 두면
 * 판정 로직을 DB 없이 확인할 수 없게 된다.
 */
public record RegistrationSnapshot(
        Map<String, String> numbersByKey,
        boolean quiesced,
        Duration waited,
        String commitDurability
) {

    /** 원장만 있는 스냅숏. 커밋 동기화 설정은 모른다("?"). 시험에서 쓴다. */
    public RegistrationSnapshot(Map<String, String> numbersByKey, boolean quiesced, Duration waited) {
        this(numbersByKey, quiesced, waited, "?");
    }

    /** 행 수를 이 간격으로 다시 센다. */
    private static final Duration POLL = Duration.ofSeconds(1);

    /** 이만큼 행 수가 변하지 않으면 잦아든 것으로 본다. */
    private static final Duration STABLE_FOR = Duration.ofSeconds(8);

    /** 여기까지 기다려도 안 멎으면 그대로 뜬다. 멎지 않았다는 사실 자체가 보고할 내용이다. */
    private static final Duration GIVE_UP_AFTER = Duration.ofMinutes(2);

    private static final String COUNT = "select count(*) from preorder_registrations";
    private static final String ROWS = "select external_key, external_number from preorder_registrations";

    /**
     * 서버가 잦아든 뒤에 떠 온다.
     *
     * <p><b>클라이언트가 멈춰도 서버는 계속 처리한다.</b> 실행 직후에 세면 아직 커밋 중인 요청이
     * 빠진다 — 탐색 실행에서 {@code baseline} 이 종료 직후 4,527건이었다가 잦아든 뒤 4,749건이 됐다.
     * 222건이 그 뒤에 커밋됐다는 뜻이고, 시나리오를 연달아 돌리면 그 잔여가 다음 집계에 섞인다.
     *
     * <p>끝까지 안 멎으면 기다리기를 그만두고 {@code quiesced=false} 로 표시한다. 그때의 숫자는
     * 움직이는 중에 찍은 것이므로 보고서가 그렇게 밝혀야 한다.
     */
    public static RegistrationSnapshot take(String jdbcUrl, String user, String password) {
        Instant startedAt = Instant.now();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password)) {
            boolean quiesced = awaitQuiesce(connection, startedAt);
            return new RegistrationSnapshot(
                    readRows(connection), quiesced, Duration.between(startedAt, Instant.now()),
                    readDurability(connection));
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "등록 원장을 읽지 못했습니다 — " + jdbcUrl + " : " + e.getMessage(), e);
        }
    }

    /** 행 수가 {@link #STABLE_FOR} 동안 그대로면 true. 상한까지 기다려도 안 멎으면 false. */
    private static boolean awaitQuiesce(Connection connection, Instant startedAt) throws SQLException {
        int stableSamples = (int) (STABLE_FOR.toMillis() / POLL.toMillis());
        long previous = -1;
        int unchanged = 0;

        while (Duration.between(startedAt, Instant.now()).compareTo(GIVE_UP_AFTER) < 0) {
            long count = count(connection);
            unchanged = count == previous ? unchanged + 1 : 0;
            previous = count;
            if (unchanged >= stableSamples) {
                return true;
            }
            try {
                Thread.sleep(POLL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static long count(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(COUNT)) {
            return rows.next() ? rows.getLong(1) : 0L;
        }
    }

    /**
     * MySQL 의 커밋 동기화 설정. 보고서의 실행 조건에 적는다.
     *
     * <p>판정 숫자를 가장 크게 흔든 것이 이 설정이었다. 커밋마다 디스크 동기화(1 · 1)를 하면 Docker Desktop 디스크에서
     * COMMIT 이 평균 29ms · p99 200ms 까지 걸려, 풀이 막히고 결과 불명이 수천 건 났다. 완화(2 · 0)하면 0.49ms 다
     * (2026-10-01). 보고서에 없으면 다른 설정으로 돌린 숫자를 같은 조건으로 오해한다.
     */
    private static String readDurability(Connection connection) {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT @@innodb_flush_log_at_trx_commit, @@sync_binlog")) {
            rs.next();
            return "innodb_flush_log_at_trx_commit=" + rs.getString(1) + " · sync_binlog=" + rs.getString(2);
        } catch (SQLException e) {
            return "읽지 못함 (" + e.getMessage() + ")";
        }
    }

    /** 번호가 null 인 행(등록 전 취소 표식)도 담는다. 키가 있다는 것 자체가 판정에 쓰인다. */
    private static Map<String, String> readRows(Connection connection) throws SQLException {
        Map<String, String> byKey = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(ROWS)) {
            while (rows.next()) {
                byKey.put(rows.getString(1), rows.getString(2));
            }
        }
        return byKey;
    }

    public int rowCount() {
        return numbersByKey.size();
    }

    public boolean has(String externalKey) {
        return numbersByKey.containsKey(externalKey);
    }

    public String numberOf(String externalKey) {
        return numbersByKey.get(externalKey);
    }
}
