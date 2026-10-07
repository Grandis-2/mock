package com.grandis.nova.mockapi.control.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.mockapi.global.chaos.DefaultFailureInjector;
import com.grandis.nova.mockapi.global.chaos.MixedResponse;
import com.grandis.nova.mockapi.global.chaos.MockConfigStore;
import com.grandis.nova.mockapi.global.config.MockProperties;
import com.grandis.nova.mockapi.registration.RegisterBodies;
import com.grandis.nova.mockapi.registration.domain.RegistrationRepository;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 실패 모드 MIXED — 실패 판정에 걸리면 고른 종류 중 하나를 보낸다.
 *
 * <p>모든 시험이 지연 0 · 실패율 1.0 으로 돈다. 실패는 전부 커밋 전이라 원장에 남지 않아야 한다.
 * {@code timeout-hold-ms=0} 은 TIMEOUT 의 표식 헤더를 바로 보려고 둔다(붙잡지 않고 빈 500).
 */
@SpringBootTest(properties = "mock.timeout-hold-ms=0")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class MixedFailureApiTest {

    private static final String CONFIG = "/external/config";
    private static final String MARK = DefaultFailureInjector.INJECTED_FAILURE_HEADER;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MockConfigStore store;

    @Autowired
    private MockProperties properties;

    @Autowired
    private RegistrationRepository repository;

    /** 설정을 바꾼 시험은 스스로 되돌린다(팀 규칙). */
    @AfterEach
    void restoreDefaults() {
        store.update(properties.registerLatencyMs(), properties.failureRate(), properties.failureMode());
    }

    private ResultActions configure(String body) throws Exception {
        return mvc.perform(put(CONFIG).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void mixedOnly(String responses) throws Exception {
        configure("""
                {"registerLatencyMs":0,"failureRate":1.0,"failureMode":"MIXED","mixedResponses":%s}"""
                .formatted(responses))
                .andExpect(status().isOk());
    }

    private ResultActions register(String key) throws Exception {
        return mvc.perform(post("/external/reservations")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(RegisterBodies.of(key)));
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    @Test
    @DisplayName("HTTP_500 — errorCode 가 있는 500. HTTP_5XX 모드와 같은 응답에 표식만 붙는다")
    void http500HasErrorCode() throws Exception {
        mixedOnly("[\"HTTP_500\"]");
        String key = newKey();

        register(key)
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(MARK, "HTTP_500"))
                .andExpect(jsonPath("$.errorCode").value("UPSTREAM_UNAVAILABLE"))
                .andExpect(jsonPath("$.errorMessage").value("외부 시스템을 사용할 수 없습니다."));
        assertThat(repository.findById(key)).isEmpty();
    }

    @Test
    @DisplayName("HTTP_500_NO_BODY — 본문 없는 500. errorCode 가 없어 워커에게는 결과 불명이다")
    void http500WithoutBody() throws Exception {
        mixedOnly("[\"HTTP_500_NO_BODY\"]");
        String key = newKey();

        register(key)
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(MARK, "HTTP_500_NO_BODY"))
                .andExpect(content().string(""));
        assertThat(repository.findById(key)).isEmpty();
    }

    /**
     * 앞단 장비 흉내라 Mock 의 JSON 이 아니라 HTML 이다. 본문을 무조건 JSON 으로 읽는 워커는 여기서 터진다.
     *
     * <p>{@code Content-Type: text/html} 은 여기서 보지 않는다. 스프링이 예외를 처리할 때 Content-Type 헤더를 지우는데,
     * 실제 Tomcat 은 이미 보낸 응답이라 무시하고 MockMvc 의 가짜 응답만 지워진다. 실제 소켓으로는
     * {@link MixedFailureSocketTest} 가 본다.
     */
    @Test
    @DisplayName("HTTP_502 · 503 · 504 — 앞단 장비처럼 HTML 본문, errorCode 없음")
    void gatewayResponsesAreHtml() throws Exception {
        for (MixedResponse kind : new MixedResponse[]{MixedResponse.HTTP_502, MixedResponse.HTTP_503,
                MixedResponse.HTTP_504}) {
            mixedOnly("[\"" + kind + "\"]");
            String key = newKey();

            register(key)
                    .andExpect(status().is(kind.status()))
                    .andExpect(header().string(MARK, kind.name()))
                    .andExpect(content().string(containsString("<h1>" + kind.status() + " ")))
                    .andExpect(content().string(org.hamcrest.Matchers.not(containsString("errorCode"))));
            assertThat(repository.findById(key)).isEmpty();
        }
    }

    @Test
    @DisplayName("목록을 빼면 다섯 종류를 전부 섞는다 — 설정 응답에도 다섯이 보인다")
    void omittedListMixesAll() throws Exception {
        configure("""
                {"registerLatencyMs":0,"failureRate":1.0,"failureMode":"MIXED"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureMode").value("MIXED"))
                .andExpect(jsonPath("$.mixedResponses.length()").value(5));

        // 다섯 중 하나라도 100번 안에 한 번도 안 나올 확률은 5 × 0.8^100 ≈ 1e-9 다
        Set<MixedResponse> seen = EnumSet.noneOf(MixedResponse.class);
        for (int i = 0; i < 100; i++) {
            seen.add(MixedResponse.valueOf(register(newKey()).andReturn().getResponse().getHeader(MARK)));
        }
        assertThat(seen).containsExactlyInAnyOrder(MixedResponse.values());
    }

    @Test
    @DisplayName("고른 종류만 나온다")
    void onlyChosenKinds() throws Exception {
        mixedOnly("[\"HTTP_502\",\"HTTP_504\"]");

        Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 50; i++) {
            MockHttpServletResponse response = register(newKey()).andReturn().getResponse();
            seen.add(response.getStatus() + " " + response.getHeader(MARK));
        }
        assertThat(seen).containsExactlyInAnyOrder("502 HTTP_502", "504 HTTP_504");
    }

    @Test
    @DisplayName("HTTP_5XX · TIMEOUT 도 표식을 붙인다. 정상 응답에는 없다")
    void markOnOtherModesOnly() throws Exception {
        configure("""
                {"registerLatencyMs":0,"failureRate":1.0,"failureMode":"HTTP_5XX"}""")
                .andExpect(jsonPath("$.mixedResponses.length()").value(0));
        register(newKey())
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(MARK, "HTTP_500"))
                .andExpect(jsonPath("$.errorCode").value("UPSTREAM_UNAVAILABLE"));

        configure("""
                {"registerLatencyMs":0,"failureRate":1.0,"failureMode":"TIMEOUT"}""");
        register(newKey())
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(MARK, "TIMEOUT"));

        configure("""
                {"registerLatencyMs":0,"failureRate":0.0,"failureMode":"MIXED"}""");
        register(newKey())
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(MARK));
    }

    /** be 로그와 키로 맞대 보려고 남긴다. 실패하지 않은 요청은 남기지 않는다. */
    @Test
    @DisplayName("주입한 실패는 종류 · 키 · 모드 · 설정 버전을 로그로 남긴다")
    void injectedFailureIsLogged(CapturedOutput output) throws Exception {
        mixedOnly("[\"HTTP_503\"]");
        int version = store.snapshot().configVersion();
        String key = newKey();

        register(key).andExpect(status().isServiceUnavailable());

        assertThat(output).contains("실패 주입 HTTP_503 key=" + key + " failureMode=MIXED configVersion=" + version);

        configure("""
                {"registerLatencyMs":0,"failureRate":0.0,"failureMode":"MIXED"}""");
        String ok = newKey();
        register(ok).andExpect(status().isCreated());
        assertThat(output).doesNotContain("key=" + ok);
    }

    @Test
    @DisplayName("잘못된 mixedResponses 는 400 이고 설정을 바꾸지 않는다")
    void invalidListIsRejected() throws Exception {
        int before = store.snapshot().configVersion();

        configure("""
                {"registerLatencyMs":0,"failureRate":1.0,"failureMode":"HTTP_5XX","mixedResponses":["HTTP_502"]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage")
                        .value("mixedResponses 은(는) failureMode 가 MIXED 일 때만 보낼 수 있습니다."));

        configure("""
                {"registerLatencyMs":0,"failureRate":1.0,"failureMode":"MIXED","mixedResponses":[]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage", containsString("mixedResponses 은(는) 비어 있을 수 없습니다.")));

        configure("""
                {"registerLatencyMs":0,"failureRate":1.0,"failureMode":"MIXED","mixedResponses":["HTTP_503","HTTP_503"]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("mixedResponses 에 HTTP_503 이(가) 두 번 있습니다."));

        configure("""
                {"registerLatencyMs":0,"failureRate":1.0,"failureMode":"MIXED","mixedResponses":["HTTP_502","HTTP_429"]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("mixedResponses[1] 은(는) HTTP_500, HTTP_500_NO_BODY, "
                        + "HTTP_502, HTTP_503, HTTP_504 중 하나여야 합니다. 받은 값: HTTP_429"));

        assertThat(store.snapshot().configVersion()).isEqualTo(before);
        mvc.perform(get(CONFIG)).andExpect(jsonPath("$.failureMode").value(properties.failureMode().name()));
    }
}
