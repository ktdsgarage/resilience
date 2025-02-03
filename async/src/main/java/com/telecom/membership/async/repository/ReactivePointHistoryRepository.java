package com.telecom.membership.async.repository;

import com.telecom.membership.async.domain.PointHistory;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface ReactivePointHistoryRepository
        extends ReactiveMongoRepository<PointHistory, String> {

    @Query("{'status': 'FAILED', 'retryCount': {$lt: ?0}}")
    Flux<PointHistory> findFailedRetries(int maxRetries);

    // 필요시 상태를 파라미터로 받을 수 있음
    @Query("{'status': ?0, 'retryCount': {$lt: ?1}}")
    Flux<PointHistory> findRetriesByStatus(String status, int maxRetries);
}