package com.caestro.server.domain.b2b.service;

import com.caestro.server.domain.b2b.dto.request.DuplicateB2bProjectRequest;
import com.caestro.server.domain.b2b.dto.request.ReplaceB2bFramesRequest;
import com.caestro.server.domain.b2b.dto.request.UpsertB2bProjectRequest;
import com.caestro.server.domain.b2b.dto.response.*;
import com.caestro.server.domain.b2b.entity.B2bFrame;
import com.caestro.server.domain.b2b.entity.B2bImage;
import com.caestro.server.domain.b2b.entity.B2bProject;
import com.caestro.server.domain.b2b.entity.B2bProject.Status;
import com.caestro.server.domain.b2b.repository.B2bFrameRepository;
import com.caestro.server.domain.b2b.repository.B2bProjectRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** B2B 프로젝트 관리. 모든 수정은 프로젝트 행 잠금 안에서 검증·교체하며 B2C 카탈로그에는 쓰지 않는다. */
@Service
@RequiredArgsConstructor
public class B2bProjectService {
    private final B2bProjectRepository projects;
    private final B2bFrameRepository frames;
    private final B2bImageService images;
    private final B2bFrameMapper mapper;
    private final Clock clock;

    /** 최근 수정 순으로 관리자 목록을 반환한다. */
    @Transactional(readOnly = true)
    public List<B2bProjectResponse> list() {
        return projects.findAllByOrderByUpdatedAtDescIdAsc().stream().map(this::response).toList();
    }

    /** id의 프로젝트와 현재 프레임을 반환한다. 없으면 404. */
    @Transactional(readOnly = true)
    public B2bProjectResponse get(String id) { return response(project(id)); }

    /** LIVE 프로젝트만 비인증 선택 화면에 공개한다. 초안·중지는 404. */
    @Transactional(readOnly = true)
    public B2bPublicProjectResponse publicProject(String slug) {
        B2bProject project = projects.findBySlug(slug)
                .filter(p -> p.getStatus() == Status.LIVE)
                .orElseThrow(() -> new CustomException(ErrorCode.B2B_PROJECT_NOT_FOUND));
        return B2bPublicProjectResponse.from(project, frameResponses(project));
    }

    /** 형식·예약어·중복 순서로 slug를 검사한다. exclude는 현재 프로젝트 id다. */
    @Transactional(readOnly = true)
    public B2bSlugCheckResponse slugCheck(String slug, String exclude) {
        String reason = B2bPolicy.slugProblem(slug);
        if (reason == null && (exclude == null ? projects.existsBySlug(slug) : projects.existsBySlugAndIdNot(slug, exclude))) {
            reason = "duplicate";
        }
        return new B2bSlugCheckResponse(reason == null, reason);
    }

    /** 검증한 기본 정보로 빈 DRAFT를 만든다. 같은 slug의 동시 생성도 DB 유일 제약으로 409 처리한다. */
    @Transactional
    public B2bProjectResponse create(UpsertB2bProjectRequest request) {
        B2bProject project = newProject(request.name(), request.slug(), request.description());
        persistUniqueSlug(project);
        return response(project);
    }

    /** 기본 정보를 통째로 갱신한다. 최초 공개 후 slug 변경은 중지 상태에서도 409. */
    @Transactional
    public B2bProjectResponse update(String id, UpsertB2bProjectRequest request) {
        B2bProject project = locked(id);
        if (project.getPublishedAt() != null && !project.getSlug().equals(request.slug())) {
            throw new CustomException(ErrorCode.B2B_SLUG_LOCKED);
        }
        requireSlug(request.slug(), id);
        project.update(B2bPolicy.text(request.name(), 60, true, ErrorCode.B2B_INVALID_PROJECT), request.slug(),
                B2bPolicy.text(request.description(), 120, false, ErrorCode.B2B_INVALID_PROJECT), now());
        persistUniqueSlug(project);
        return response(project);
    }

    /**
     * 배열 전체를 검증한 뒤 현재 프레임을 교체한다. 배열 순서가 displayOrder다.
     * @throws CustomException B2B_INVALID_FRAME - 중복/다른 프로젝트 id, 잘못된 설정
     * @throws CustomException B2B_NOT_PUBLISHABLE - LIVE 상태에서 빈 목록이나 미완성 프레임을 저장하려는 경우
     */
    @Transactional
    public B2bProjectResponse replaceFrames(String id, ReplaceB2bFramesRequest request) {
        B2bProject project = locked(id);
        List<B2bFrame> existing = frames.findByProjectIdOrderByDisplayOrderAsc(id);
        Map<String, B2bFrame> byId = existing.stream().collect(Collectors.toMap(B2bFrame::getId, f -> f));
        List<ReplaceB2bFramesRequest.Frame> inputs = request.frames();
        if (inputs == null || inputs.size() > 3) throw new CustomException(ErrorCode.B2B_INVALID_FRAME);

        // 1. 다른 프로젝트 id와 중복 id는 저장 전에 거부한다.
        Set<String> kept = new HashSet<>();
        for (var input : inputs) {
            if (input == null || input.layout() == null) throw new CustomException(ErrorCode.B2B_INVALID_FRAME);
            if (input.id() != null && (!byId.containsKey(input.id()) || !kept.add(input.id()))) {
                throw new CustomException(ErrorCode.B2B_INVALID_FRAME);
            }
            B2bPolicy.text(input.name(), 30, true, ErrorCode.B2B_INVALID_FRAME);
            B2bPolicy.windows(input.windows());
        }

        // 2. 이미지 사본과 설정을 같은 트랜잭션에 저장한다. 뒤 항목이 실패해도 전부 롤백된다.
        List<B2bFrame> replacement = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            var input = inputs.get(i);
            B2bFrame old = input.id() == null ? null : byId.get(input.id());
            B2bImage image = images.retain(input.imageId(), old == null ? null : old.getImage());
            String name = input.name().strip();
            String windows = mapper.json(B2bPolicy.windows(input.windows()));
            B2bFrame frame;
            if (old == null) {
                frame = B2bFrame.builder().id(B2bPolicy.id("bf_")).project(project).name(name).layout(input.layout())
                        .image(image).windowsJson(windows).displayOrder(i).version(1).createdAt(now()).build();
            } else {
                old.update(name, input.layout(), image, windows, i);
                frame = old;
            }
            replacement.add(frame);
        }
        if (project.getStatus() == Status.LIVE) requirePublishable(replacement);

