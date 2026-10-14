package com.grandis.nova.mockapi.draw.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.mockapi.draw.domain.DrawEntryRepository;
import com.grandis.nova.mockapi.draw.domain.DrawRepository;
import com.grandis.nova.mockapi.draw.domain.DrawStatus;
import com.grandis.nova.mockapi.global.chaos.FailureMode;
import com.grandis.nova.mockapi.global.chaos.MockConfigStore;
import com.grandis.nova.mockapi.global.config.MockProperties;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 럭키드로우 응모 API. API 안(4-내 작업/럭키드로우 Mock API 안.md) 3장의 처리 순서를 HTTP 로 본다.
 *
 * <p>H2 라 동시성은 여기서 보지 않는다. 처음 보는 회차 · 같은 응모 · 같은 회원의 동시 요청은
 * {@code DrawEntryConcurrencyTest} 가 진짜 MySQL 로 본다. 여기서는 순서대로 부를 때의 판정만 본다.
 *
 * <p>H2 는 같은 JVM 의 다른 시험과 공유되므로 회차 · 응모 · 회원 id 는 시험마다 새로 만든다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DrawEntryApiTest {

    private static final String PATH = "/external/draw-entries";

    /** API 가 내는 시각 모양. UTC 밀리초 세 자리. */
    private static final String UTC_MILLIS = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DrawRepository draws;

    @Autowired
    private DrawEntryRepository entries;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockConfigStore store;

    @Autowired
    private MockProperties properties;

    /** 설정을 바꾼 시험은 스스로 되돌린다(팀 규칙). */
    @AfterEach
    void restoreDefaults() {
        store.update(properties.registerLatencyMs(), properties.failureRate(), properties.failureMode());
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private static String body(String entryId, String drawId, String customerId) {
        return """
                {"entryId":"%s","drawId":"%s","customerId":"%s"}""".formatted(entryId, drawId, customerId);
    }

    private ResultActions enter(String entryId, String drawId, String customerId) throws Exception {
        return mvc.perform(request(entryId).content(body(entryId, drawId, customerId)));
    }

    private static MockHttpServletRequestBuilder request(String key) {
        return post(PATH)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON);
    }

    private int entryCount(String drawId) {
        return jdbc.queryForObject("select count(*) from draw_entries where draw_id = ?", Integer.class, drawId);
    }

    /** 추첨 API 가 생기기 전이라 회차를 직접 추첨한 상태로 바꾼다. */
    private void markDrawn(String drawId) {
        jdbc.update("update draws set status = 'DRAWN', winner_count = 1, drawn_at = ? where draw_id = ?",
                Timestamp.from(Instant.now()), drawId);
    }

    @Test
    @DisplayName("첫 응모 - 201, 재생 아님, 처음 보는 회차를 OPEN 으로 만든다")
    void firstEntryOpensDraw() throws Exception {
        String entryId = newId();
        String drawId = newId();
        String customerId = newId();
        int version = store.snapshot().configVersion();

        enter(entryId, drawId, customerId)
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotent-Replay", "false"))
                .andExpect(header().string("X-Mock-Config-Version", String.valueOf(version)))
                .andExpect(jsonPath("$.entryId").value(entryId))
                .andExpect(jsonPath("$.drawId").value(drawId))
                .andExpect(jsonPath("$.customerId").value(customerId))
                .andExpect(jsonPath("$.receivedAt", matchesPattern(UTC_MILLIS)));

        assertThat(draws.findById(drawId)).get().satisfies(draw -> {
            assertThat(draw.status()).isEqualTo(DrawStatus.OPEN);
            assertThat(draw.winnerCount()).isNull();
            assertThat(draw.drawnAt()).isNull();
        });
        assertThat(entryCount(drawId)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 응모를 다시 보내면 201 재생 - 처음 응답과 같고 응모 수는 그대로")
    void sameEntryReplays() throws Exception {
        String entryId = newId();
        String drawId = newId();
        String customerId = newId();
        String first = enter(entryId, drawId, customerId).andReturn().getResponse().getContentAsString();

        String second = enter(entryId, drawId, customerId)
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotent-Replay", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(entryCount(drawId)).isEqualTo(1);
    }

    @Test
    @DisplayName("응모가 한 건씩 쌓인다 - 회차는 하나, 응모는 받은 만큼")
    void entriesAccumulate() throws Exception {
        String drawId = newId();
        for (int i = 0; i < 3; i++) {
            enter(newId(), drawId, newId()).andExpect(status().isCreated());
        }

        assertThat(entryCount(drawId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from draws where draw_id = ?", Integer.class, drawId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("같은 응모 id 에 다른 회원 - 422, 저장된 응모는 그대로")
    void sameEntryDifferentCustomer() throws Exception {
        String entryId = newId();
        String drawId = newId();
        String customerId = newId();
        enter(entryId, drawId, customerId).andExpect(status().isCreated());

        enter(entryId, drawId, newId())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("KEY_PAYLOAD_MISMATCH"))
                .andExpect(jsonPath("$.errorMessage", containsString("customerId")))
                .andExpect(jsonPath("$.replayable").value(false))
                .andExpect(jsonPath("$.externalNumber").value(nullValue()));

        assertThat(entries.findById(entryId)).get()
                .satisfies(entry -> assertThat(entry.customerId()).isEqualTo(UUID.fromString(customerId)));
    }

    @Test
    @DisplayName("같은 응모 id 에 다른 회차 - 422, 그 회차는 만들어지지 않는다")
    void sameEntryDifferentDraw() throws Exception {
        String entryId = newId();
        String customerId = newId();
        enter(entryId, newId(), customerId).andExpect(status().isCreated());
        String otherDraw = newId();

        enter(entryId, otherDraw, customerId)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("KEY_PAYLOAD_MISMATCH"))
                .andExpect(jsonPath("$.errorMessage", containsString("drawId")));

        // 거절과 함께 롤백되어 빈 회차가 남지 않는다
        assertThat(draws.findById(otherDraw)).isEmpty();
    }

    @Test
    @DisplayName("같은 회차에 같은 회원의 다른 응모 - 409 CUSTOMER_ALREADY_ENTERED, 저장하지 않는다")
    void sameCustomerOtherEntry() throws Exception {
        String drawId = newId();
        String customerId = newId();
        enter(newId(), drawId, customerId).andExpect(status().isCreated());
        String second = newId();

        enter(second, drawId, customerId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CUSTOMER_ALREADY_ENTERED"))
                .andExpect(jsonPath("$.replayable").value(true));

        assertThat(entries.findById(second)).isEmpty();
        assertThat(entryCount(drawId)).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 회차면 같은 회원도 받는다")
    void sameCustomerOtherDraw() throws Exception {
        String customerId = newId();
        enter(newId(), newId(), customerId).andExpect(status().isCreated());

        enter(newId(), newId(), customerId).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("추첨한 회차 - 새 응모는 409 DRAW_CLOSED, 이미 받은 응모의 재전송은 201 재생")
    void drawnDraw() throws Exception {
        String drawId = newId();
        String entryId = newId();
        String customerId = newId();
        enter(entryId, drawId, customerId).andExpect(status().isCreated());
        markDrawn(drawId);
        String late = newId();

        enter(late, drawId, newId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DRAW_CLOSED"))
                .andExpect(jsonPath("$.replayable").value(true));
        assertThat(entries.findById(late)).isEmpty();

        // 재생이 추첨 여부보다 먼저다 — 이미 받은 응모를 거절하면 워커는 들어간 응모를 안 들어간 것으로 믿는다
        enter(entryId, drawId, customerId)
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotent-Replay", "true"));
    }

    @Test
    @DisplayName("주입한 실패 - 500, 응모도 회차도 저장하지 않는다")
    void injectedFailureSavesNothing() throws Exception {
        store.update(0, 1.0, FailureMode.HTTP_5XX);
        String entryId = newId();
        String drawId = newId();

        enter(entryId, drawId, newId())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("UPSTREAM_UNAVAILABLE"));

        assertThat(entries.findById(entryId)).isEmpty();
        assertThat(draws.findById(drawId)).isEmpty();
    }

    @Test
    @DisplayName("본문 entryId 와 Idempotency-Key 가 다르면 400, 저장하지 않는다")
    void keyMismatch() throws Exception {
        String key = newId();
        String drawId = newId();

        mvc.perform(request(key).content(body(newId(), drawId, newId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errorMessage").value("entryId 가 Idempotency-Key 와 다릅니다."));

        assertThat(draws.findById(drawId)).isEmpty();
    }

    @Test
    @DisplayName("회차 id 형식이 틀리면 400 - 회차 조회 · 추첨의 경로에 넣을 수 없는 값")
    void invalidDrawId() throws Exception {
        String entryId = newId();

        enter(entryId, "draw/1", newId())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage", containsString("drawId")));
    }

    @Test
    @DisplayName("회원 id 가 소문자 표준 UUID 가 아니면 400")
    void customerIdNotLowercaseUuid() throws Exception {
        String entryId = newId();

        enter(entryId, newId(), newId().toUpperCase())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage", containsString("customerId")));
    }

    @Test
    @DisplayName("명세에 없는 필드 · 빠진 필드는 400")
    void unknownOrMissingField() throws Exception {
        String key = newId();

        mvc.perform(request(key).content("""
                        {"entryId":"%s","drawId":"%s","customerId":"%s","winner":true}"""
                        .formatted(key, newId(), newId())))
                .andExpect(status().isBadRequest());
        mvc.perform(request(key).content("""
                        {"entryId":"%s","drawId":"%s"}""".formatted(key, newId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage", containsString("customerId")));
    }

    @Test
    @DisplayName("Idempotency-Key 헤더가 없으면 400")
    void missingKeyHeader() throws Exception {
        mvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(body(newId(), newId(), newId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage", containsString("Idempotency-Key")));
    }

    @Test
    @DisplayName("Accept 에 JSON 이 없으면 처리 전에 400 - 응모를 저장한 뒤 응답을 못 쓰는 일이 없다")
    void notAcceptableBeforeSaving() throws Exception {
        String entryId = newId();
        String drawId = newId();

        mvc.perform(post(PATH)
                        .header("Idempotency-Key", entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_PLAIN)
                        .content(body(entryId, drawId, newId())))
                .andExpect(status().isBadRequest());

        assertThat(entries.findById(entryId)).isEmpty();
        assertThat(draws.findById(drawId)).isEmpty();
    }
}
