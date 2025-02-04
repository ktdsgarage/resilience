// File: membership/gateway/src/main/java/com/telecom/membership/gateway/filter/RateLimitingGatewayFilterFactory.java
package com.telecom.membership.gateway.filter;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 파트너 타입별 요청 비율을 제어하는 Rate Limiting 필터
 *
 * @version 1.0
 * @author Digital Platform Development Team
 * @since 2024.02.04
 *
 * <pre>
 * << 개정이력(Modification Information) >>
 *
 *   수정일         수정자           수정내용
 *  ----------    --------    ---------------------------
 *  2024.02.04    갑빠       최초 생성
 *  2024.02.05    온달       파트너 타입별 처리율 제어 로직 추가
 * </pre>
 */
@Slf4j
@Component
public class RateLimitingGatewayFilterFactory extends AbstractGatewayFilterFactory<RateLimitingGatewayFilterFactory.Config> {

    /** Rate Limiter 레지스트리 */
    private final RateLimiterRegistry rateLimiterRegistry;

    /** 파트너 타입 매핑 */
    private static final Map<String, String> PARTNER_TYPE_MAPPING = Map.of(
            "MART", "mart",
            "CONVENIENCE", "convenience",
            "ONLINE", "online"
    );

    /**
     * RateLimitingGatewayFilterFactory 생성자
     *
     * @param rateLimiterRegistry Rate Limiter 레지스트리
     */
    public RateLimitingGatewayFilterFactory(RateLimiterRegistry rateLimiterRegistry) {
        super(Config.class);
        this.rateLimiterRegistry = rateLimiterRegistry;
    }

    /**
     * Gateway Filter 적용
     *
     * @param config 필터 설정
     * @return GatewayFilter 구현체
     */
    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String rawPartnerType = exchange.getRequest().getHeaders()
                    .getFirst("X-Partner-Type");

            // 파트너 타입 헤더 검증
            if (!isValidPartnerType(rawPartnerType)) {
                log.debug("### RateLimiting: No valid partner type header found, using default rate limiter");
                return chain.filter(exchange);
            }

            // 파트너 타입 정규화 및 유효성 검증
            String normalizedPartnerType = normalizePartnerType(rawPartnerType);
            if (normalizedPartnerType == null) {
                log.warn("Invalid partner type received: {}", rawPartnerType);
                exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
                return exchange.getResponse().setComplete();
            }

            // Rate Limiter 적용
            return applyRateLimiter(exchange, chain, normalizedPartnerType);
        };
    }

    private boolean isValidPartnerType(String rawPartnerType) {
        return rawPartnerType != null && !rawPartnerType.trim().isEmpty();
    }

    /**
     * 파트너 타입 정규화
     *
     * @param rawPartnerType 원본 파트너 타입
     * @return 정규화된 파트너 타입, 유효하지 않은 경우 null
     */
    private String normalizePartnerType(String rawPartnerType) {
        try {
            return PARTNER_TYPE_MAPPING.get(rawPartnerType.toUpperCase());
        } catch (Exception e) {
            log.error("Partner type normalization failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Rate Limiter 적용 처리
     *
     * @param exchange 서버 웹 교환 객체
     * @param chain 필터 체인
     * @param partnerType 파트너 타입
     * @return 처리 결과
     */
    private Mono<Void> applyRateLimiter(ServerWebExchange exchange,
                                        GatewayFilterChain chain,
                                        String partnerType) {
        try {
            RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter(partnerType);
            RateLimiter.Metrics metrics = rateLimiter.getMetrics();

            log.debug("### RateLimiting: Status [{}]: Available = {}",
                    partnerType,
                    metrics.getAvailablePermissions());

            return Mono.just(exchange)
                    .transformDeferred(RateLimiterOperator.of(rateLimiter))
                    .flatMap(chain::filter)
                    //.doOnSuccess(v -> log.debug("### RateLimiting: [{}] Success", partnerType))
                    .doOnError(Exception.class, e -> log.error("### RateLimiting: [{}] Exception => {}", partnerType, e.getMessage()))
                    .doOnError(RequestNotPermitted.class, e -> log.error("### RateLimiting: [{}] RequestNotPermitted => {}", partnerType, e.getMessage()))
                    .onErrorResume(RequestNotPermitted.class, e -> {
                        log.error("### RateLimiting: [{}] Rate limit exceeded", partnerType, e);
                        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                        exchange.getResponse().getHeaders().add("X-RateLimit-Exceeded", "true");
                        exchange.getResponse().getHeaders().add("X-Partner-Type", partnerType);

                        return exchange.getResponse().setComplete();
                    });

        } catch (Exception e) {
            log.error("### RateLimiting: [{}] Failed=>{}", partnerType, e.getMessage());
            exchange.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return exchange.getResponse().setComplete();
        }
    }

    /**
     * Rate Limiting 필터 설정 클래스
     */
    @Data
    public static class Config {

    }
}
