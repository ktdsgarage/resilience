// BaseFilter.java
package com.telecom.membership.gateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.*;

@RequiredArgsConstructor
public abstract class BaseFilter {
    protected final ObjectMapper objectMapper;
    private static final String CACHED_REQUEST_BODY_ATTR = "cachedRequestBody";

    protected Mono<byte[]> cacheRequestBody(ServerWebExchange exchange) {
        return DataBufferUtils.join(exchange.getRequest().getBody())
                .map(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);
                    exchange.getAttributes().put(CACHED_REQUEST_BODY_ATTR, bytes);
                    return bytes;
                });
    }

    protected ServerHttpRequest decorateRequest(ServerWebExchange exchange, byte[] bytes) {
        return new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public Flux<DataBuffer> getBody() {
                return Flux.just(exchange.getResponse().bufferFactory().wrap(bytes));
            }
        };
    }

    protected String createEventGridEvent(ServerWebExchange exchange, String eventType) throws Exception {
        byte[] cachedBody = exchange.getAttribute(CACHED_REQUEST_BODY_ATTR);
        JsonNode requestBody = objectMapper.readTree(cachedBody);

        Map<String, Object> event = createEventMap(requestBody, eventType);
        return objectMapper.writeValueAsString(event);
    }

    private Map<String, Object> createEventMap(JsonNode requestBody, String eventType) {
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("memberId", requestBody.get("memberId").asText());
        eventData.put("partnerId", requestBody.get("partnerId").asText());
        eventData.put("partnerType", requestBody.get("partnerType").asText());
        eventData.put("amount", requestBody.get("amount").asText());

        Map<String, Object> event = new HashMap<>();
        event.put("id", UUID.randomUUID().toString());
        event.put("subject", "pointAccumulation");
        event.put("eventType", eventType);
        event.put("type", eventType);
        event.put("data", eventData);
        event.put("eventTime", OffsetDateTime.now().toString());
        event.put("metadataVersion", "1");
        event.put("dataVersion", "1");

        return event;
    }
}
