package com.grandis.nova.mockapi.draw.api;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/**
 * 럭키드로우 회차 조회 API. API 안 4장.
 *
 * <p>H2 는 같은 JVM 의 다른 시험과 공유되므로 회차 · 응모 · 회원 id 는 시험마다 새로 만든다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DrawLookupApiTest {

    private static final String UTC_MILLIS = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z";

    @Autowired
    private MockMvc mvc;

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

    private void enter(String entryId, String drawId) throws Exception {
        mvc.perform(post("/external/draw-entries")
                        .header("Idempotency-Key", entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entryId":"%s","drawId":"%s","customerId":"%s"}""".formatted(entryId, drawId, newId())))
                .andExpect(status().isCreated());
    }

    private ResultActions find(String drawId) throws Exception {
        return mvc.perform(get("/external/draws/" + drawId).accept(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("응모 중인 회차 - 200, OPEN, 받은 응모 수, 추첨 칸은 null · 당첨은 빈 배열")
    void openDraw() throws Exception {
        String drawId = newId();
        enter(newId(), drawId);
        enter(newId(), drawId);

        find(drawId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.drawId").value(drawId))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.entryCount").value(2))
                .andExpect(jsonPath("$.winnerCount").value(nullValue()))
                .andExpect(jsonPath("$.winnerEntryIds", empty()))
                .andExpect(jsonPath("$.drawnAt").value(nullValue()));
    }

    @Test
    @DisplayName("추첨한 회차 - DRAWN, 당첨 인원 · 추첨 시각, 당첨 응모는 뽑힌 순서")
    void drawnDraw() throws Exception {
        String drawId = newId();
        String first = newId();
        String second = newId();
        String loser = newId();
        enter(first, drawId);
        enter(second, drawId);
        enter(loser, drawId);
        // 추첨 API 가 생기기 전이라 직접 추첨한 상태로 바꾼다. 뽑힌 순서는 넣은 순서와 다르게 둔다
        jdbc.update("update draws set status = 'DRAWN', winner_count = 2, drawn_at = ? where draw_id = ?",
                Timestamp.from(Instant.now()), drawId);
        jdbc.update("update draw_entries set winner_rank = 1 where entry_id = ?", second);
        jdbc.update("update draw_entries set winner_rank = 2 where entry_id = ?", first);

        find(drawId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAWN"))
                .andExpect(jsonPath("$.entryCount").value(3))
                .andExpect(jsonPath("$.winnerCount").value(2))
                .andExpect(jsonPath("$.winnerEntryIds", contains(second, first)))
                .andExpect(jsonPath("$.drawnAt", matchesPattern(UTC_MILLIS)));
    }

    @Test
    @DisplayName("받은 응모가 없는 회차 - 404 NOT_FOUND")
    void unknownDraw() throws Exception {
        find(newId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("회차 id 형식이 틀리면 404 가 아니라 400 - '아직 응모가 없다' 와 섞이지 않게")
    void invalidDrawId() throws Exception {
        find("bad%20id")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage", containsString("drawId")));
    }

    @Test
    @DisplayName("조회에는 지연 · 실패를 넣지 않는다 - 실패율 100% 여도 200")
    void noFailureInjection() throws Exception {
        String drawId = newId();
        enter(newId(), drawId);
        store.update(0, 1.0, FailureMode.HTTP_5XX);

        find(drawId).andExpect(status().isOk());
    }
}
