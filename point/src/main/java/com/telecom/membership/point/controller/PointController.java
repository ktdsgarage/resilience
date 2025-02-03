// File: membership/point/src/main/java/com/telecom/membership/point/controller/PointController.java
package com.telecom.membership.point.controller;

import com.telecom.membership.common.dto.PointRequest;
import com.telecom.membership.common.dto.PointResponse;
import com.telecom.membership.common.response.ApiResponse;
import com.telecom.membership.point.domain.PointTransaction;
import com.telecom.membership.point.service.PointService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/points")
@RequiredArgsConstructor
@Tag(name = "포인트 적립 API", description = "멤버십 포인트 적립 관련 API를 제공합니다.")
public class PointController {

    private final PointService pointService;

    @PostMapping("/accumulate")
    @Operation(summary = "포인트 적립", description = "회원의 구매 금액에 따라 포인트를 적립합니다.")
    public Mono<ResponseEntity<ApiResponse<PointResponse>>> accumulatePoints(
            @RequestBody PointRequest request) {
        return pointService.processPointAccumulation(request)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)))
                .doOnSuccess(response -> log.info("Points accumulated successfully for member: {}", request.getMemberId()))
                .doOnError(error -> log.error("Error accumulating points for member: {}", request.getMemberId(), error));
    }

    @GetMapping("/transactions/{memberId}")
    @Operation(summary = "포인트 거래내역 조회", description = "회원의 포인트 거래내역을 조회합니다.")
    public Mono<ResponseEntity<ApiResponse<List<PointTransaction>>>> getTransactions(
            @Parameter(description = "회원 ID", required = true)
            @PathVariable String memberId,
            @Parameter(description = "조회 시작일 (yyyy-MM-dd)")
            @RequestParam(required = false) String startDate,
            @Parameter(description = "조회 종료일 (yyyy-MM-dd)")
            @RequestParam(required = false) String endDate) {

        return pointService.getTransactions(memberId, startDate, endDate)
                .collectList()
                .map(transactions -> ResponseEntity.ok(ApiResponse.success(transactions)))
                .doOnSuccess(response -> log.info("Retrieved transactions for member: {}", memberId))
                .doOnError(error -> log.error("Error retrieving transactions for member: {}", memberId, error));
    }

    @GetMapping("/health")
    @Operation(summary = "서비스 상태 체크", description = "서비스의 상태와 가용성을 확인합니다.")
    public Mono<ResponseEntity<ApiResponse<String>>> healthCheck() {
        return Mono.just(ResponseEntity.ok(ApiResponse.success("Service is healthy - " + LocalDateTime.now())))
                .doOnSuccess(response -> log.info("Health check successful"))
                .doOnError(error -> log.error("Error during health check", error));
    }
}
