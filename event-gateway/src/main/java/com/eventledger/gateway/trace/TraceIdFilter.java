package com.eventledger.gateway.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Entry point of tracing. For every incoming request:
 *
 *  - reuse the X-Trace-Id header if a caller supplied one, otherwise generate
 *    a new trace ID (the Gateway is where traces are born, per the spec);
 *  - store it in the SLF4J MDC so EVERY log line during this request —
 *    from any class, any layer — automatically carries the trace ID;
 *  - echo it in the response header so clients can quote it when reporting
 *    problems;
 *  - clear the MDC afterwards (threads are pooled and reused; leaking a
 *    trace ID onto the next request would corrupt correlation).
 *
 * This is a deliberate, minimal, manual implementation of what OpenTelemetry
 * automates — small enough to explain line by line. Production would run the
 * OTel agent; the concept (generate, propagate via header, log everywhere)
 * is identical.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String incoming = request.getHeader(TRACE_HEADER);
        String traceId = (incoming == null || incoming.isBlank()) ? newTraceId() : incoming;

        MDC.put(MDC_KEY, traceId);
        response.setHeader(TRACE_HEADER, traceId);
        long startedAt = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            log.info("request completed method={} path={} status={} durationMs={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus(),
                    System.currentTimeMillis() - startedAt);
            MDC.remove(MDC_KEY);
        }
    }

    private String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
