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

@Service
@Slf4j
@RequiredArgsConstructor
public class EventGridService {
    private final EventGridPublisherClient client;
    private final ObjectMapper objectMapper;

    public Mono<Void> publishEvent(String jsonEventString) {
        return Mono.defer(() -> {  // defer를 사용하여 실행 시점 제어
            try {
                JsonNode event = objectMapper.readTree(jsonEventString);
                String eventType = event.get("eventType").asText();

                EventGridEvent gridEvent = new EventGridEvent(
                        event.get("subject").asText(),
                        eventType,
                        BinaryData.fromObject(event.get("data")),
                        "1.0"
                );

                return Mono.fromCallable(() -> {  // fromCallable을 사용하여 동기 호출 래핑
                    client.sendEvent(gridEvent);
                    return null;
                });

            } catch (Exception e) {
                return Mono.error(new PointException.EventPublishException(e));
            }
        });
    }
}