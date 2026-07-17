package com.eventledger.gateway.trace;

import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * The propagation half of tracing: every outbound HTTP call to the Account
 * Service carries the current request's trace ID as X-Trace-Id. The Account
 * Service's own filter picks it up, so one client request produces one trace
 * ID across BOTH services' logs — the traceable path the spec requires.
 */
public class TraceIdPropagationInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request,
                                        byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        if (traceId != null && !traceId.isBlank()) {
            request.getHeaders().set(TraceIdFilter.TRACE_HEADER, traceId);
        }
        return execution.execute(request, body);
    }
}
