// com.telecom.membership.gateway.filter.CustomRetryGatewayFilterFactory.java
package com.telecom.membership.gateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.http.HttpStatus;
import com.telecom.membership.gateway.service.EventGridService;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;

/**
 * 재시도 처리를 담당하는 Gateway Filter
 * - 파트너 타입별 다른 재시도 정책 적용
 * - 실패 시 EventGrid로 이벤트 발행
 *
 * @version 1.0
 * @author Digital Platform Development Team
 * @since 2024.02.04
 */
@Slf4j
@Component
public class CustomRetryGatewayFilterFactory extends AbstractGatewayFilterFactory<CustomRetryGatewayFilterFactory.Config> {

    private static final Map<String, String> PARTNER_TYPE_MAPPING = Map.of(
            "MART", "mart",
            "CONVENIENCE", "convenience",
            "ONLINE", "online"
    );

    private final RetryRegistry retryRegistry;
    private final EventGridService eventGridService;
    private final BaseFilter baseFilter;
    private final ObjectMapper objectMapper;
    private final Set<String> testMemberIds;

    /**
     * CustomRetryGatewayFilterFactory 생성자
     */
    public CustomRetryGatewayFilterFactory(RetryRegistry retryRegistry,
                                           EventGridService eventGridService,
                                           BaseFilter baseFilter,
                                           ObjectMapper objectMapper) {
        super(Config.class);
        this.retryRegistry = retryRegistry;
        this.eventGridService = eventGridService;
        this.objectMapper = objectMapper;
        this.baseFilter = baseFilter;
        this.testMemberIds = Set.of("TEST003", "TEST004");
    }

    /**
     * Gateway Filter 적용
     *
     * @param config 필터 설정
     * @return GatewayFilter 구현체
     */
    @Override
    public GatewayFilter apply(Config config) {
        if (!isValidConfig(config)) {
            throw new IllegalArgumentException("Invalid retry configuration. Name is required.");
        }

        return (exchange, chain) -> {
            // 파트너 타입 확인
            String rawPartnerType = exchange.getRequest().getHeaders()
                    .getFirst("X-Partner-Type");

            // Retry 인스턴스 선택
            Retry retry = getRetryForPartnerType(rawPartnerType);

            return baseFilter.cacheRequestBody(exchange)
                    .flatMap(bytes -> processRequest(exchange, chain, bytes, retry));
        };
    }

    /**
     * 설정 유효성 검증
     */
    private boolean isValidConfig(Config config) {
        return config != null && StringUtils.hasText(config.getName());
    }

    /**
     * 파트너 타입에 따른 Retry 인스턴스 조회
     */
    private Retry getRetryForPartnerType(String rawPartnerType) {
        String retryName;

        if (!StringUtils.hasText(rawPartnerType)) {
            retryName = "point-service"; // 기본 retry 설정 사용
            log.debug("Using default retry configuration: {}", retryName);
        } else {
            retryName = PARTNER_TYPE_MAPPING.getOrDefault(
                    rawPartnerType.toUpperCase(),
                    "point-service"
            );
            log.debug("Using partner specific retry configuration: {}", retryName);
        }

        try {
            Retry retry = retryRegistry.retry(retryName);
            logRetryConfig(retry, retryName);
            return retry;
        } catch (Exception e) {
            log.warn("Failed to get retry configuration for: {}. Using default.", retryName, e);
            return retryRegistry.retry("point-service");
        }
    }

    /**
     * Retry 설정 로깅
     */
    private void logRetryConfig(Retry retry, String retryName) {
        Retry.Metrics metrics = retry.getMetrics();
        log.debug("Retry configuration for {}: " +
                        "number of calls: {}, " +
                        "number of failed calls: {}, " +
                        "number of successful calls: {}",
                retryName,
                metrics.getNumberOfFailedCallsWithoutRetryAttempt()
                        + metrics.getNumberOfFailedCallsWithRetryAttempt(),
                metrics.getNumberOfFailedCallsWithRetryAttempt(),
                metrics.getNumberOfSuccessfulCallsWithoutRetryAttempt()
                        + metrics.getNumberOfSuccessfulCallsWithRetryAttempt()
        );
    }

