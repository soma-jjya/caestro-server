package com.caestro.server.domain.deveval.controller;

import com.caestro.server.domain.deveval.controller.api.DevEvaluationAdminApi;
import com.caestro.server.domain.deveval.dto.response.DevEvaluationPageResponse;
import com.caestro.server.domain.deveval.dto.response.DevEvaluationStatsResponse;
import com.caestro.server.domain.deveval.entity.DevEvaluationGuide;
import com.caestro.server.domain.deveval.entity.DevEvaluationPhoto;
import com.caestro.server.domain.deveval.service.DevEvaluationService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 개발자 평가 열람 (#260, contracts/dev-evaluations.md). /admin/dev/** 은 SecurityConfig 가 ROLE_STUDIO(X-Studio-Key)·ADMIN 으로 막고,
 * OpsAccessLogFilter 가 누가 무엇을 봤는지 한 줄씩 남긴다. 사진은 여기로만 나간다(공개 주소 없음).
 */
@RestController
@RequestMapping("/admin/dev")
@RequiredArgsConstructor
public class DevEvaluationAdminController implements DevEvaluationAdminApi {

    private static final MediaType CSV = MediaType.parseMediaType("text/csv;charset=UTF-8");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final DevEvaluationService evaluationService;
    private final Clock clock;

    @GetMapping("/evaluations")
    @Override
    public ResponseEntity<DevEvaluationPageResponse> list(@RequestParam(required = false) Integer page,
                                                          @RequestParam(required = false) Integer size,
                                                          @RequestParam(required = false) Boolean guide,
                                                          @RequestParam(required = false) Long userId) {
        return ResponseEntity.ok(evaluationService.page(page, size, guide, userId));
    }

    @GetMapping("/evaluations/stats")
    @Override
    public ResponseEntity<DevEvaluationStatsResponse> stats() {
        return ResponseEntity.ok(evaluationService.stats());
    }

    // 얼굴이 담긴 사진: 브라우저에만 잠깐(private, 5분) 두고 공용 캐시는 거치지 않는다
    @GetMapping("/evaluations/{id}/photo")
    @Override
    public ResponseEntity<byte[]> photo(@PathVariable String id) {
        DevEvaluationPhoto photo = evaluationService.photo(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.getContentType()))
                .cacheControl(CacheControl.maxAge(300, TimeUnit.SECONDS).cachePrivate())
                .body(photo.getData());
    }

    // 직접 올린 사진 가이드(사람이 담긴 사진): 평가 사진과 같은 캐시
    @GetMapping("/evaluations/{id}/guide/{kind}")
    @Override
    public ResponseEntity<byte[]> guide(@PathVariable String id, @PathVariable String kind) {
        DevEvaluationGuide guide = evaluationService.guide(id, kind);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(guide.getContentType()))
                .cacheControl(CacheControl.maxAge(300, TimeUnit.SECONDS).cachePrivate())
                .body(guide.getData());
    }

    @GetMapping("/evaluations.csv")
    @Override
    public ResponseEntity<byte[]> csv(@RequestParam(required = false) Boolean guide,
                                      @RequestParam(required = false) Long userId) {
        String date = LocalDate.now(clock.withZone(SEOUL)).format(DateTimeFormatter.BASIC_ISO_DATE);
        return ResponseEntity.ok()
                .contentType(CSV)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("peakpic-dev-evaluations-" + date + ".csv").build().toString())
                .body(evaluationService.csv(guide, userId));
    }

    @DeleteMapping("/evaluations/{id}")
    @Override
    public ResponseEntity<Void> delete(@PathVariable String id) {
        evaluationService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
