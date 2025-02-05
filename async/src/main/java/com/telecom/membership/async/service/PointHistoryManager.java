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

/**
 * 포인트 적립 이력을 관리하는 서비스
 * 포인트 처리 결과를 이력으로 저장하고 관리합니다.
 *
 * @author point-team
 * @version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointHistoryManager {
    private final ReactivePointHistoryRepository historyRepository;
    private final PointProcessor pointProcessor;

    /**
     * 포인트 적립을 처리하고 이력을 저장합니다.
     * flatMap을 사용하여 포인트 처리와 이력 저장을 비동기적으로 연결합니다.
     *
     * @param request 포인트 적립 요청
     * @return 처리 결과
     */
    public Mono<PointResponse> processPointAccumulation(PointRequest request) {
        return pointProcessor.processPoints(request)
                /*
                map대신에 flatMap을 사용하는 이유
                flatMap은 변환 작업의 결과가 또 다른 리액티브 타입(Mono나 Flux)일 때 사용합니다.
                중첩된 리액티브 타입을 하나의 평면적인 스트림으로 만들어줍니다.
                save()는 Mono<PointHistory>를 반환하므로 이 함수의 결과가 Mono<Mono<PointHistory>>가 되는것을
                방지하기 위해 사용합니다.
                쉽게 생각하면 결과 객체가 리액티브 타입(Mono/Flux)인 객체를 다루려면 flatMap을 사용해야 합니다.
                 */
                .flatMap(response -> {
                    // 포인트 적립 성공 시 이력 생성
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

                    // 이력 저장 후 응답 생성
                    return historyRepository.save(history)
                            .map(savedHistory -> PointResponse.builder()
                                    .transactionId(response.getTransactionId())
                                    .memberId(savedHistory.getMemberId())
                                    .partnerId(savedHistory.getPartnerId())
                                    .partnerType(savedHistory.getPartnerType())
                                    .amount(savedHistory.getAmount())
                                    .points(savedHistory.getPoints())
                                    .status(TransactionStatus.COMPLETED.name())
                                    .processedAt(savedHistory.getTransactionTime())
                                    .message("포인트가 정상적으로 처리되었습니다.")
                                    .build());
                })
                .onErrorResume(error -> {
                    log.error("Point accumulation failed for memberId={}, amount={}, error={}",
                            request.getMemberId(),
                            request.getAmount(),
                            error.getMessage(),
                            error);

                    // 실패 시 실패 이력 생성
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