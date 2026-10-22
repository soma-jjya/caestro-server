package com.caestro.server.domain.catalog.service;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/** 카탈로그 id: 프레임 "af_", 배너 "hb_", 포즈 업로드 "pu_" 뒤에 [a-z0-9] 10자. 그림 id 는 fill 과 같은 22자 난수(주소에 그대로 들어가 추측 불가). */
@Component
public class CatalogIdGenerator {

    static final String FRAME_PREFIX = "af_";
    static final String BANNER_PREFIX = "hb_";
    static final String POSE_UPLOAD_PREFIX = "pu_";
    private static final String SLUG_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final String IMAGE_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private final SecureRandom random = new SecureRandom();

    public String frameId() {
        return FRAME_PREFIX + random(SLUG_ALPHABET, 10);
    }

    public String bannerId() {
        return BANNER_PREFIX + random(SLUG_ALPHABET, 10);
    }

    public String poseUploadId() {
        return POSE_UPLOAD_PREFIX + random(SLUG_ALPHABET, 10);
    }

    public String imageId() {
        return random(IMAGE_ALPHABET, 22);
    }

    private String random(String alphabet, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
