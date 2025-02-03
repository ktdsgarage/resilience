package com.telecom.membership.async.service;

import com.telecom.membership.common.dto.PointRequest;
import com.telecom.membership.common.dto.PointResponse;
import com.telecom.membership.common.service.PointProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class PointProcessorImpl implements PointProcessor {

    private final WebClient webClient;

    @Value("${services.point.url}")
    private String pointServiceUrl;

    public PointProcessorImpl(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Mono<PointResponse> processPoints(PointRequest request) {
        return webClient.post()
                .uri(pointServiceUrl + "/api/points/accumulate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(PointResponse.class)
                .doOnSuccess(response ->
                        log.info("Point accumulation processed successfully for memberId={}",
                                request.getMemberId()))
                .onErrorResume(throwable -> {
                    log.error("Error processing point accumulation for memberId={}",
                            request.getMemberId(), throwable);

                    // 원본 에러를 전파하여 PointHistoryManager에서 실패 상태로 저장하도록 함
                    return Mono.error(throwable);
                });
    }
}