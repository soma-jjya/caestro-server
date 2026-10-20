package com.caestro.server.domain.fill.service;

import com.caestro.server.domain.fill.dto.FillCustomFrameDto;
import com.caestro.server.domain.fill.dto.FillLayerDto;
import com.caestro.server.domain.fill.dto.request.CreateFillRequest;
import com.caestro.server.domain.fill.dto.request.UpdateFrameRequest;
import com.caestro.server.domain.fill.dto.response.FillResponse;
import com.caestro.server.domain.fill.dto.response.FillSlotResponse;
import com.caestro.server.domain.fill.entity.FillSession;
import com.caestro.server.domain.fill.entity.FillSlot;
import com.caestro.server.domain.fill.repository.FillSessionRepository;
import com.caestro.server.domain.fill.repository.FillSlotRepository;
import com.caestro.server.domain.fill.store.FillImageStore;
import com.caestro.server.domain.fill.store.FillImageTypes;
import com.caestro.server.domain.fill.store.StoredImage;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 함께 채우기(#fill-together). 세션은 프레임과 네 칸의 상태만 들고, 합성은 클라이언트가 한다.
 * 규칙: 칸은 먼저 채운 사람 것(다른 사람이 채운 칸은 409), 비우기는 본인 칸 또는 세션 주인, 없애기는 주인만.
 * 이미지 바이트는 {@link FillImageStore}가 보관하고 서비스는 id만 든다 — 인증 없는 난수 URL로 내려준다 (#152).
 */
@Service
@RequiredArgsConstructor
public class FillService {

    public static final int SLOTS = 4;
    private static final int CODE_ATTEMPTS = 5;
    // 표시 문자열의 컬럼 상한. 넘겨 보내면 DB가 거부해 500이 되므로 잘라서 받는다 (#151)
    private static final int TITLE_MAX = 30;
    private static final int NAME_MAX = 40;
    private static final Set<String> FRAMES = Set.of("Black", "White");
    private static final Set<String> LAYOUTS = Set.of("Strip", "Grid");

    private final FillSessionRepository sessionRepository;
    private final FillSlotRepository slotRepository;
    private final FillImageStore imageStore;
    private final UserRepository userRepository;
    private final FillCodeGenerator codeGenerator;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Value("${fill.ttl-days:7}")
    private long ttlDays;

    @Value("${fill.max-image-bytes:3145728}")
    private int maxImageBytes;

    // 움직이는 GIF는 칸 사진보다 크다(네컷 4~7MB).
    @Value("${fill.max-gif-bytes:16777216}")
    private int maxGifBytes;

    @Transactional
    public FillResponse create(Long ownerId, CreateFillRequest request) {
        if (!FRAMES.contains(request.frame()) || !LAYOUTS.contains(request.layout())) {
            throw new CustomException(ErrorCode.FILL_INVALID_FRAME);
        }
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        LocalDateTime now = LocalDateTime.now(clock);
        FillSession session = sessionRepository.save(FillSession.builder()
                .code(uniqueCode())
                .owner(owner)
                .frame(request.frame())
                .layout(request.layout())
                .artFrameId(request.artFrameId())
                .customFrameJson(request.customFrame() == null ? null : toJson(request.customFrame()))
                .title(normalizeTitle(request.title()))
                .borderless(resolveBorderless(request.borderless(), request.customFrame()))
                .expiresAt(now.plusDays(ttlDays))
                .build());
        List<FillSlot> slots = new ArrayList<>(SLOTS);
        for (int i = 0; i < SLOTS; i++) {
            slots.add(slotRepository.save(FillSlot.builder().session(session).slotIndex(i).build()));
        }
        return toResponse(session, slots, ownerId);
    }

    @Transactional(readOnly = true)
    public FillResponse get(String code, Long userId) {
        FillSession session = liveSession(code);
        return toResponse(session, slotRepository.findBySessionIdOrderBySlotIndex(session.getId()), userId);
    }

    /**
     * 칸 [index]에 사진을 넣는다. 비어 있거나 본인이 채운 칸만 가능하고, 다른 사람의 칸이면 409로 돌려보내
     * 클라이언트가 최신 상태를 다시 그리게 한다. 이전 사진은 지운다(교체).
     * 같은 칸에 동시 요청이 오면 칸 행을 잠가 한 명만 통과시킨다 — 패자는 사진을 저장하기 전에 409로 끝난다 (#151).
     */
    @Transactional
    public FillResponse fillSlot(String code, int index, Long userId, String name, byte[] body, String contentType) {
        FillSession session = liveSession(code);
        // 행을 잠그고 읽으므로 이 검사와 아래 기록 사이에 다른 요청이 끼어들 수 없다 (#151)
        FillSlot slot = slotOf(session, index);
        if (!slot.isEmpty() && !slot.isFilledBy(userId)) {
            throw new CustomException(ErrorCode.FILL_SLOT_TAKEN);
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        String imageId = storeImage(session, body, contentType, Set.of("image/jpeg", "image/png"), maxImageBytes);
        String previousId = slot.getImageId();
        // 닉네임도 컬럼(40자)보다 길 수 있어 같이 자른다
        String shownName = normalizeText(name, NAME_MAX);
        if (shownName == null) {
            shownName = normalizeText(user.getNickname(), NAME_MAX);
        }
        slot.fill(user, shownName, imageId, LocalDateTime.now(clock));
        imageStore.delete(session, previousId);
        dropGif(session);
        return toResponse(session, slotRepository.findBySessionIdOrderBySlotIndex(session.getId()), userId);
    }

    /**
     * 주인이 프레임을 바꾼다: 바탕색·배치·그림 프레임·커스텀 프레임·무테를 요청대로 갈아 끼운다.
     * 이전 커스텀 프레임의 요소 이미지는 지우고, 새 커스텀 프레임의 요소는 url 없이 저장돼 frame-assets 업로드를 기다린다.
     * 칸의 사진은 그대로다.
     */
    @Transactional
    public FillResponse updateFrame(String code, Long userId, UpdateFrameRequest request) {
        FillSession session = liveSession(code);
        if (!session.isOwnedBy(userId)) {
            throw new CustomException(ErrorCode.FILL_ACCESS_DENIED);
        }
        dropGif(session);
        if (!FRAMES.contains(request.frame()) || !LAYOUTS.contains(request.layout())) {
            throw new CustomException(ErrorCode.FILL_INVALID_FRAME);
        }
        FillCustomFrameDto previous = parseCustom(session.getCustomFrameJson());
        if (previous != null && previous.layers() != null) {
            for (FillLayerDto layer : previous.layers()) {
                imageStore.delete(session, imageIdOf(layer.url()));
            }
        }
        boolean borderless = resolveBorderless(request.borderless(), request.customFrame());
        String customJson = null;
        if (request.customFrame() != null) {
            List<FillLayerDto> bare = new ArrayList<>();
            for (FillLayerDto l : request.customFrame().layers() == null ? List.<FillLayerDto>of() : request.customFrame().layers()) {
                bare.add(l.withUrl(null));
            }
            customJson = toJson(new FillCustomFrameDto(request.customFrame().base(), bare, borderless));
        }
        session.updateFrame(request.frame(), request.layout(), request.artFrameId(), customJson, borderless);
        return toResponse(session, slotRepository.findBySessionIdOrderBySlotIndex(session.getId()), userId);
    }

    /** 요청의 무테 값이 있으면 그것, 없으면 커스텀 프레임의 무테, 그것도 없으면 false. */
    static boolean resolveBorderless(Boolean requested, FillCustomFrameDto custom) {
        if (requested != null) return requested;
        return custom != null && Boolean.TRUE.equals(custom.borderless());
    }

    /** 이미지 URL(…/fills/{code}/images/{id})의 마지막 조각. URL이 아니면 null. */
    private static String imageIdOf(String url) {
        if (url == null || url.isBlank()) return null;
        int slash = url.lastIndexOf('/');
        return slash < 0 || slash == url.length() - 1 ? null : url.substring(slash + 1);
    }

    /** 칸을 비운다: 본인이 채운 칸, 또는 세션 주인은 어느 칸이든. */
    @Transactional
    public FillResponse clearSlot(String code, int index, Long userId) {
        FillSession session = liveSession(code);
        FillSlot slot = slotOf(session, index);
        if (!slot.isEmpty() && !slot.isFilledBy(userId) && !session.isOwnedBy(userId)) {
            throw new CustomException(ErrorCode.FILL_ACCESS_DENIED);
        }
        String previousId = slot.getImageId();
        slot.clear();
        imageStore.delete(session, previousId);
        dropGif(session);
        return toResponse(session, slotRepository.findBySessionIdOrderBySlotIndex(session.getId()), userId);
    }

    /** 커스텀 프레임의 k번째 요소 PNG. 주인만 올릴 수 있고, 저장하면 customFrame의 해당 layer.url이 채워진다. */
    @Transactional
    public String uploadFrameAsset(String code, int k, Long userId, byte[] body, String contentType) {
        FillSession session = liveSession(code);
        if (!session.isOwnedBy(userId)) {
            throw new CustomException(ErrorCode.FILL_ACCESS_DENIED);
        }
        FillCustomFrameDto custom = parseCustom(session.getCustomFrameJson());
        if (custom == null || custom.layers() == null || k < 0 || k >= custom.layers().size()) {
            throw new CustomException(ErrorCode.FILL_INVALID_SLOT);
        }
        String imageId = storeImage(session, body, contentType, Set.of("image/png"), maxImageBytes);
        String url = imageStore.urlOf(session.getCode(), imageId);
        List<FillLayerDto> layers = new ArrayList<>(custom.layers());
        layers.set(k, layers.get(k).withUrl(url));
        session.updateCustomFrameJson(toJson(new FillCustomFrameDto(custom.base(), layers, custom.borderless())));
        return url;
    }

    /**
     * 완성된 네컷의 움직이는 GIF(주인만): 앱이 저장된 네컷을 링크로 공유할 때 사진과 함께 올린다.
     * 웹과 다른 앱은 응답의 gifUrl로 받는다. 이전 GIF는 지우고(교체), 칸이나 프레임이 바뀌면 함께 지워진다.
     */
    @Transactional
    public String uploadGif(String code, Long userId, byte[] body, String contentType) {
        FillSession session = liveSession(code);
        if (!session.isOwnedBy(userId)) {
            throw new CustomException(ErrorCode.FILL_ACCESS_DENIED);
        }
        if (!FillImageTypes.isGif(body)) {
            throw new CustomException(ErrorCode.FILL_INVALID_GIF);
        }
        if (body.length > maxGifBytes) {
            throw new CustomException(ErrorCode.FILL_GIF_TOO_LARGE);
        }
        dropGif(session);
        String imageId = storeImage(session, body, "image/gif", Set.of("image/gif"), maxGifBytes);
        session.updateGifImageId(imageId);
        return imageStore.urlOf(session.getCode(), imageId);
    }

    /** 사진이나 프레임이 바뀌면 그 전에 올라온 GIF는 더 맞지 않으므로 지운다. */
    private void dropGif(FillSession session) {
        String id = session.getGifImageId();
        if (id == null) return;
        imageStore.delete(session, id);
        session.updateGifImageId(null);
    }

    /** 세션을 없앤다(주인만): 칸, 사진, 요소가 모두 사라지고 링크는 더 열리지 않는다. */
    @Transactional
    public void delete(String code, Long userId) {
        FillSession session = sessionOf(code);
        if (!session.isOwnedBy(userId)) {
            throw new CustomException(ErrorCode.FILL_ACCESS_DENIED);
        }
        purge(session);
    }

    /** 이미지 바이트(칸 사진·프레임 요소). 세션 코드와 이미지 id가 함께 맞아야 한다. */
    @Transactional(readOnly = true)
    public StoredImage image(String code, String imageId) {
        return imageStore.load(sessionOf(code), imageId);
    }

    /** 저장소가 직접 내줄 수 있으면 그 주소(S3 서명 주소). 비어 있으면 {@link #image}로 바이트를 중계한다 (#152). */
    @Transactional(readOnly = true)
    public Optional<URI> imageRedirect(String code, String imageId) {
        return imageStore.redirectUrl(sessionOf(code), imageId);
    }

    /** 만료 시각이 지난 세션 id 목록(배치용). 삭제는 {@link #purgeSession}으로 세션마다 따로 한다. */
    @Transactional(readOnly = true)
    public List<Long> expiredSessionIds(LocalDateTime now) {
        return sessionRepository.findByExpiresAtBefore(now).stream().map(FillSession::getId).toList();
    }

    /**
     * 세션 하나를 사진까지 통째로 지운다(배치용, 세션당 트랜잭션 하나).
     * 한 묶음으로 지우면 세션 하나의 실패(예: 옛 fill_images 행의 외래키)가 나머지까지 롤백시켰다 — 2026-10-20 운영 배치 실패 (#162).
     * 이미 지워졌으면 조용히 넘어간다.
     */
    @Transactional
    public void purgeSession(Long sessionId) {
        sessionRepository.findById(sessionId).ifPresent(this::purge);
    }

    private void purge(FillSession session) {
        slotRepository.deleteBySessionId(session.getId());
        imageStore.deleteBySession(session.getId());
        sessionRepository.delete(session);
    }

    /**
     * 코드로 세션을 찾는다. 코드 정규화는 여기 한 곳에서만 한다 (#151) —
     * 메서드마다 대문자화를 기억해야 했고 delete·image가 그걸 빠뜨려 소문자 코드에 404를 주고 있었다.
     */
    private FillSession sessionOf(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase();
        return sessionRepository.findByCode(normalized)
                .orElseThrow(() -> new CustomException(ErrorCode.FILL_NOT_FOUND));
    }

    private FillSession liveSession(String code) {
        FillSession session = sessionOf(code);
        if (session.isExpired(LocalDateTime.now(clock))) {
            throw new CustomException(ErrorCode.FILL_EXPIRED);
        }
        return session;
    }

    private FillSlot slotOf(FillSession session, int index) {
        if (index < 0 || index >= SLOTS) {
            throw new CustomException(ErrorCode.FILL_INVALID_SLOT);
        }
        return slotRepository.findBySessionIdAndSlotIndex(session.getId(), index)
                .orElseThrow(() -> new CustomException(ErrorCode.FILL_INVALID_SLOT));
    }

    /** 크기·형식을 검증하고 저장소에 맡긴다. 바이트의 보관 위치는 저장소만 안다 (#152). */
    private String storeImage(FillSession session, byte[] body, String contentType, Set<String> allowed, int maxBytes) {
        String type = FillImageTypes.resolve(body, contentType, allowed);
        if (body.length > maxBytes) {
            throw new CustomException(ErrorCode.FILL_IMAGE_TOO_LARGE);
        }
        return imageStore.store(session, type, body);
    }

    private String uniqueCode() {
        for (int i = 0; i < CODE_ATTEMPTS; i++) {
            String code = codeGenerator.next();
            if (!sessionRepository.existsByCode(code)) {
                return code;
            }
        }
        throw new CustomException(ErrorCode.FILL_CODE_GENERATION_FAILED);
    }

    private FillResponse toResponse(FillSession session, List<FillSlot> slots, Long userId) {
        List<FillSlotResponse> slotResponses = new ArrayList<>(SLOTS);
        for (int i = 0; i < SLOTS; i++) {
            final int index = i;
            FillSlot slot = slots.stream().filter(s -> s.getSlotIndex() == index).findFirst().orElse(null);
            if (slot == null || slot.isEmpty()) {
                slotResponses.add(new FillSlotResponse(index, null, false, null, null));
            } else {
                slotResponses.add(new FillSlotResponse(
                        index,
                        imageStore.urlOf(session.getCode(), slot.getImageId()),
                        slot.isFilledBy(userId),
                        slot.getFilledByName(),
                        toInstant(slot.getFilledAt())));
            }
        }
        return new FillResponse(
                session.getCode(),
                session.isOwnedBy(userId),
                session.getFrame(),
                session.getLayout(),
                session.getArtFrameId(),
                parseCustom(session.getCustomFrameJson()),
                session.getTitle(),
                session.isBorderless(),
                slotResponses,
                session.getGifImageId() == null ? null : imageStore.urlOf(session.getCode(), session.getGifImageId()),
                toInstant(session.getExpiresAt()),
                toInstant(session.getCreatedAt()));
    }

    /** 앞뒤 공백을 지우고 연속 공백을 하나로, maxLength까지 자른다. 비면 null. */
    static String normalizeText(String raw, int maxLength) {
        if (raw == null) return null;
        String t = raw.trim().replaceAll("\\s+", " ");
        if (t.length() > maxLength) t = t.substring(0, maxLength);
        return t.isEmpty() ? null : t;
    }

    static String normalizeTitle(String raw) {
        return normalizeText(raw, TITLE_MAX);
    }

    private Instant toInstant(LocalDateTime t) {
        return t == null ? null : t.atZone(clock.getZone()).toInstant();
    }

    private String toJson(FillCustomFrameDto dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.FILL_INVALID_FRAME);
        }
    }

    private FillCustomFrameDto parseCustom(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, FillCustomFrameDto.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
