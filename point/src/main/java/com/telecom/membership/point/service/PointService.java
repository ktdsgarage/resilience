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

@Slf4j
@Service
@RequiredArgsConstructor
public class PointService {
    private final ReactivePointTransactionRepository repository;
    private final PointCalculator pointCalculator;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public Mono<PointResponse> processPointAccumulation(PointRequest request) {
        return validateRequest(request)
                .flatMap(this::calculatePoints)
                .flatMap(this::saveTransaction)
                .map(this::createResponse)
                .doOnError(e -> {
                    log.error("Error processing points: {}", e.getMessage(), e);
                    if (!(e instanceof PointException)) {
                        throw new PointException.DatabaseException("Database error occurred");
                    }
                });
    }

    private Mono<PointRequest> validateRequest(PointRequest request) {
        return Mono.just(request)
                .filter(r -> r.getAmount().compareTo(BigDecimal.ZERO) > 0)
                .switchIfEmpty(Mono.error(new PointException(PointException.INVALID_AMOUNT)));
    }

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

    private Mono<PointTransaction> saveTransaction(PointTransaction transaction) {
        return repository.save(transaction)
                .doOnSuccess(tx -> tx.setStatus("COMPLETED"))
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
     * 회원의 포인트 거래내역을 조회합니다.
     *
     * @param memberId 회원 ID
     * @param startDateStr 조회 시작일 (yyyy-MM-dd)
     * @param endDateStr 조회 종료일 (yyyy-MM-dd)
     * @return 거래내역 목록
     */
    public Flux<PointTransaction> getTransactions(String memberId, String startDateStr, String endDateStr) {
        try {
            LocalDateTime startDateTime = parseStartDate(startDateStr);
            LocalDateTime endDateTime = parseEndDate(endDateStr);

            validateDateRange(startDateTime, endDateTime);

            return repository.findByMemberIdAndTransactionTimeBetween(memberId, startDateTime, endDateTime)
                    .doOnComplete(() -> log.info("Retrieved transactions for memberId={} between {} and {}",
                            memberId, startDateTime, endDateTime))
                    .doOnError(error -> log.error("Error retrieving transactions for memberId={}", memberId, error));
        } catch (DateTimeParseException e) {
            log.error("Invalid date format for memberId={}", memberId, e);
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