        // 3. 선택 목록만 교체한다. 이전 세션 스냅샷과 그 이미지에는 손대지 않는다.
        frames.deleteAll(existing.stream().filter(f -> !kept.contains(f.getId())).toList());
        frames.saveAll(replacement);
        project.touch(now());
        return B2bProjectResponse.from(project, replacement.stream().map(mapper::response).toList(), clock.getZone());
    }

    /** DRAFT → LIVE. 프레임은 1~3개이며 모두 유효한 이미지·창 4개를 가져야 한다. */
    @Transactional
    public B2bProjectResponse publish(String id) { return transition(id, Status.DRAFT, Status.LIVE); }

    /** LIVE → PAUSED. 기존 세션은 기존 만료 시각까지 계속 쓸 수 있다. */
    @Transactional
    public B2bProjectResponse pause(String id) { return transition(id, Status.LIVE, Status.PAUSED); }

    /** PAUSED → LIVE. 공개 조건을 다시 검사하고 최초 publishedAt은 유지한다. */
    @Transactional
    public B2bProjectResponse resume(String id) { return transition(id, Status.PAUSED, Status.LIVE); }

    /** 프레임 설정과 불변 이미지 사본만 공유하는 새 DRAFT. 세션·사진은 복사하지 않는다. */
    @Transactional
    public B2bProjectResponse duplicate(String id, DuplicateB2bProjectRequest request) {
        B2bProject source = locked(id);
        B2bProject copy = newProject(request.name(), request.slug(), source.getDescription());
        persistUniqueSlug(copy);
        List<B2bFrame> copied = frames.findByProjectIdOrderByDisplayOrderAsc(id).stream().map(f ->
                B2bFrame.builder().id(B2bPolicy.id("bf_")).project(copy).name(f.getName()).layout(f.getLayout())
                        .image(f.getImage()).windowsJson(f.getWindowsJson()).displayOrder(f.getDisplayOrder())
                        .version(1).createdAt(now()).build()).toList();
        frames.saveAll(copied);
        return B2bProjectResponse.from(copy, copied.stream().map(mapper::response).toList(), clock.getZone());
    }

    private B2bProjectResponse transition(String id, Status expected, Status next) {
        B2bProject project = locked(id);
        if (project.getStatus() != expected) throw new CustomException(ErrorCode.B2B_INVALID_TRANSITION);
        if (next == Status.LIVE) requirePublishable(frames.findByProjectIdOrderByDisplayOrderAsc(id));
        project.transition(next, now());
        return response(project);
    }

    private void requirePublishable(List<B2bFrame> choices) {
        if (choices.isEmpty() || choices.size() > 3) throw new CustomException(ErrorCode.B2B_NOT_PUBLISHABLE);
        for (B2bFrame frame : choices) {
            if (frame.getImage() == null || B2bPolicy.windows(mapper.windows(frame.getWindowsJson())).size() != 4) {
                throw new CustomException(ErrorCode.B2B_NOT_PUBLISHABLE);
            }
        }
    }

    private B2bProject newProject(String name, String slug, String description) {
        requireSlug(slug, null);
        return B2bProject.builder().id(B2bPolicy.id("bp_")).slug(slug)
                .name(B2bPolicy.text(name, 60, true, ErrorCode.B2B_INVALID_PROJECT))
                .description(B2bPolicy.text(description, 120, false, ErrorCode.B2B_INVALID_PROJECT))
                .status(Status.DRAFT).createdAt(now()).updatedAt(now()).build();
    }

    private void requireSlug(String slug, String exclude) {
        B2bSlugCheckResponse result = slugCheck(slug, exclude);
        if (!result.available()) throw new CustomException("duplicate".equals(result.reason())
                ? ErrorCode.B2B_SLUG_DUPLICATE : ErrorCode.B2B_INVALID_SLUG);
    }

    private void persistUniqueSlug(B2bProject project) {
        try {
            projects.saveAndFlush(project);
        } catch (DataIntegrityViolationException e) {
            // 필드 검증 후 이 행에서 충돌할 수 있는 사용자 지정 값은 유일한 slug다.
            throw new CustomException(ErrorCode.B2B_SLUG_DUPLICATE);
        }
    }

    private B2bProject project(String id) {
        return projects.findById(id).orElseThrow(() -> new CustomException(ErrorCode.B2B_PROJECT_NOT_FOUND));
    }

    private B2bProject locked(String id) {
        return projects.findLockedById(id).orElseThrow(() -> new CustomException(ErrorCode.B2B_PROJECT_NOT_FOUND));
    }

    private List<B2bFrameResponse> frameResponses(B2bProject project) {
        return frames.findByProjectIdOrderByDisplayOrderAsc(project.getId()).stream().map(mapper::response).toList();
    }

    private B2bProjectResponse response(B2bProject project) {
        return B2bProjectResponse.from(project, frameResponses(project), clock.getZone());
    }

    private LocalDateTime now() { return LocalDateTime.now(clock); }
}
