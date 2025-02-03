// File: membership/async/src/main/java/com/telecom/membership/async/service/PointHistoryManager.java
package com.telecom.membership.async.service;

import com.telecom.membership.async.domain.PointHistory;
import com.telecom.membership.async.repository.ReactivePointHistoryRepository;
import com.telecom.membership.common.dto.PointRequest;
import com.telecom.membership.common.dto.PointResponse;
import com.telecom.membership.common.enums.TransactionStatus;
import com.telecom.membership.common.service.PointProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class PointHistoryManager {
    private final ReactivePointHistoryRepository historyRepository;
    private final PointProcessor pointProcessor;

    public Mono<PointResponse> processPointAccumulation(PointRequest request) {
        return pointProcessor.processPoints(request)
                .flatMap(response -> {
                    // 성공 시 PointHistory 생성
                    PointHistory history = PointHistory.builder()
                            .memberId(request.getMemberId())
                            .partnerId(request.getPartnerId())
                            .partnerType(request.getPartnerType())
                            .amount(request.getAmount())
                            .points(response.getPoints())
                            .transactionTime(LocalDateTime.now())
                            .status(TransactionStatus.COMPLETED.name())
                            .processType("ACCUMULATION")
                            .retryCount(0)
                            .build();

                    // PointHistory를 저장하고 원본 response를 유지
                    return historyRepository.save(history)
                            .map(savedHistory -> {
                                return PointResponse.builder()
                                        .transactionId(response.getTransactionId())
                                        .memberId(savedHistory.getMemberId())
                                        .partnerId(savedHistory.getPartnerId())
                                        .partnerType(savedHistory.getPartnerType())
                                        .amount(savedHistory.getAmount())
                                        .points(savedHistory.getPoints())
                                        .status(TransactionStatus.COMPLETED.name())
                                        .processedAt(savedHistory.getTransactionTime())
                                        .message("포인트가 정상적으로 처리되었습니다.")
                                        .build();
                            });
                })
                .onErrorResume(error -> {
                    log.error("Point accumulation failed for memberId={}, amount={}, error={}",
                            request.getMemberId(),
                            request.getAmount(),
                            error.getMessage(),
                            error);

                    // 실패 시 PointHistory 생성
                    PointHistory history = PointHistory.builder()
                            .memberId(request.getMemberId())
                            .partnerId(request.getPartnerId())
                            .partnerType(request.getPartnerType())
                            .amount(request.getAmount())
                            .points(BigDecimal.ZERO)
                            .transactionTime(LocalDateTime.now())
                            .status(TransactionStatus.FAILED.name())
                            .processType("ACCUMULATION")
                            .retryCount(0)
                            .errorMessage(error.getMessage())
                            .build();

                    // 실패 이력 저장 후 원본 에러 전파
                    return historyRepository.save(history)
                            .then(Mono.error(error));
                });
    }
}