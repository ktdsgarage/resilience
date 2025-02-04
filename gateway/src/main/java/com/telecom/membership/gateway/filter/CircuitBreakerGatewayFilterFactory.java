package com.telecom.membership.gateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.membership.gateway.service.EventGridService;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * Circuit Breaker 패턴을 구현한 Gateway Filter
 * 서비스 장애 시 빠른 실패 처리와 부분적 장애의 전파를 방지하는 역할을 수행
 *
 * @version 1.0
 * @author Digital Platform Development Team
 * @since 2024.02.04
 *
 * <pre>
 * << 개정이력(Modification Information) >>
 *
 *   수정일       수정자           수정내용
 *  ----------    --------    ---------------------------
 *  2024.02.04    갑빠       최초 생성
 *  2024.02.05    온달       이벤트 발행 기능 추가
 * </pre>
 */
@Slf4j
@Component
public class CircuitBreakerGatewayFilterFactory extends AbstractGatewayFilterFactory<CircuitBreakerGatewayFilterFactory.Config> {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final EventGridService eventGridService;
    private final BaseFilter baseFilter;
    private final ObjectMapper objectMapper;
    private final Set<String> testMemberIds = Set.of("TEST001", "TEST002");

    public CircuitBreakerGatewayFilterFactory(CircuitBreakerRegistry circuitBreakerRegistry,
                                              EventGridService eventGridService,
                                              BaseFilter baseFilter,
                                              ObjectMapper objectMapper) {
        super(Config.class);
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.eventGridService = eventGridService;
        this.baseFilter = baseFilter;
        this.objectMapper = objectMapper;
    }

    /**
     * Gateway Filter를 생성하고 적용
     */
    @Override
    public GatewayFilter apply(Config config) {
        validateConfig(config);
        CircuitBreaker circuitBreaker = getCircuitBreaker(config.getName());
        subscribeToCircuitBreakerEvents(circuitBreaker);

        return (exchange, chain) -> baseFilter.cacheRequestBody(exchange)
                .flatMap(bytes -> processRequest(exchange, chain, bytes, circuitBreaker))
                .onErrorResume(error -> handleError(exchange, circuitBreaker, error));
    }

