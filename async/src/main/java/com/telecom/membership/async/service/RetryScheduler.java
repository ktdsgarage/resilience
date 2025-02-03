// File: membership/async/src/main/java/com/telecom/membership/async/service/RetryScheduler.java
package com.telecom.membership.async.service;

import com.telecom.membership.async.domain.PointHistory;
import com.telecom.membership.async.repository.ReactivePointHistoryRepository;
import com.telecom.membership.common.dto.PointRequest;
import com.telecom.membership.common.dto.PointResponse;
import com.telecom.membership.common.enums.TransactionStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Configuration
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryScheduler {
    private final ReactivePointHistoryRepository historyRepository;
    private final PointHistoryManager historyManager;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;

    @Value("${point.retry.max-count:3}")
    private int retryMaxCount;

    @Scheduled(fixedDelayString = "${point.retry.interval:30000}")
    public void retryFailedRequests() {
        log.info("Starting retry of pending point requests");

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("async-processor");
        Retry retry = retryRegistry.retry("async-retry");

        historyRepository.findFailedRetries(retryMaxCount)
                .flatMap(history -> processRetry(history, circuitBreaker, retry))
                .subscribe(
                        success -> log.debug("Retry processed successfully: {}", success.getTransactionId()),
                        error -> log.error("Error during retry processing", error)
                );
    }

    private Mono<PointResponse> processRetry(PointHistory history, CircuitBreaker circuitBreaker, Retry retry) {
        return Mono.just(convertToPointRequest(history))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(RetryOperator.of(retry))
                .flatMap(historyManager::processPointAccumulation)
                .onErrorResume(error -> {
                    log.error("Retry failed for history id={}", history.getId(), error);

                    // 에러 처리를 위한 history 업데이트
                    history.setStatus(
                            history.getRetryCount() >= retryMaxCount ?
                                    TransactionStatus.MAX_RETRY_EXCEEDED.name() :
                                    TransactionStatus.FAILED.name()
                    );
                    history.setErrorMessage(error.getMessage());
                    history.setRetryCount(history.getRetryCount() + 1);
                    history.setLastRetryTime(LocalDateTime.now());

                    return historyRepository.save(history)
                            .then(Mono.error(error)); // 원본 에러를 전파하여 상위에서 처리하도록 함
                });
    }

    private PointRequest convertToPointRequest(PointHistory history) {
        return PointRequest.builder()
                .memberId(history.getMemberId())
                .partnerId(history.getPartnerId())
                .partnerType(history.getPartnerType())
                .amount(history.getAmount())
                .build();
    }
}
