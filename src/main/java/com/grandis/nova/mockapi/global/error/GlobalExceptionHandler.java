package com.grandis.nova.mockapi.global.error;

import jakarta.validation.ConstraintViolationException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * 공통 예외 처리.
 *
 * <p>맨 아래 catch-all 이 500 UPSTREAM_UNAVAILABLE 을 내는데, 본 서비스는 그것을 일시 실패로 보고
 * 재시도한다. 그래서 <b>재시도해도 결과가 달라지지 않는 요청은 반드시 여기서 4xx 로 걷어내야 한다.</b>
 * 걷어내지 못하면 잘못된 요청 하나가 워커를 무한 재시도에 묶는다.
 *
 * <p>상태는 명세의 "오류 분류 계약" 다섯 가지(400 · 404 · 409 · 422 · 500)만 쓴다. 메서드 오타를 405,
 * Content-Type 오류를 415 로 주는 편이 HTTP 로는 정확하지만, 본 서비스는 상태가 아니라 {@code errorCode}
 * 로 분기하고 둘 다 "계약 오류" 라는 같은 뜻이다. 상태를 늘리는 대신 무엇이 틀렸는지를 메시지에 담는다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Mock 이 계약대로 내는 오류. */
    @ExceptionHandler(MockException.class)
    public ResponseEntity<ErrorResponse> handleMock(MockException e) {
        ErrorCode code = e.errorCode();
        return ResponseEntity.status(code.status())
                .body(ErrorResponse.of(code, e.getMessage(), e.externalNumber()));
    }

    /** 본문 검증 실패. 어느 필드가 문제인지 메시지에 담는다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .findFirst()
                .orElse(ErrorCode.INVALID_REQUEST.defaultMessage());
        return badRequest(detail);
    }

    /**
     * 경로 변수 · 쿼리 파라미터 검증 실패. 예를 들어 키 길이 1~100 자 제약.
     * 본문 검증과 달리 이쪽은 {@code ConstraintViolationException} 으로 나온다.
     *
     * <p>{@code jakarta.validation} 쪽이다. DB CHECK 위반은 이름이 같은
     * {@code org.hibernate.exception.ConstraintViolationException} 이고, 그건 Mock 의 버그이므로
     * catch-all 로 떨어뜨려 500 을 낸다.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                .findFirst()
                .orElse(ErrorCode.INVALID_REQUEST.defaultMessage());
        return badRequest(detail);
    }

    /** Idempotency-Key 같은 필수 헤더 누락. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException e) {
        return badRequest(e.getHeaderName() + " 헤더가 필요합니다.");
    }

    /**
     * 본문을 객체로 바꾸지 못한 경우. 원인을 찾아 무엇이 틀렸는지 알려준다.
     *
     * <p>"읽을 수 없다" 로 뭉뚱그리면 사람이 손으로 치는 설정 API 에서 오타를 찾을 수 없다.
     * 원인을 알 수 없는 경우(JSON 이 깨졌거나 본문이 비었을 때)만 그 문장으로 남긴다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        return badRequest(describeUnreadable(e));
    }

    /** Content-Type 누락 또는 오타. 본문을 읽을 수 없으니 재시도해도 같다. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException e) {
        return badRequest("Content-Type 이 application/json 이어야 합니다. 받은 값: "
                + Objects.toString(e.getContentType(), "(없음)"));
    }

    /** 경로는 맞는데 메서드가 다르다. 경로 오타와 같은 종류의 실수다. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        String[] supported = e.getSupportedMethods();
        String allowed = supported == null ? "없음" : String.join(", ", supported);
        return badRequest("이 경로에서 " + e.getMethod() + " 는 지원하지 않습니다. 허용: " + allowed);
    }

    /** 경로 변수 타입이 안 맞는다. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return badRequest(e.getName() + " 값이 올바르지 않습니다: " + e.getValue());
    }

    /**
     * 경로 변수 검증 실패인데 컨트롤러에 {@code @Validated} 가 없는 경우.
     *
     * <p>같은 길이 위반이라도 {@code @Validated} 가 붙어 있으면 ConstraintViolationException,
     * 없으면 이 예외가 나온다. 스프링이 스스로 400 이라고 표시해 던지는데 핸들러가 없으면
     * catch-all 로 떨어져 500 이 됐다. 컨트롤러마다 애너테이션을 기억해야 한다면 언젠가
     * 한 번은 빠뜨리고, 빠뜨린 경로가 통째로 재시도 대상이 된다.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleMethodValidation(HandlerMethodValidationException e) {
        String detail = e.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> describe(result, error)))
                .findFirst()
                .orElse(ErrorCode.INVALID_REQUEST.defaultMessage());
        return badRequest(detail);
    }

    /**
     * 없는 경로. 아래 catch-all 로 떨어지면 500 UPSTREAM_UNAVAILABLE 이 되는데,
     * 본 서비스가 그것을 일시 실패로 보고 재시도한다. 경로 오타는 재시도해도 소용없으므로 404 로 준다.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e) {
        ErrorCode code = ErrorCode.NOT_FOUND;
        return ResponseEntity.status(code.status())
                .body(ErrorResponse.of(code, "그런 경로가 없습니다: " + e.getResourcePath()));
    }

    /**
     * 클라이언트가 먼저 끊은 연결에 응답을 쓰다 난 오류. 요청이 틀린 것도 Mock 이 아픈 것도 아니다.
     *
     * <p>아래 catch-all 로 떨어지면 "처리하지 못한 오류" 로 찍혀, 부하 시험이 0건이어야 한다고 세는 서버
     * 버그 로그와 섞인다. 부하 클라이언트를 도중에 강제 종료하면 한 번에 수백 건이 찍혔다. 받을 쪽이
     * 없으니 응답도 쓰지 않는다 — 쓰려고 하면 같은 예외가 또 난다.
     *
     * <p>스프링의 {@code DisconnectedClientHelper} 를 쓰지 않고 두 예외로 좁힌다. 그쪽은
     * {@code EOFException} 과 "connection reset" 문구가 원인에 있기만 해도 끊긴 클라이언트로 보는데,
     * MySQL 연결이 끊겨도 같은 것이 원인에 실린다. DB 장애가 여기로 오면 500 대신 빈 응답이 나가
     * 워커가 일시 실패를 알 수 없다. 이 두 예외는 응답을 주고받는 소켓에서만 난다.
     */
    @ExceptionHandler({AsyncRequestNotUsableException.class, ClientAbortException.class})
    public void handleClientGone(Exception e) {
        log.debug("클라이언트가 먼저 끊었다 — 응답을 쓰지 않는다: {}", e.getMessage());
    }

    /**
     * 나머지. Mock 의 500 은 본 서비스 공통 오류(INTERNAL_ERROR)가 아니라 UPSTREAM_UNAVAILABLE 이다.
     * 본 서비스는 이것을 일시 실패로 보고 재시도한다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        // 스프링이 스스로 4xx 라고 표시해 던지는 예외는 요청이 잘못된 것이지 Mock 이 아픈 게 아니다.
        // 개별 핸들러를 빠뜨려도 여기서 걸러 500 으로 나가지 않게 한다. 500 은 워커가 재시도한다.
        if (e instanceof org.springframework.web.ErrorResponse spring
                && spring.getStatusCode().is4xxClientError()) {
            log.warn("개별 핸들러 없이 4xx 예외를 받았다: {}", e.getClass().getName());
            ErrorCode code = spring.getStatusCode().value() == 404
                    ? ErrorCode.NOT_FOUND
                    : ErrorCode.INVALID_REQUEST;
            return ResponseEntity.status(code.status()).body(ErrorResponse.of(code));
        }
        log.error("처리하지 못한 오류", e);
        ErrorCode code = ErrorCode.UPSTREAM_UNAVAILABLE;
        return ResponseEntity.status(code.status()).body(ErrorResponse.of(code));
    }

    /**
     * 본문 변환 실패의 원인을 문장으로 만든다. 순서가 중요하다 — 모르는 필드와 enum 오류도
     * 넓게 보면 형식 불일치(MismatchedInputException)라서, 구체적인 쪽을 먼저 본다.
     */
    private static String describeUnreadable(HttpMessageNotReadableException e) {
        JacksonException cause = findCause(e, JacksonException.class);
        if (cause instanceof UnrecognizedPropertyException unknown) {
            return unknown.getPropertyName() + " 은(는) 알 수 없는 필드입니다."
                    + knownFields(unknown.getKnownPropertyIds());
        }
        if (cause instanceof InvalidFormatException invalid
                && invalid.getTargetType() != null && invalid.getTargetType().isEnum()) {
            return fieldPath(invalid) + " 은(는) " + enumNames(invalid.getTargetType())
                    + " 중 하나여야 합니다. 받은 값: " + invalid.getValue();
        }
        if (cause instanceof MismatchedInputException mismatched && !mismatched.getPath().isEmpty()) {
            return fieldPath(mismatched) + " 값의 형식이 올바르지 않습니다.";
        }
        return "요청 본문을 읽을 수 없습니다.";
    }

    private static <T extends Throwable> T findCause(Throwable e, Class<T> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
        }
        return null;
    }

    /** 중첩 객체면 "a.b", 배열이면 "items[0]" 처럼 이어 붙인다. */
    private static String fieldPath(JacksonException e) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference ref : e.getPath()) {
            if (ref.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(ref.getPropertyName());
            } else if (ref.getIndex() >= 0) {
                path.append('[').append(ref.getIndex()).append(']');
            }
        }
        return path.isEmpty() ? "요청 값" : path.toString();
    }

    private static String enumNames(Class<?> enumType) {
        return Arrays.stream(enumType.getEnumConstants())
                .map(constant -> ((Enum<?>) constant).name())
                .collect(Collectors.joining(", "));
    }

    private static String knownFields(Collection<Object> known) {
        if (known == null || known.isEmpty()) {
            return "";
        }
        return " 받을 수 있는 필드: " + known.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
    }

    /** 어느 값이 왜 틀렸는지. 이름이나 메시지가 비어 있을 수 있어 둘 다 받아둔다. */
    private static String describe(ParameterValidationResult result, MessageSourceResolvable error) {
        String name = result.getMethodParameter().getParameterName();
        String message = error.getDefaultMessage();
        return (name == null ? "요청 값" : name)
                + " " + (message == null ? "이(가) 올바르지 않습니다." : message);
    }

    private ResponseEntity<ErrorResponse> badRequest(String message) {
        ErrorCode code = ErrorCode.INVALID_REQUEST;
        return ResponseEntity.status(code.status()).body(ErrorResponse.of(code, message));
    }
}
