package com.grandis.nova.mockapi.control.api;

import com.grandis.nova.mockapi.control.application.ResetBarrier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 등록 · 취소 요청 전체를 {@link ResetBarrier} 로 감싼다.
 *
 * <p>필터에 둔 이유 — 지연 · 주사위 · 커밋 · 결함의 유지 시간까지 요청의 처음부터 끝을 한 번에 감싸고,
 * 등록 파트 코드를 건드리지 않는다. 조회는 쓰지 않으므로 감싸지 않는다.
 *
 * <p>{@code @Component} 가 아니라 아래 {@link Registration} 이 등록한다. {@code @WebMvcTest} 는 필터 빈을
 * 함께 올리면서 {@link ResetBarrier} 는 올리지 않아, 컴포넌트로 두면 컨트롤러 슬라이스 시험이 전부 기동부터
 * 깨진다. 설정 클래스는 슬라이스에 들어가지 않는다.
 */
class ResetBarrierFilter extends OncePerRequestFilter {

    /** 원장에 쓰는 경로. 초기화가 지운 뒤에 써서는 안 되는 요청이다. */
    static final Set<String> WRITE_PATHS = Set.of("/external/reservations", "/external/cancellations");

    @Configuration(proxyBeanMethods = false)
    static class Registration {

        @Bean
        FilterRegistrationBean<ResetBarrierFilter> resetBarrierFilter(ResetBarrier barrier) {
            var registration = new FilterRegistrationBean<>(new ResetBarrierFilter(barrier));
            registration.setUrlPatterns(WRITE_PATHS);
            return registration;
        }
    }

    private final ResetBarrier barrier;

    ResetBarrierFilter(ResetBarrier barrier) {
        this.barrier = barrier;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && WRITE_PATHS.contains(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        barrier.enter();
        try {
            chain.doFilter(request, response);
        } finally {
            barrier.exit();
        }
    }
}
