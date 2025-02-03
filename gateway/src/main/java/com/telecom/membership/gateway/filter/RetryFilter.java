package com.telecom.membership.gateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import com.telecom.membership.gateway.service.EventGridService;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;

// RetryFilter.java
@Slf4j
@Component
public class RetryFilter extends AbstractGatewayFilterFactory<RetryFilter.Config> {

    private final RetryRegistry retryRegistry;
    private final EventGridService eventGridService;
    private final BaseFilter baseFilter;
    private final ObjectMapper objectMapper;
    private final Set<String> testMemberIds = Set.of("TEST003", "TEST004"); // 테스트용 memberId 목록

    public RetryFilter(RetryRegistry retryRegistry,
                       EventGridService eventGridService,
                       ObjectMapper objectMapper) {
        super(Config.class);
        this.retryRegistry = retryRegistry;
        this.eventGridService = eventGridService;
        this.baseFilter = new BaseFilter(objectMapper) {};
        this.objectMapper = objectMapper;
    }

    @Override
    public GatewayFilter apply(Config config) {
        Retry retry = retryRegistry.retry("point-service");

        return (exchange, chain) -> {
            return baseFilter.cacheRequestBody(exchange)
                    .flatMap(bytes -> {
                        try {
                            // 요청 바디에서 memberId 추출
                            JsonNode requestBody = this.objectMapper.readTree(bytes);
                            String memberId = requestBody.get("memberId").asText();

                            // 테스트용 memberId인 경우 즉시 실패 처리
                            if (testMemberIds.contains(memberId)) {
                                exchange.getAttributes().put("isTestMember", true);
                                return handleRetryExhausted(exchange, new RuntimeException("Test member ID failure"));
                            }

                            // 일반적인 처리 진행
                            ServerHttpRequest mutatedRequest = baseFilter.decorateRequest(exchange, bytes);
                            return chain.filter(exchange.mutate().request(mutatedRequest).build())
                                    .transformDeferred(RetryOperator.of(retry));

                        } catch (Exception e) {
                            log.error("Error processing request", e);
                            return Mono.error(e);
                        }

                    })
                    .onErrorResume(throwable -> {
                        if (!exchange.getAttributes().containsKey("isTestMember")) {
                            try {
                                String jsonEvents = baseFilter.createEventGridEvent(exchange, "RetryExhausted");
                                return eventGridService.publishEvent(jsonEvents)
                                        .then(Mono.fromRunnable(() -> {
                                            exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                                            exchange.getResponse().getHeaders().add("X-Retry-Exhausted", "true");
                                            exchange.getResponse().getHeaders().add("X-Error-Message", throwable.getMessage());
                                        }))
                                        .then(exchange.getResponse().setComplete());
                            } catch (Exception e) {
                                log.error("Failed to process retry exhausted event", e);
                                return Mono.error(e);
                            }
                        }
                        return Mono.error(throwable);
                    });
        };
    }


    @Data
    public static class Config {
        private int retries;
        private List<HttpStatus> statuses;
        private List<HttpMethod> methods;
        private BackoffConfig backoff;
    }

    @Data
    public static class BackoffConfig {
        private Duration firstBackoff;
        private Duration maxBackoff;
        private int factor;
        private boolean basedOnPreviousValue;
    }

    private Mono<Void> handleRetryExhausted(ServerWebExchange exchange, Throwable throwable) {
        try {
            String jsonEvents = baseFilter.createEventGridEvent(exchange, "RetryExhausted");
            return eventGridService.publishEvent(jsonEvents)
                    .then(Mono.fromRunnable(() -> {
                        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                        exchange.getResponse().getHeaders().add("X-Retry-Exhausted", "true");
                        exchange.getResponse().getHeaders().add("X-Error-Message",
                                testMemberIds.contains(exchange.getAttribute("memberId")) ?
                                        "Retry failed for test member" : throwable.getMessage());
                    }))
                    .then(exchange.getResponse().setComplete());
        } catch (Exception e) {
            log.error("Failed to process retry exhausted event", e);
            return Mono.error(e);
        }
    }
}