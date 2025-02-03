// File: membership/gateway/src/main/java/com/telecom/membership/gateway/filter/RateLimitingFilter.java
package com.telecom.membership.gateway.filter;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.springframework.http.HttpStatus.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitingFilter implements WebFilter {

    private final RateLimiterRegistry rateLimiterRegistry;

    private static final Map<String, String> PARTNER_TYPE_MAPPING = Map.of(
            "MART", "mart",
            "CONVENIENCE", "convenience",
            "ONLINE", "online"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String rawPartnerType = exchange.getRequest().getHeaders()
                .getFirst("X-Partner-Type");

        // 파트너 타입 헤더가 없으면 기본 처리
        if (rawPartnerType == null) {
            log.debug("No partner type header found, skipping rate limiting");
            return chain.filter(exchange);
        }

        String normalizedPartnerType = PARTNER_TYPE_MAPPING.get(rawPartnerType.toUpperCase());
        if (normalizedPartnerType == null) {
            log.warn("Invalid partner type received: {}", rawPartnerType);
            exchange.getResponse().setStatusCode(BAD_REQUEST);
            return exchange.getResponse().setComplete();
        }

        // 파트너 타입에 해당하는 rate limiter 가져오기
        RateLimiter limiter;
        try {
            limiter = rateLimiterRegistry.rateLimiter(normalizedPartnerType);
        } catch (Exception e) {
            log.error("Failed to get rate limiter for partner type: {}", normalizedPartnerType, e);
            exchange.getResponse().setStatusCode(INTERNAL_SERVER_ERROR);
            return exchange.getResponse().setComplete();
        }

        // rate limiter를 적용하여 요청 처리
        return Mono.just(exchange)
                .transformDeferred(RateLimiterOperator.of(limiter))
                .flatMap(chain::filter)
                .onErrorResume(RequestNotPermitted.class, e -> {
                    log.warn("Rate limit exceeded for partner type: {}", normalizedPartnerType);

                    exchange.getResponse().setStatusCode(TOO_MANY_REQUESTS);
                    return exchange.getResponse().setComplete();
                })
                .onErrorResume(Exception.class, e -> {
                    log.error("Unexpected error during rate limiting", e);
                    exchange.getResponse().setStatusCode(INTERNAL_SERVER_ERROR);
                    return exchange.getResponse().setComplete();
                });
    }
}
