package com.telecom.membership.async.service;

import com.telecom.membership.common.dto.PointRequest;
import com.telecom.membership.common.dto.PointResponse;
import com.telecom.membership.common.service.PointProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 포인트 처리를 위한 서비스 구현체입니다.
 * WebClient를 사용하여 파트너 타입별 포인트 서비스와 비동기적으로 통신합니다.
 *
 * WebClient는 Spring의 리액티브 웹 클라이언트로:
 * - 동기-논블로킹 방식의 HTTP 통신 지원
 * - 선언적인 API로 요청/응답 처리 용이
 * - 효율적인 리소스 사용 (적은 수의 스레드로 많은 요청 처리)
 * - 기본적으로 connection pooling 지원
 */
@Slf4j
@Service
public class PointProcessorImpl implements PointProcessor {

    private final WebClient webClient;

    public PointProcessorImpl(WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * 포인트 적립 요청을 처리합니다.
     * 파트너 타입에 따라 적절한 포인트 서비스로 요청을 라우팅합니다.
     *
     * WebClient 요청 처리 흐름:
     * 1. HTTP 메서드 지정 (post)
     * 2. URI 설정 (.uri)
     * 3. 요청 헤더/바디 구성 (.contentType, .bodyValue)
     * 4. 요청 실행 및 응답 처리 (.retrieve, .bodyToMono)
     * 5. 성공/실패 처리 (.doOnSuccess, .onErrorResume)
     *
     * @param request 포인트 적립 요청 정보
     * @return 처리 결과를 담은 Mono<PointResponse>
     */
    @Override
    public Mono<PointResponse> processPoints(PointRequest request) {
        String partnerType = request.getPartnerType().toLowerCase();
        String url = "http://point-" + partnerType + "/points/accumulate";

        return webClient.post()  // HTTP POST 요청 구성
                .uri(url)        // 대상 URL 설정
                .contentType(MediaType.APPLICATION_JSON)  // Content-Type 헤더 설정
                .bodyValue(request)  // 요청 바디 설정 (자동 직렬화)
                // retrieve(): 응답을 가져오는 메서드
                // - 4xx/5xx 에러를 WebClientResponseException으로 변환
                // - exchange()보다 단순하고 안전한 방식
                .retrieve()
                // bodyToMono(): 응답 바디를 지정된 타입으로 변환
                // - 응답을 PointResponse 클래스로 역직렬화
                // - Mono로 래핑하여 비동기 처리 지원
                .bodyToMono(PointResponse.class)
                // 성공 시 로깅
                .doOnSuccess(response ->
                        log.info("Point accumulation processed successfully for memberId={}",
                                request.getMemberId()))
                // 에러 발생 시 로깅 후 상위로 전파
                .onErrorResume(throwable -> {
                    log.error("Error processing point accumulation for memberId={}",
                            request.getMemberId(), throwable);

                    // 원본 에러를 전파하여 PointHistoryManager에서 실패 상태로 저장하도록 함
                    return Mono.error(throwable);
                });
    }
}