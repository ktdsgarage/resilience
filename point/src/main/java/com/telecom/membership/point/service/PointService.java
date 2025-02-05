// File: membership/point/src/main/java/com/telecom/membership/point/service/PointService.java
package com.telecom.membership.point.service;

import com.telecom.membership.common.dto.PointRequest;
import com.telecom.membership.common.dto.PointResponse;
import com.telecom.membership.common.enums.TransactionStatus;
import com.telecom.membership.common.exception.PointException;
import com.telecom.membership.point.domain.PointTransaction;
import com.telecom.membership.point.repository.ReactivePointTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 포인트 적립 및 조회를 처리하는 서비스 클래스입니다.
 * WebFlux의 리액티브 스트림을 활용하여 고성능 비동기 처리를 구현합니다.
 * 주요 책임:
 * - 포인트 적립 요청의 유효성 검증
 * - 포인트 계산 및 트랜잭션 처리
 * - 포인트 조회 및 이력 관리
 *
 * @author point-team
 * @version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointService {
    private final ReactivePointTransactionRepository repository;
    private final PointCalculator pointCalculator;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 포인트 적립을 처리합니다.
     * 처리 흐름:
     * 1. validateRequest: 요청 유효성 검증
     * 2. calculatePoints: 적립 포인트 계산
     * 3. saveTransaction: 트랜잭션 저장
     * 4. createResponse: 응답 생성
     * 각 단계는 리액티브 스트림으로 연결되어 있어,
     * 하나의 스레드에서 논블로킹 방식으로 실행됩니다.
     *
     * @param request 포인트 적립 요청 정보
     * @return 처리 결과를 담은 Mono<PointResponse>
     */
    public Mono<PointResponse> processPointAccumulation(PointRequest request) {
        return validateRequest(request)
                .flatMap(this::calculatePoints)
                .flatMap(this::saveTransaction)
                .map(this::createResponse)
                // doOnError: 에러 발생 시 로깅 및 에러 변환
                .doOnError(e -> {
                    log.error("Error processing points: {}", e.getMessage(), e);
                    if (!(e instanceof PointException)) {
                        throw new PointException.DatabaseException("Database error occurred");
                    }
                });
    }

    /**
     * 요청의 유효성을 검증합니다.
     * Mono.filter를 사용하여 조건을 만족하지 않는 요청을 필터링합니다.
     */
    private Mono<PointRequest> validateRequest(PointRequest request) {
        return Mono.just(request)
                // filter: 조건을 만족하지 않는 요청을 필터링
                .filter(r -> r.getAmount().compareTo(BigDecimal.ZERO) > 0)
                // switchIfEmpty: filter 결과가 비어있을 때 대체 값(에러) 제공
                .switchIfEmpty(Mono.error(new PointException(PointException.INVALID_AMOUNT)));
    }

    /**
     * 포인트를 계산하고 트랜잭션 객체를 생성합니다.
     * fromCallable()이 필요한 상황:
     * - 매 요청마다 새로운 값을 생성해야 할 때 (예: 타임스탬프)
     * - CPU 집약적인 계산이 필요할 때
     * - 블로킹 작업을 포함할 때 (예: 레거시 시스템 연동)
     * just()나 단순 map()으로 충분한 경우:
     * - 캐시된 값을 반환할 때
     * - 단순한 값 변환만 필요할 때
     * - 이미 존재하는 객체를 반환할 때
     */
    private Mono<PointTransaction> calculatePoints(PointRequest request) {

        return Mono.fromCallable(() ->
                PointTransaction.builder()
                        .memberId(request.getMemberId())
                        .partnerId(request.getPartnerId())
                        .partnerType(request.getPartnerType())
                        .amount(request.getAmount())
                        .points(pointCalculator.calculate(request))
                        .transactionTime(LocalDateTime.now())
                        .status(TransactionStatus.COMPLETED)
                        .build()
        );
    }

    /**
     * 트랜잭션을 저장하고 상태를 업데이트합니다.
     * doOnSuccess와 doOnError를 사용하여 처리 결과에 따른 부가 작업을 수행합니다.
     */
    private Mono<PointTransaction> saveTransaction(PointTransaction transaction) {
        return repository.save(transaction)
                // doOnSuccess: 저장 성공 시 상태 업데이트
                .doOnSuccess(tx -> tx.setStatus("COMPLETED"))
                // doOnError: 저장 실패 시 상태 변경 및 로깅
                .doOnError(e -> {
                    transaction.setStatus("FAILED");
                    log.error("Failed to save transaction", e);
                });
    }

    private PointResponse createResponse(PointTransaction transaction) {
        return PointResponse.builder()
                .transactionId(transaction.getId())
                .memberId(transaction.getMemberId())
                .partnerId(transaction.getPartnerId())
                .partnerType(transaction.getPartnerType())
                .amount(transaction.getAmount())
                .points(transaction.getPoints())
                .status(transaction.getStatus())
                .processedAt(transaction.getTransactionTime())
                .message(getStatusMessage(transaction.getStatus()))
                .build();
    }

    private String getStatusMessage(String status) {
        return switch (status) {
            case "COMPLETED" -> "포인트가 정상적으로 적립되었습니다.";
            case "FAILED" -> "포인트 적립에 실패했습니다.";
            default -> "처리 중입니다.";
        };
    }

    /**
     * 포인트 거래내역을 조회합니다.
     * Flux를 사용하여 여러 건의 트랜잭션을 스트리밍 방식으로 처리합니다.
     *
     * @param memberId 회원 ID
     * @param startDateStr 조회 시작일 (yyyy-MM-dd)
     * @param endDateStr 조회 종료일 (yyyy-MM-dd)
     * @return 거래내역 스트림 (Flux<PointTransaction>)
     */
    public Flux<PointTransaction> getTransactions(String memberId,
                                                  String startDateStr, String endDateStr) {
        try {
            LocalDateTime startDateTime = parseStartDate(startDateStr);
            LocalDateTime endDateTime = parseEndDate(endDateStr);

            validateDateRange(startDateTime, endDateTime);

            // 리액티브 레포지토리를 통한 데이터 조회
            return repository.findByMemberIdAndTransactionTimeBetween(
                            memberId, startDateTime, endDateTime)
                    // doOnComplete: 스트림 완료 시 로깅
                    .doOnComplete(() -> log.info(
                            "Retrieved transactions for memberId={} between {} and {}",
                            memberId, startDateTime, endDateTime))
                    // doOnError: 에러 발생 시 로깅
                    .doOnError(error -> log.error(
                            "Error retrieving transactions for memberId={}",
                            memberId, error));

        } catch (DateTimeParseException e) {
            log.error("Invalid date format for memberId={}", memberId, e);
            // Flux.error: 에러를 포함한 스트림 생성
            return Flux.error(new PointException("Invalid date format. Please use yyyy-MM-dd"));

        } catch (IllegalArgumentException e) {
            log.error("Invalid date range for memberId={}", memberId, e);
            return Flux.error(new PointException(e.getMessage()));
        }
    }

    private LocalDateTime parseStartDate(String startDateStr) {
        if (startDateStr == null) {
            // 기본값: 1개월 전
            return LocalDateTime.now().minusMonths(1).with(LocalTime.MIN);
        }
        return LocalDate.parse(startDateStr, DATE_FORMATTER).atStartOfDay();
    }

    private LocalDateTime parseEndDate(String endDateStr) {
        if (endDateStr == null) {
            // 기본값: 현재
            return LocalDateTime.now();
        }
        return LocalDate.parse(endDateStr, DATE_FORMATTER).atTime(LocalTime.MAX);
    }

    private void validateDateRange(LocalDateTime startDateTime, LocalDateTime endDateTime) {
        if (startDateTime.isAfter(endDateTime)) {
            throw new IllegalArgumentException("Start date must be before end date");
        }

        if (startDateTime.plusMonths(3).isBefore(endDateTime)) {
            throw new IllegalArgumentException("Date range cannot exceed 3 months");
        }
    }
}