// BaseFilter.java
package com.telecom.membership.gateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Gateway Filter의 공통 기능을 제공하는 추상 클래스
 * - 요청 본문 캐싱
 * - 요청 데코레이팅
 * - EventGrid 이벤트 생성
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BaseFilter {
    private final ObjectMapper objectMapper;
    private static final String CACHED_REQUEST_BODY_ATTR = "cachedRequestBody";

    /**
     * 요청 본문을 캐시하고 byte 배열로 반환
     * DataBuffer를 적절히 해제하여 메모리 누수 방지
     */
    protected Mono<byte[]> cacheRequestBody(ServerWebExchange exchange) {
        return DataBufferUtils.join(exchange.getRequest().getBody())
                .doOnError(error ->
                        log.error("요청 본문 캐싱 중 오류 발생", error))
                .map(dataBuffer -> {
                    try {
                        byte[] bytes = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(bytes);
                        exchange.getAttributes().put(CACHED_REQUEST_BODY_ATTR, bytes);
                        return bytes;
                    } finally {
                        DataBufferUtils.release(dataBuffer);
                    }
                });
    }

    /**
     * 캐시된 본문으로 새로운 요청 객체 생성
     * exchange의 응답 버퍼 팩토리를 사용하여 메모리 효율성 보장
     */
    protected ServerHttpRequest decorateRequest(ServerWebExchange exchange, byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("요청 본문이 null입니다");
        }

        return new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            @NonNull
            public Flux<DataBuffer> getBody() {
                return Flux.just(exchange.getResponse().bufferFactory().wrap(bytes))
                        .doOnError(error ->
                                log.error("요청 본문 데코레이팅 중 오류 발생", error));
            }
        };
    }

    /**
     * EventGrid 이벤트 생성
     * 요청 본문의 필수 필드 존재 여부 확인
     */
    protected String createEventGridEvent(ServerWebExchange exchange, String eventType) {
        try {
            byte[] cachedBody = exchange.getAttribute(CACHED_REQUEST_BODY_ATTR);
            if (cachedBody == null) {
                throw new IllegalStateException("캐시된 요청 본문이 없습니다");
            }

            JsonNode requestBody = objectMapper.readTree(cachedBody);
            validateRequestBody(requestBody);

            Map<String, Object> event = createEventMap(requestBody, eventType);
            return objectMapper.writeValueAsString(event);

        } catch (Exception e) {
            log.error("EventGrid 이벤트 생성 중 오류 발생", e);
            throw new RuntimeException("EventGrid 이벤트 생성 실패", e);
        }
    }

    /**
     * 요청 본문의 필수 필드 검증
     */
    private void validateRequestBody(JsonNode requestBody) {
        List<String> requiredFields = Arrays.asList("memberId", "partnerId", "partnerType", "amount");

        for (String field : requiredFields) {
            if (!requestBody.has(field) || requestBody.get(field).isNull()) {
                throw new IllegalArgumentException(String.format("필수 필드 누락: %s", field));
            }
        }
    }

    /**
     * EventGrid 이벤트 맵 생성
     * 모든 필드를 문자열로 변환하여 일관성 유지
     */
    private Map<String, Object> createEventMap(JsonNode requestBody, String eventType) {
        // 이벤트 데이터 생성
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("memberId", requestBody.get("memberId").asText());
        eventData.put("partnerId", requestBody.get("partnerId").asText());
        eventData.put("partnerType", requestBody.get("partnerType").asText());
        eventData.put("amount", requestBody.get("amount").asText());

        // 이벤트 메타데이터 생성
        Map<String, Object> event = new HashMap<>();
        event.put("id", UUID.randomUUID().toString());
        event.put("subject", "pointAccumulation");
        event.put("type", eventType);
        event.put("data", eventData);
        event.put("eventTime", OffsetDateTime.now().toString());
        event.put("metadataVersion", "1");
        event.put("dataVersion", "1");

        return event;
    }
}