    /**
     * 요청 처리 및 재시도 적용
     */
    private Mono<Void> processRequest(ServerWebExchange exchange,
                                      GatewayFilterChain chain,
                                      byte[] bytes,
                                      Retry retry) {
        try {
            String memberId = extractMemberId(bytes);
            if (isTestMember(memberId)) {
                exchange.getAttributes().put("isTestMember", true);
                return handleTestMemberFailure(exchange);
            }

            return executeWithRetry(exchange, chain, bytes, retry);

        } catch (Exception e) {
            log.error("Error processing request", e);
            return Mono.error(e);
        }
    }

    /**
     * 요청 본문에서 memberId 추출
     */
    private String extractMemberId(byte[] bytes) throws Exception {
        JsonNode requestBody = objectMapper.readTree(bytes);
        return requestBody.get("memberId").asText();
    }

    /**
     * 테스트용 회원 ID 여부 확인
     */
    private boolean isTestMember(String memberId) {
        return testMemberIds.contains(memberId);
    }

    /**
     * 테스트 회원 실패 처리
     */
    private Mono<Void> handleTestMemberFailure(ServerWebExchange exchange) {
        return handleRetryExhausted(
                exchange,
                new RuntimeException("Test member ID failure")
        );
    }

    /**
     * 재시도 로직 실행
     */
    private Mono<Void> executeWithRetry(ServerWebExchange exchange,
                                        GatewayFilterChain chain,
                                        byte[] bytes,
                                        Retry retry) {
        ServerHttpRequest mutatedRequest = baseFilter.decorateRequest(exchange, bytes);
        return chain.filter(exchange.mutate().request(mutatedRequest).build())
                .transformDeferred(RetryOperator.of(retry))
                .onErrorResume(throwable -> handleError(exchange, throwable));
    }

    /**
     * 에러 처리
     */
    private Mono<Void> handleError(ServerWebExchange exchange, Throwable throwable) {
        Boolean isTestMember = exchange.getAttribute("isTestMember");
        if (!Boolean.TRUE.equals(isTestMember)) {
            return handleRetryExhausted(exchange, throwable);
        }
        return Mono.error(throwable);
    }

    /**
     * 재시도 소진 처리
     */
    private Mono<Void> handleRetryExhausted(ServerWebExchange exchange, Throwable throwable) {
        try {
            String jsonEvents = baseFilter.createEventGridEvent(exchange, "RetryExhausted");
            return eventGridService.publishEvent(jsonEvents)
                    .then(Mono.fromRunnable(() -> {
                        Boolean isTestMember = exchange.getAttribute("isTestMember");
                        setRetryExhaustedResponse(exchange, throwable,
                                Boolean.TRUE.equals(isTestMember));
                    }))
                    .then(exchange.getResponse().setComplete());
        } catch (Exception e) {
            log.error("Failed to process retry exhausted event", e);
            return Mono.error(e);
        }
    }

    /**
     * 재시도 소진 응답 설정
     */
    private void setRetryExhaustedResponse(ServerWebExchange exchange,
                                           Throwable throwable,
                                           boolean isTestMember) {
        HttpStatus status = isTestMember ?
                HttpStatus.FORBIDDEN : HttpStatus.SERVICE_UNAVAILABLE;
        String message = isTestMember ?
                "Retry failed for test member" : throwable.getMessage();

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().add("X-Retry-Exhausted", "true");
        exchange.getResponse().getHeaders().add("X-Error-Message", message);
    }

    /**
     * Retry 필터 설정 클래스
     */
    @Data
    public static class Config {
        /**
         * Retry 이름
         * application.yml의 resilience4j.retry.instances에 정의된 이름과 매칭
         */
        private String name;
    }
}
