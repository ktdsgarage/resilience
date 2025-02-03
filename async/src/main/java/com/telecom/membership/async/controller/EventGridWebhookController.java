// File: membership/async/src/main/java/com/telecom/membership/async/controller/EventGridWebhookController.java
package com.telecom.membership.async.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.membership.async.dto.ValidationResponse;
import com.telecom.membership.async.service.PointHistoryManager;
import com.telecom.membership.common.dto.PointRequest;
import com.telecom.membership.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Slf4j
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
@Tag(name = "Event Grid Webhook API", description = "Event Grid에서 전송된 이벤트를 처리하는 API")
public class EventGridWebhookController {

    private final ObjectMapper objectMapper;
    private final PointHistoryManager historyManager;

    @PostMapping("/point")
    @Operation(summary = "이벤트 수신", description = "Event Grid에서 전송된 포인트 관련 이벤트를 처리합니다.")
    public Mono<ResponseEntity<?>> handleEvent(
            @RequestHeader(value = "aeg-event-type", required = false) String aegEventType,
            @RequestBody String requestBody) {

        return Mono.defer(() -> {
            try {
                log.info("Received event type: {}", aegEventType);
                log.debug("Request body: {}", requestBody);

                // Subscription Validation 처리
                if ("SubscriptionValidation".equals(aegEventType)) {
                    JsonNode[] events = objectMapper.readValue(requestBody, JsonNode[].class);
                    String validationCode = events[0].get("data").get("validationCode").asText();
                    log.info("Handling validation request with code: {}", validationCode);
                    return Mono.just(ResponseEntity.ok().body(new ValidationResponse(validationCode)));
                }

                // 실제 이벤트 처리
                JsonNode[] events = objectMapper.readValue(requestBody, JsonNode[].class);
                JsonNode eventNode = events[0];
                String eventType = eventNode.get("eventType").asText();
                JsonNode data = eventNode.get("data");

                log.info("Processing {} event. Data: {}", eventType, data);

                // point 서비스 호출을 위한 요청 생성
                PointRequest pointRequest = PointRequest.builder()
                        .memberId(data.get("memberId").asText())
                        .partnerId(data.get("partnerId").asText())
                        .partnerType(data.get("partnerType").asText())
                        .amount(new BigDecimal(data.get("amount").asText()))
                        .build();

                // point 적립 처리
                return historyManager.processPointAccumulation(pointRequest)
                        .map(response -> ResponseEntity.ok(ApiResponse.success(response)))
                        .onErrorResume(error -> {
                            log.error("Failed to process point accumulation for event type: {}", eventType, error);
                            return Mono.just(ResponseEntity.internalServerError()
                                    .body(ApiResponse.error("이벤트 처리 중 오류가 발생했습니다: " + error.getMessage())));
                        });

            } catch (Exception e) {
                log.error("Error processing event", e);
                return Mono.just(ResponseEntity.internalServerError()
                        .body(ApiResponse.error("이벤트 처리 중 오류가 발생했습니다: " + e.getMessage())));
            }
        });
    }

    @GetMapping("/health")
    @Operation(summary = "헬스 체크", description = "서비스의 상태를 확인합니다.")
    public Mono<ResponseEntity<ApiResponse<String>>> healthCheck() {
        return Mono.just(ResponseEntity.ok(ApiResponse.success("Healthy - " + LocalDateTime.now())));
    }
}