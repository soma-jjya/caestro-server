package com.caestro.server.domain.inquiry.controller;

import com.caestro.server.domain.inquiry.controller.api.InquiryApi;
import com.caestro.server.domain.inquiry.dto.request.CreateInquiryRequest;
import com.caestro.server.domain.inquiry.dto.response.InquiryCreatedResponse;
import com.caestro.server.domain.inquiry.service.InquiryService;
import com.caestro.server.global.security.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 공개 접수(인증 없음, SecurityConfig permitAll). 관리는 {@link InquiryAdminController}. */
@RestController
@RequestMapping("/inquiries")
@RequiredArgsConstructor
public class InquiryController implements InquiryApi {

    private final InquiryService inquiryService;

    @PostMapping
    @Override
    public ResponseEntity<InquiryCreatedResponse> submit(@Valid @RequestBody CreateInquiryRequest request,
                                                         HttpServletRequest httpRequest) {
        InquiryCreatedResponse created = inquiryService.submit(request, ClientIp.of(httpRequest),
                httpRequest.getHeader(HttpHeaders.USER_AGENT));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
