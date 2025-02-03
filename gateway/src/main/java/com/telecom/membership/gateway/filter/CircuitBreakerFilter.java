// File: membership/gateway/src/main/java/com/telecom/membership/gateway/filter/CircuitBreakerFilter.java
package com.telecom.membership.gateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.membership.gateway.service.EventGridService;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.concurrent.TimeoutException;

// CircuitBreakerFilter.java
@Slf4j
@Component
public class CircuitBreakerFilter extends AbstractGatewayFilterFactory<CircuitBreakerFilter.Config> {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final EventGridService eventGridService;
    private final BaseFilter baseFilter;
    private final ObjectMapper objectMapper;
    private final Set<String> testMemberIds = Set.of("TEST001", "TEST002");

    public CircuitBreakerFilter(CircuitBreakerRegistry circuitBreakerRegistry,
                                EventGridService eventGridService,
                                ObjectMapper objectMapper) {
        super(Config.class);
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.eventGridService = eventGridService;
        this.baseFilter = new BaseFilter(objectMapper) {};
        this.objectMapper = objectMapper;
    }

    @Override
    public GatewayFilter apply(Config config) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(
                config.getCircuitBreakerName()
        );

        circuitBreaker.getEventPublisher()
                .onStateTransition(event -> {
                    log.info("Circuit breaker state changed: {} -> {}",
                            event.getStateTransition().getFromState(),
                            event.getStateTransition().getToState());
                });

        return (exchange, chain) -> {
            return baseFilter.cacheRequestBody(exchange)
                    .flatMap(bytes -> {
                        try {
                            JsonNode requestBody = objectMapper.readTree(bytes);
                            String memberId = requestBody.get("memberId").asText();

                            if (testMemberIds.contains(memberId)) {
                                exchange.getAttributes().put("isTestMember", true);
                                return handleCircuitBreakerOpen(exchange, circuitBreaker);
                            }

                            ServerHttpRequest mutatedRequest = baseFilter.decorateRequest(exchange, bytes);
                            return chain.filter(exchange.mutate().request(mutatedRequest).build())
                                    .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));

                        } catch (Exception e) {
                            return Mono.error(e);
                        }
                    })
                    .onErrorResume(CallNotPermittedException.class, e -> {
                        return handleCircuitBreakerError(exchange, circuitBreaker,
                                HttpStatus.SERVICE_UNAVAILABLE, "Circuit breaker is open");
                    })
                    .onErrorResume(TimeoutException.class, e -> {
                        return handleCircuitBreakerError(exchange, circuitBreaker,
                                HttpStatus.GATEWAY_TIMEOUT, "Request timeout");
                    })
                    .onErrorResume(Exception.class, e -> {
                        if (!exchange.getAttributes().containsKey("isTestMember")) {
                            return handleCircuitBreakerError(exchange, circuitBreaker,
                                    HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
                        }
                        return Mono.error(e);
                    });
        };
    }

    private Mono<Void> handleCircuitBreakerOpen(ServerWebExchange exchange,
                                                CircuitBreaker circuitBreaker) {
        try {
            String jsonEvents = baseFilter.createEventGridEvent(exchange, "CircuitBreakerOpened");
            return eventGridService.publishEvent(jsonEvents)
                    .then(Mono.fromRunnable(() -> {
                        exchange.getResponse().setStatusCode(HttpStatus.GATEWAY_TIMEOUT);
                        exchange.getResponse().getHeaders().add("X-Circuit-Open", "true");
                        exchange.getResponse().getHeaders()
                                .add("X-Circuit-State", circuitBreaker.getState().name());
                        exchange.getResponse().getHeaders()
                                .add("X-Error-Message", "Circuit breaker opened for test member");
                    }))
                    .then(exchange.getResponse().setComplete());
        } catch (Exception e) {
            log.error("Failed to process circuit breaker event", e);
            return Mono.error(e);
        }
    }

    private Mono<Void> handleCircuitBreakerError(ServerWebExchange exchange,
                                                 CircuitBreaker circuitBreaker,
                                                 HttpStatus status,
                                                 String message) {
        try {
            String jsonEvents = baseFilter.createEventGridEvent(exchange, "CircuitBreakerError");
            return eventGridService.publishEvent(jsonEvents)
                    .then(Mono.fromRunnable(() -> {
                        exchange.getResponse().setStatusCode(status);
                        exchange.getResponse().getHeaders().add("X-Circuit-Open",
                                String.valueOf(circuitBreaker.getState() != CircuitBreaker.State.CLOSED));
                        exchange.getResponse().getHeaders()
                                .add("X-Circuit-State", circuitBreaker.getState().name());
                        exchange.getResponse().getHeaders()
                                .add("X-Error-Message", message);
                    }))
                    .then(exchange.getResponse().setComplete());
        } catch (Exception e) {
            log.error("Failed to process circuit breaker event", e);
            return Mono.error(e);
        }
    }

    @Data
    public static class Config {
        @NotNull(message = "Circuit breaker name is required")
        private String circuitBreakerName;  // 기본값 제거
    }
}


