package com.grandis.nova.mockapi.global.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.Map;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MultipartException;

/**
 * 잘못된 요청이 500 으로 나가지 않는지 본다.
 *
 * <p>Mock 의 500 은 "일시 실패" 라는 뜻이고 본 서비스가 재시도한다. 요청 자체가 틀린 경우는
 * 몇 번을 다시 보내도 결과가 같으므로 4xx 로 나가야 재시도가 멈춘다. 실제 API 로는 일으키기 어려운
 * 예외가 많아 여기서만 쓰는 시험용 컨트롤러로 각 예외를 일으킨다.
 */
// 범위를 시험용 컨트롤러로 좁힌다. 범위 없는 @WebMvcTest 는 컨트롤러를 전부 긁어오므로,
// 누군가 새 컨트롤러를 만들 때마다 그 컨트롤러가 의존하는 빈이 없다며 이 시험이 깨진다.
@WebMvcTest(controllers = {GlobalExceptionHandlerTest.ProbeController.class,
        GlobalExceptionHandlerTest.PlainProbeController.class})
@Import({GlobalExceptionHandlerTest.ProbeController.class,
        GlobalExceptionHandlerTest.PlainProbeController.class})
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("메서드 오타 - 경로는 맞는데 메서드가 다르면 400")
    void methodNotSupported() throws Exception {
        mvc.perform(get("/probe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.replayable").value(false));
    }

    @Test
    @DisplayName("Content-Type 누락 - 본문을 읽을 수 없으면 400")
    void mediaTypeNotSupported() throws Exception {
        mvc.perform(post("/probe").content("{\"value\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("경로 변수 타입 불일치 - 숫자 자리에 글자가 오면 400")
    void typeMismatch() throws Exception {
        mvc.perform(get("/probe/number/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("경로 변수 길이 검증 - @Validated 가 붙은 컨트롤러")
    void constraintViolation() throws Exception {
        mvc.perform(get("/probe/" + "k".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    /**
     * 같은 길이 위반인데 {@code @Validated} 가 없으면 스프링이 다른 예외를 던진다.
     * 컨트롤러마다 애너테이션을 기억해야 한다면 언젠가 한 번은 빠뜨린다. 빠뜨린 경로가 통째로
     * 500 이 되면 워커가 그 요청을 영원히 재시도하므로, 둘 다 400 이어야 한다.
     */
    @Test
    @DisplayName("경로 변수 길이 검증 - @Validated 가 없는 컨트롤러도 400")
    void methodValidationWithoutValidated() throws Exception {
        mvc.perform(get("/plain/" + "k".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    /** 기록 없음({@code NOT_FOUND})과 코드가 같으면 워커가 주소 오타를 "등록 없음" 으로 읽는다. */
    @Test
    @DisplayName("없는 경로 - 404 NO_SUCH_ENDPOINT 이며 오류 형식을 지킨다")
    void noResource() throws Exception {
        mvc.perform(get("/없는경로"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NO_SUCH_ENDPOINT"))
                // 값이 없어도 필드를 생략하지 않는다(명세). doesNotExist() 는 필드가 아예 없어도
                // 통과해서 이 계약을 못 잡는다. value(nullValue()) 는 "있고 null" 만 통과한다.
                .andExpect(jsonPath("$.externalNumber").value(nullValue()));
    }

    /**
     * 부하 시험은 서버 로그의 "처리하지 못한 오류" 가 0건인지 센다. 진짜 서버 오류는 이 문구로 남아야 하고,
     * 아래 끊긴 클라이언트 시험이 "안 찍혔다" 를 볼 때 로그를 제대로 붙잡고 있다는 근거도 된다.
     * 응답 문구도 주입 실패와 달라야 부하 결과에서 둘을 나눌 수 있다.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("Mock 안에서 터진 오류만 500 UPSTREAM_UNAVAILABLE 이고 ERROR 로 남으며, 문구가 주입 실패와 다르다")
    void unexpectedStaysServerError(CapturedOutput output) throws Exception {
        mvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("UPSTREAM_UNAVAILABLE"))
                .andExpect(jsonPath("$.errorMessage").value(GlobalExceptionHandler.UNHANDLED_MESSAGE));
        assertThat(GlobalExceptionHandler.UNHANDLED_MESSAGE)
                .isNotEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE.defaultMessage());
        assertThat(output).contains("처리하지 못한 오류");
    }

    /**
     * 오류 응답은 Accept 와 협상하지 않는다. 협상에 맡기면 JSON 을 못 써 컨테이너가 본문 없는 오류를 내고,
     * 409 같은 확정 거절이 본문 없는 500(재시도 대상)으로 뒤집힌다.
     */
    @Test
    @DisplayName("Accept: text/plain 이어도 오류 본문은 JSON 으로 나간다")
    void errorBodyIgnoresAccept() throws Exception {
        mvc.perform(get("/probe/boom").accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.errorCode").value("UPSTREAM_UNAVAILABLE"));
    }

    /** 경계 없는 multipart 는 핸들러를 찾기 전에 나서 매핑의 consumes 로 막을 수 없다. */
    @Test
    @DisplayName("multipart 를 나누지 못하면 400")
    void multipartIsBadRequest() throws Exception {
        mvc.perform(get("/probe/multipart"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errorMessage", containsString("Content-Type")));
    }

    /**
     * 부하 클라이언트를 도중에 강제 종료하면 응답을 쓰다 이 예외들이 난다(한 번에 수백 건).
     * catch-all 로 떨어지면 서버 버그와 같은 문구로 찍히고, 끊긴 연결에 500 을 또 쓰려 한다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"async", "abort"})
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("끊긴 클라이언트 - ERROR 로 찍지 않고 오류 응답도 쓰지 않는다")
    void clientGoneIsNotAnError(String kind, CapturedOutput output) throws Exception {
        mvc.perform(get("/probe/gone/" + kind))
                .andExpect(content().string(""));
        assertThat(output).doesNotContain("처리하지 못한 오류");
    }

    @RestController
    @Validated
    static class ProbeController {

        @PostMapping(path = "/probe", consumes = MediaType.APPLICATION_JSON_VALUE)
        String post(@RequestBody Map<String, String> body) {
            return "ok";
        }

        @GetMapping("/probe/{key}")
        String get(@PathVariable @Size(min = 1, max = 100) String key) {
            if ("boom".equals(key)) {
                throw new IllegalStateException("Mock 내부 오류");
            }
            if ("multipart".equals(key)) {
                throw new MultipartException("Failed to parse multipart servlet request");
            }
            return key;
        }

        @GetMapping("/probe/number/{n}")
        String number(@PathVariable int n) {
            return String.valueOf(n);
        }

        /** 실제 로그에서 본 모양 그대로 — 스프링 예외가 톰캣 예외를 감싸고, 맨 안쪽이 연결 리셋이다. */
        @GetMapping("/probe/gone/{kind}")
        String gone(@PathVariable String kind) throws IOException {
            IOException reset = new IOException("Connection reset by peer");
            if ("abort".equals(kind)) {
                throw new ClientAbortException(reset);
            }
            throw new AsyncRequestNotUsableException("ServletResponse failed to flushBuffer",
                    new ClientAbortException(reset));
        }
    }

    /** {@code @Validated} 를 일부러 붙이지 않았다. */
    @RestController
    static class PlainProbeController {

        @GetMapping("/plain/{key}")
        String get(@PathVariable @Size(min = 1, max = 100) String key) {
            return key;
        }
    }
}
