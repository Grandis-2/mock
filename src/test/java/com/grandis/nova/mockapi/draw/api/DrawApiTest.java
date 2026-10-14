package com.grandis.nova.mockapi.draw.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.mockapi.draw.domain.DrawRepository;
import com.grandis.nova.mockapi.draw.domain.DrawStatus;
import com.grandis.nova.mockapi.global.chaos.FailureMode;
import com.grandis.nova.mockapi.global.chaos.MockConfigStore;
import com.grandis.nova.mockapi.global.config.MockProperties;
import java.util.ArrayList;
import java.util.List;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 럭키드로우 추첨 API. API 안 5장의 처리 순서를 HTTP 로 본다.
 *
 * <p>H2 라 동시성은 여기서 보지 않는다. 같은 추첨의 동시 요청 · 추첨과 응모가 엇갈리는 경우는 {@code DrawConcurrencyTest} 가
 * 진짜 MySQL 로 본다. H2 는 같은 JVM 의 다른 시험과 공유되므로 id 는 시험마다 새로 만든다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DrawApiTest {

    private static final String UTC_MILLIS = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private DrawRepository draws;

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

    private ResultActions enter(String entryId, String drawId) throws Exception {
        return mvc.perform(post("/external/draw-entries")
                .header("Idempotency-Key", entryId)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("""
                        {"entryId":"%s","drawId":"%s","customerId":"%s"}""".formatted(entryId, drawId, newId())));
    }

    /** 응모를 {@code count} 건 넣고 응모 id 를 돌려준다. */
    private List<String> openDraw(String drawId, int count) throws Exception {
        List<String> entryIds = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String entryId = newId();
            enter(entryId, drawId).andExpect(status().isCreated());
            entryIds.add(entryId);
        }
        return entryIds;
    }

    private ResultActions draw(String drawId, String body) throws Exception {
        return mvc.perform(post("/external/draws/" + drawId + "/draw")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private JsonNode bodyOf(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private int winnerRows(String drawId) {
        return jdbc.queryForObject("select count(*) from draw_entries where draw_id = ? and winner_rank is not null",
                Integer.class, drawId);
    }

    @Test
    @DisplayName("추첨 - 200, DRAWN, 당첨 인원만큼 겹치지 않게 그 회차 응모 중에서, 조회도 같은 결과")
    void drawsWinners() throws Exception {
        String drawId = newId();
        List<String> entryIds = openDraw(drawId, 5);
        int version = store.snapshot().configVersion();

        ResultActions result = draw(drawId, """
                {"winnerCount":2}""")
                .andExpect(status().isOk())
                .andExpect(header().string("X-Idempotent-Replay", "false"))
                .andExpect(header().string("X-Mock-Config-Version", String.valueOf(version)))
                .andExpect(jsonPath("$.drawId").value(drawId))
                .andExpect(jsonPath("$.status").value("DRAWN"))
                .andExpect(jsonPath("$.entryCount").value(5))
                .andExpect(jsonPath("$.winnerCount").value(2))
                .andExpect(jsonPath("$.drawnAt", matchesPattern(UTC_MILLIS)));

        List<String> winners = bodyOf(result).get("winnerEntryIds").valueStream().map(JsonNode::asString).toList();
        assertThat(winners).hasSize(2).doesNotHaveDuplicates();
        assertThat(entryIds).containsAll(winners);
        assertThat(winnerRows(drawId)).isEqualTo(2);
        // 조회가 같은 당첨을 같은 순서로 준다 — worker 가 어느 쪽을 읽어도 같다
        String drawn = result.andReturn().getResponse().getContentAsString();
        String found = mvc.perform(get("/external/draws/" + drawId).accept(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
        assertThat(found).isEqualTo(drawn);
    }

    @Test
    @DisplayName("같은 추첨을 다시 보내면 다시 뽑지 않고 200 재생 - 처음 결과와 같다")
    void sameDrawReplays() throws Exception {
        String drawId = newId();
        openDraw(drawId, 5);
        String first = draw(drawId, """
                {"winnerCount":2}""").andReturn().getResponse().getContentAsString();

        String second = draw(drawId, """
                {"winnerCount":2}""")
                .andExpect(status().isOk())
                .andExpect(header().string("X-Idempotent-Replay", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(winnerRows(drawId)).isEqualTo(2);
    }

    @Test
    @DisplayName("추첨한 회차에 다른 당첨 인원 · 다른 기대 수 - 422, 결과는 그대로")
    void differentDrawAfterDrawn() throws Exception {
        String drawId = newId();
        openDraw(drawId, 5);
        draw(drawId, """
                {"winnerCount":2}""").andExpect(status().isOk());

        draw(drawId, """
                {"winnerCount":3}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("KEY_PAYLOAD_MISMATCH"))
                .andExpect(jsonPath("$.errorMessage", containsString("winnerCount")));
        draw(drawId, """
                {"winnerCount":2,"expectedEntryCount":5}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorMessage", containsString("expectedEntryCount")));

        assertThat(winnerRows(drawId)).isEqualTo(2);
    }

    @Test
    @DisplayName("기대 수가 받은 수와 같으면 추첨, 다르면 409 ENTRY_COUNT_MISMATCH 이고 아무것도 바꾸지 않는다")
    void expectedEntryCount() throws Exception {
        String drawId = newId();
        openDraw(drawId, 3);

        draw(drawId, """
                {"winnerCount":1,"expectedEntryCount":4}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ENTRY_COUNT_MISMATCH"))
                .andExpect(jsonPath("$.errorMessage", containsString("받은 수=3")))
                .andExpect(jsonPath("$.replayable").value(false));
        assertThat(draws.findById(drawId)).get().satisfies(draw -> assertThat(draw.status()).isEqualTo(DrawStatus.OPEN));

        // 기다렸다 다시 — 응모가 하나 더 닿으면 맞는다
        openDraw(drawId, 1);
        draw(drawId, """
                {"winnerCount":1,"expectedEntryCount":4}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entryCount").value(4));
    }

    @Test
    @DisplayName("당첨 인원이 받은 응모 수 이상이면 422 INVALID_WINNER_COUNT - be 는 전원 당첨이면 부르지 않는다")
    void winnerCountNotLessThanEntries() throws Exception {
        String drawId = newId();
        openDraw(drawId, 3);

        for (String body : List.of("""
                {"winnerCount":3}""", """
                {"winnerCount":4}""")) {
            draw(drawId, body)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_WINNER_COUNT"));
        }
        assertThat(winnerRows(drawId)).isZero();
    }

    @Test
    @DisplayName("받은 응모가 없는 회차 - 기대 수가 없으면 404, 있으면 409(받은 수 0). 회차를 만들지 않는다")
    void unknownDraw() throws Exception {
        String drawId = newId();

        draw(drawId, """
                {"winnerCount":1}""")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
        draw(drawId, """
                {"winnerCount":1,"expectedEntryCount":2}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ENTRY_COUNT_MISMATCH"))
                .andExpect(jsonPath("$.errorMessage", containsString("받은 수=0")));

        assertThat(draws.findById(drawId)).isEmpty();
    }

    @Test
    @DisplayName("추첨 뒤 새 응모는 409 DRAW_CLOSED - 추첨 대상이 바뀌지 않는다")
    void entryAfterDraw() throws Exception {
        String drawId = newId();
        openDraw(drawId, 3);
        draw(drawId, """
                {"winnerCount":1}""").andExpect(status().isOk());

        enter(newId(), drawId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DRAW_CLOSED"));

        mvc.perform(get("/external/draws/" + drawId).accept(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.entryCount").value(3));
    }

    @Test
    @DisplayName("주입한 실패 - 500, 추첨하지 않는다")
    void injectedFailureDoesNotDraw() throws Exception {
        String drawId = newId();
        openDraw(drawId, 3);
        store.update(0, 1.0, FailureMode.HTTP_5XX);

        draw(drawId, """
                {"winnerCount":1}""")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("UPSTREAM_UNAVAILABLE"));

        assertThat(draws.findById(drawId)).get().satisfies(draw -> assertThat(draw.status()).isEqualTo(DrawStatus.OPEN));
        assertThat(winnerRows(drawId)).isZero();
    }

    @Test
    @DisplayName("본문 오류 - 당첨 인원 없음 · 0 · 기대 수 0 · 모르는 필드는 400")
    void invalidBody() throws Exception {
        String drawId = newId();
        openDraw(drawId, 3);

        draw(drawId, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage", containsString("winnerCount")));
        draw(drawId, """
                {"winnerCount":0}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage", containsString("winnerCount")));
        draw(drawId, """
                {"winnerCount":1,"expectedEntryCount":0}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage", containsString("expectedEntryCount")));
        draw(drawId, """
                {"winnerCount":1,"seed":7}""")
                .andExpect(status().isBadRequest());

        assertThat(winnerRows(drawId)).isZero();
    }

    @Test
    @DisplayName("회차 id 형식이 틀리면 400")
    void invalidDrawId() throws Exception {
        draw("bad%20id", """
                {"winnerCount":1}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage", containsString("drawId")));
    }
}
