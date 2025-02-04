// File: membership/common/src/main/java/com/telecom/membership/common/service/EventGridService.java
package com.telecom.membership.gateway.service;

import com.azure.core.util.BinaryData;
import com.azure.messaging.eventgrid.EventGridEvent;
import com.azure.messaging.eventgrid.EventGridPublisherClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.membership.common.exception.PointException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Azure Event Grid 이벤트 발행 서비스
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EventGridService {
    private final EventGridPublisherClient client;
    private final ObjectMapper objectMapper;

    /**
     * JSON 형식의 이벤트를 Event Grid로 발행
     *
     * @param jsonEventString 발행할 이벤트 JSON 문자열
     * @return 이벤트 발행 결과
     */
    public Mono<Void> publishEvent(String jsonEventString) {
        return Mono.defer(() -> {
                    try {
                        // JSON 파싱 및 EventGridEvent 생성
                        JsonNode event = objectMapper.readTree(jsonEventString);
                        validateEventFields(event);

                        EventGridEvent gridEvent = new EventGridEvent(
                                event.get("subject").asText(),
                                event.get("type").asText(),
                                BinaryData.fromObject(event.get("data")),
                                "1.0"
                        );

                        // 이벤트 발행
                        return Mono.fromRunnable(() -> {
                            try {
                                client.sendEvent(gridEvent);
                                log.debug("이벤트 발행 성공 - type: {}", gridEvent.getEventType());
                            } catch (Exception e) {
                                log.error("이벤트 발행 실패 - type: {}", gridEvent.getEventType(), e);
                                throw new PointException.EventPublishException(e);
                            }
                        }).then();  // Mono<Void>로 변환

                    } catch (Exception e) {
                        log.error("이벤트 생성 실패", e);
                        return Mono.error(new PointException.EventPublishException(e));
                    }
                });
    }

    /**
     * 이벤트 필수 필드 검증
     *
     * @param event 검증할 이벤트 JsonNode
     * @throws IllegalArgumentException 필수 필드 누락시
     */
    private void validateEventFields(JsonNode event) {
        List<String> requiredFields = Arrays.asList("subject", "type", "data");

        for (String field : requiredFields) {
            if (!event.has(field) || event.get(field).isNull()) {
                throw new IllegalArgumentException(
                        String.format("이벤트 필수 필드 누락: %s", field));
            }
        }
    }
}