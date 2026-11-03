package com.caestro.server.domain.inquiry.controller.api;

import com.caestro.server.domain.inquiry.dto.request.CreateInquiryRequest;
import com.caestro.server.domain.inquiry.dto.response.InquiryCreatedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;

@Tag(name = "Inquiry", description = "B2B 문의 접수 (contracts/b2b-inquiries.md). 광고 랜딩의 폼이 인증 없이 보낸다")
public interface InquiryApi {

    @Operation(summary = "문의 접수",
            description = "필수: type, organization, contactName, phone, privacyAgreed=true. utm 은 광고 링크의 utm_* 를 그대로. "
                    + "fax 는 화면에 없는 허니팟 — 값이 있으면 저장하지 않고 201(id 0)을 돌려준다. IP 당 5회/시간, 전체 300회/일을 넘으면 429.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "접수됨 {id}"),
            @ApiResponse(responseCode = "400", description = "필수 누락, 형식 오류, 개인정보 동의 없음"),
            @ApiResponse(responseCode = "429", description = "한도 초과")
    })
    ResponseEntity<InquiryCreatedResponse> submit(CreateInquiryRequest request, HttpServletRequest httpRequest);
}