    /**
     * 요청을 처리하고 Circuit Breaker 로직을 적용
     */
    private Mono<Void> processRequest(ServerWebExchange exchange,
                                      GatewayFilterChain chain,
                                      byte[] bytes,
                                      CircuitBreaker circuitBreaker) {
        try {
            String memberId = extractMemberId(bytes);
            if (testMemberIds.contains(memberId)) {
                exchange.getAttributes().put("isTestMember", true);
                return handleCircuitBreakerOpen(exchange, circuitBreaker);
            }
            return executeRequest(exchange, chain, bytes, circuitBreaker);
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    /**
     * 실제 요청 실행 - Circuit Breaker 로직 적용
     */
    private Mono<Void> executeRequest(ServerWebExchange exchange,
                                      GatewayFilterChain chain,
                                      byte[] bytes,
                                      CircuitBreaker circuitBreaker) {
        ServerHttpRequest mutatedRequest = baseFilter.decorateRequest(exchange, bytes);
        return chain.filter(exchange.mutate()
                        .request(mutatedRequest)
                        .build())
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    /**
     * Circuit Breaker 이벤트 발행 및 에러 처리
     */
    private Mono<Void> handleError(ServerWebExchange exchange,
                                   CircuitBreaker circuitBreaker,
                                   Throwable error) {
        if (Boolean.TRUE.equals(exchange.getAttribute("isTestMember"))) {
            return Mono.error(error);
        }

        //log.debug("### C/B: [handleError] C/B Stateu: {}", circuitBreaker.getState().name());

        if (error instanceof CallNotPermittedException) {
            //log.debug("### C/B: [handleError] CallNotPermittedException");
            return handleCircuitBreakerError(exchange, circuitBreaker,
                    HttpStatus.SERVICE_UNAVAILABLE, "Circuit breaker is OPEN");
        } else if (error instanceof TimeoutException) {
            //log.debug("### C/B: [handleError] TimeoutException");
            return handleCircuitBreakerError(exchange, circuitBreaker,
                    HttpStatus.GATEWAY_TIMEOUT, "Request timed out");
        } else if (error instanceof IOException || error instanceof WebClientRequestException) {
            //log.debug("### C/B: [handleError] IOException OR WebClientRequestException");
            return handleCircuitBreakerError(exchange, circuitBreaker,
                    HttpStatus.BAD_GATEWAY, "Network error occurred");
        }

        //log.debug("### C/B: [handleError] Other Exception => {}", error.getMessage());
        return handleCircuitBreakerError(exchange, circuitBreaker,
                HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage());
    }

    /**
     * Circuit Breaker 상태 변경 모니터링
     */
    private void subscribeToCircuitBreakerEvents(CircuitBreaker circuitBreaker) {
        circuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.info("##### C/B: Circuit breaker state changed: {} -> {}",
                    event.getStateTransition().getFromState(),
                    event.getStateTransition().getToState()))
                .onError(event -> log.warn("##### C/B: Circuit breaker error: {}",
                        event.getThrowable().getMessage()))
                .onSlowCallRateExceeded(event -> log.warn("##### C/B: Slow call rate exceeded: {}%",
                        event.getSlowCallRate()))
                .onFailureRateExceeded(event -> log.warn("#####  C/B: Failure rate exceeded: {}%",
                        event.getFailureRate()));
    }

    // 유틸리티 메소드
    private String extractMemberId(byte[] bytes) throws Exception {
        JsonNode requestBody = objectMapper.readTree(bytes);
        return requestBody.get("memberId").asText();
    }

    private void validateConfig(Config config) {
        if (config == null || !StringUtils.hasText(config.getName())) {
            throw new IllegalArgumentException("Invalid circuit breaker configuration");
        }
    }

    private CircuitBreaker getCircuitBreaker(String name) {
        try {
            return circuitBreakerRegistry.circuitBreaker(name);
        } catch (Exception e) {
            log.error("Failed to get circuit breaker: {}", name, e);
            throw new IllegalStateException("Circuit breaker initialization failed", e);
        }
    }

    // Circuit Breaker 응답 처리 메소드
    private Mono<Void> handleCircuitBreakerOpen(ServerWebExchange exchange,
                                                CircuitBreaker circuitBreaker) {
        return publishEventAndSetResponse(exchange, circuitBreaker,
                "CircuitBreakerOpened",
                HttpStatus.SERVICE_UNAVAILABLE,
                "Test member triggered circuit breaker OPEN");
    }

    private Mono<Void> handleCircuitBreakerError(ServerWebExchange exchange,
                                                 CircuitBreaker circuitBreaker,
                                                 HttpStatus status,
                                                 String message) {
        //log.debug("### C/B: [handleCircuitBreakerError] status: {}, X-Circuit-State: {}", status, circuitBreaker.getState().name());
        return publishEventAndSetResponse(exchange, circuitBreaker,
                "CircuitBreakerError", status, message);
    }

    private Mono<Void> publishEventAndSetResponse(ServerWebExchange exchange,
                                                  CircuitBreaker circuitBreaker,
                                                  String eventType,
                                                  HttpStatus status,
                                                  String message) {
        //log.debug("### C/B: [publishEventAndSetResponse] status: {}, X-Circuit-State: {}",
        //       status, circuitBreaker.getState().name());

        // 응답 헤더 설정
        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
        ServerHttpResponse response = exchange.getResponse();
        var headers = response.getHeaders();

        headers.add("X-Circuit-State", circuitBreaker.getState().name());
        headers.add("X-Error-Message", message);
        headers.add("X-Failure-Rate", String.valueOf(metrics.getFailureRate()));
        headers.add("X-Slow-Call-Rate", String.valueOf(metrics.getSlowCallRate()));
        headers.add("X-Not-Permitted-Calls", String.valueOf(metrics.getNumberOfNotPermittedCalls()));

        try {
            String jsonEvents = baseFilter.createEventGridEvent(exchange, eventType);
            return eventGridService.publishEvent(jsonEvents)
                    .then(Mono.defer(() -> {
                        response.setStatusCode(status);  // 응답 직전에 상태 코드 설정
                        return response.setComplete();
                    }))
                    .onErrorResume(e -> {
                        //log.error("### C/B: [publishEventAndSetResponse] Event publish failed=>{}", e.getMessage());
                        response.setStatusCode(status);  // 에러 시에도 상태 코드 설정
                        return response.setComplete();
                    });
        } catch (Exception e) {
            //log.error("### C/B: [publishEventAndSetResponse] Failed to process circuit breaker event=>{}", e.getMessage());
            response.setStatusCode(status);  // 예외 발생 시에도 상태 코드 설정
            return response.setComplete().then(Mono.error(e));
        }
    }

    @Data
    public static class Config {
        private String name;
    }
}
