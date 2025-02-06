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

/**
 * Event Grid로부터 수신된 이벤트를 처리하는 컨트롤러
 * WebFlux를 사용하여 비동기-논블로킹 방식으로 요청을 처리합니다.
 *
 * @author point-team
 * @version 1.0
 */
@Slf4j
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
@Tag(name = "Event Grid Webhook API", description = "Event Grid에서 전송된 이벤트를 처리하는 API")
public class EventGridWebhookController {

    private final ObjectMapper objectMapper;
    private final PointHistoryManager historyManager;

    /**
     * Event Grid로부터 수신된 이벤트를 처리합니다.
     * Mono.defer를 사용하여 구독 시점(API요청이 오는 시점에 WebFlux 내부 객체가 구독)에 로직이 실행되도록 지연시킵니다.
     *
     * @param aegEventType Event Grid 이벤트 타입 헤더
     * @param requestBody 이벤트 데이터
     * @return 처리 결과 응답
     */
    @PostMapping("/point")
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

                /**
                 * 포인트 적립을 비동기-논블로킹 방식으로 처리합니다.
                 * 요청 처리부터 응답 생성, 에러 처리까지의 전체 흐름을 리액티브 스트림으로 구성합니다.
                 */
                return historyManager.processPointAccumulation(pointRequest)
                        // map: 스트림의 각 요소를 변환하는 연산자-리턴 객체 형식에 맞게 변환하여 리턴
                        // - 입력: PointResponse(processPointAccumulation의 결과 객체)
                        // - 출력: ResponseEntity<ApiResponse<PointResponse>>
                        // - 특징: 동기적 변환에 사용되며, 새로운 비동기 작업을 만들지 않음
                        .map(response -> ResponseEntity.ok(ApiResponse.success(response)))

                        // onErrorResume: 에러 발생 시 대체 스트림을 제공하는 연산자
                        // - 입력: 발생한 에러(Throwable)
                        // - 출력: 대체할 새로운 Mono 스트림
                        // - 특징: try-catch와 유사하나, 리액티브 방식으로 에러를 처리
                        .onErrorResume(error -> {
                            // 에러 로깅
                            log.error("Failed to process point accumulation for event type: {}",
                                    eventType, error);

                            // Mono.just: 단일 값을 포함하는 새로운 Mono를 생성하는 팩토리 메서드
                            // - 입력: error 객체
                            // - 출력: 해당 값을 포함하는 Mono
                            // - 특징: 이미 존재하는 값을 리액티브 스트림으로 변환
                            return Mono.just(ResponseEntity.internalServerError()
                                    .body(ApiResponse.error(
                                            "이벤트 처리 중 오류가 발생했습니다: " + error.getMessage()
                                    )));
                        });

            } catch (Exception e) {
                log.error("Error processing event", e);
                return Mono.just(ResponseEntity.internalServerError()
                        .body(ApiResponse.error("이벤트 처리 중 오류가 발생했습니다: " + e.getMessage())));
            }
        });
    }

    /**
     * 서비스 상태를 확인합니다.
     *
     * @return 서비스 상태 정보
     */
    @GetMapping("/health")
    @Operation(summary = "헬스 체크", description = "서비스의 상태를 확인합니다.")
    public Mono<ResponseEntity<ApiResponse<String>>> healthCheck() {
        return Mono.just(ResponseEntity.ok(ApiResponse.success("Healthy - " + LocalDateTime.now())));
    }
}
