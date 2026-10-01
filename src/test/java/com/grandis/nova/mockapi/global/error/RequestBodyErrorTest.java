package com.grandis.nova.mockapi.global.error;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.mockapi.global.chaos.FailureMode;
import com.grandis.nova.mockapi.global.config.StrictJsonConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 요청 본문을 읽지 못했을 때 무엇이 틀렸는지 알려주는지 본다.
 *
 * <p>설정 API 는 사람이 손으로 치는 조작 패널이다. "요청 본문을 읽을 수 없습니다" 만으로는 오타를
 * 찾을 수 없다.
 *
 * <p>{@code controllers=} 는 스캔된 컨트롤러를 거르기만 하고 등록은 하지 않는다. 시험용 컨트롤러는
 * 중첩 클래스라 스캔에 안 잡히므로 {@code @Import} 로 넣는다. 둘 다 필요하다.
 * {@link StrictJsonConfig} 도 슬라이스가 스캔하지 않아 같이 넣는다.
 */
@WebMvcTest(controllers = RequestBodyErrorTest.BodyProbeController.class)
@Import({RequestBodyErrorTest.BodyProbeController.class, StrictJsonConfig.class})
class RequestBodyErrorTest {

    private static final String PATH = "/probe/body";

    @Autowired
    private MockMvc mvc;

    private ResultActions send(String body) throws Exception {
        return mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("모르는 필드 - 필드 이름과 받을 수 있는 필드를 알려준다")
    void unknownField() throws Exception {
        send("""
                {"count":1,"mode":"TIMEOUT","timeoutHoldMs":9000}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errorMessage", containsString("timeoutHoldMs 은(는) 알 수 없는 필드입니다.")))
                .andExpect(jsonPath("$.errorMessage", containsString("count")))
                .andExpect(jsonPath("$.errorMessage", containsString("mode")));
    }

    @Test
    @DisplayName("잘못된 enum 값 - 허용값과 받은 값을 알려준다")
    void invalidEnum() throws Exception {
        send("""
                {"count":1,"mode":"HTTP_429"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage")
                        .value("mode 은(는) HTTP_5XX, TIMEOUT 중 하나여야 합니다. 받은 값: HTTP_429"));
    }

    @Test
    @DisplayName("타입 불일치 - 숫자 자리에 글자가 오면 필드 이름을 알려준다")
    void typeMismatch() throws Exception {
        send("""
                {"count":"abc","mode":"TIMEOUT"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("count 값의 형식이 올바르지 않습니다."));
    }

    @Test
    @DisplayName("JSON 이 깨졌으면 원래 문장 그대로다")
    void brokenJson() throws Exception {
        send("{\"count\":")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("요청 본문을 읽을 수 없습니다."));
    }

    /** 같은 필드가 두 번 오면 마지막 값을 쓰지 않고 거절한다. 어느 값이 맞는지 Mock 이 고를 수 없다. */
    @Test
    @DisplayName("같은 필드 두 번 - 필드 이름을 알려준다")
    void duplicateField() throws Exception {
        send("""
                {"count":1,"count":2,"mode":"TIMEOUT"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errorMessage").value("count 필드가 두 번 왔습니다."));
    }

    /** 문자열 숫자 · 실수 · 빈 문자열을 정수로 바꿔 받지 않는다. 바꿔 주면 워커의 타입 오류가 묻힌다. */
    @Test
    @DisplayName("정수 자리에 문자열 · 실수 · 빈 문자열 - 바꿔 받지 않고 400")
    void noCoercionToInteger() throws Exception {
        for (String count : new String[]{"\"1\"", "1.9", "\"\""}) {
            send("""
                    {"count":%s,"mode":"TIMEOUT"}""".formatted(count))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorMessage").value("count 값의 형식이 올바르지 않습니다."));
        }
    }

    /** 숫자를 열거형 순번으로 읽으면 1 이 조용히 TIMEOUT 이 된다. */
    @Test
    @DisplayName("열거형 자리에 숫자 - 순번으로 읽지 않고 허용값을 알려준다")
    void noEnumByIndex() throws Exception {
        send("""
                {"count":1,"mode":1}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage", containsString("mode 은(는) HTTP_5XX, TIMEOUT 중 하나여야 합니다.")));
    }

    record ProbeBody(Integer count, FailureMode mode) {
    }

    @RestController
    static class BodyProbeController {

        @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
        String post(@RequestBody ProbeBody body) {
            return "ok";
        }
    }
}
