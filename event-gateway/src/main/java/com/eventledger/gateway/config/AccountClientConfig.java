package com.eventledger.gateway.config;

import com.eventledger.gateway.trace.TraceIdPropagationInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * HTTP client for the Account Service.
 *
 * Timeouts are explicit and short. Without them, a hung Account Service would
 * hold Gateway threads indefinitely — the default is effectively "wait
 * forever", which is never acceptable for an inter-service call. These
 * timeouts are the foundation the circuit breaker builds on in a later step.
 */
@Configuration
public class AccountClientConfig {

    @Bean
    public RestClient accountServiceRestClient(
            @Value("${account-service.base-url}") String baseUrl,
            @Value("${account-service.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${account-service.read-timeout-ms:3000}") long readTimeoutMs) {

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor(new TraceIdPropagationInterceptor())
                .build();
    }
}
