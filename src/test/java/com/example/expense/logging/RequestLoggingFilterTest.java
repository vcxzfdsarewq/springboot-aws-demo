package com.example.expense.logging;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.expense.entity.Role;
import com.example.expense.security.AuthPrincipal;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class RequestLoggingFilterTest {

    private final RequestLoggingFilter filter = new RequestLoggingFilter();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void setsRequestIdHeaderAndClearsMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/expenses");
        request.addHeader(RequestLoggingFilter.REQUEST_ID_HEADER, "req-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, noOpChain());

        assertThat(response.getHeader(RequestLoggingFilter.REQUEST_ID_HEADER)).isEqualTo("req-123");
        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("userId")).isNull();
        assertThat(MDC.get("method")).isNull();
        assertThat(MDC.get("path")).isNull();
        assertThat(MDC.get("status")).isNull();
        assertThat(MDC.get("durationMs")).isNull();
    }

    @Test
    void generatesRequestIdWhenMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/expenses");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, noOpChain());

        assertThat(response.getHeader(RequestLoggingFilter.REQUEST_ID_HEADER)).isNotBlank();
    }

    @Test
    void writesAccessLogAsStructuredMdcFields() throws Exception {
        authenticate(2L);
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/expenses");
            request.addHeader(RequestLoggingFilter.REQUEST_ID_HEADER, "req-456");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, noOpChain());
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getFormattedMessage()).isEqualTo("http_request");
        assertThat(event.getMDCPropertyMap()).containsEntry("requestId", "req-456");
        assertThat(event.getMDCPropertyMap()).containsEntry("userId", "2");
        assertThat(event.getMDCPropertyMap()).containsEntry("method", "POST");
        assertThat(event.getMDCPropertyMap()).containsEntry("path", "/api/expenses");
        assertThat(event.getMDCPropertyMap()).containsEntry("status", "204");
        assertThat(event.getMDCPropertyMap().get("durationMs")).matches("\\d+");
    }

    private void authenticate(Long userId) {
        AuthPrincipal principal = new AuthPrincipal(userId, "u@example.com", Role.USER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null));
    }

    private FilterChain noOpChain() {
        return (request, response) -> ((MockHttpServletResponse) response).setStatus(204);
    }
}
