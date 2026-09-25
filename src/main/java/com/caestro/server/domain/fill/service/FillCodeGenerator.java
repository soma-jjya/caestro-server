package com.caestro.server.domain.fill.service;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/** 초대 코드: 읽어 주고 받아 적는 여섯 글자라 0/O/1/I는 뺀다. 링크(/fill/{code})와 앱의 '코드로 참여' 둘 다 이 값을 쓴다. */
@Component
public class FillCodeGenerator {

    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int LENGTH = 6;
    private final SecureRandom random = new SecureRandom();

    public String next() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** 이미지 id: URL에 그대로 들어가는 22자 난수(128비트 이상). 세션 코드와 함께 맞아야 열리므로 추측이 불가능하다. */
    public String imageId() {
        String base = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder(22);
        for (int i = 0; i < 22; i++) {
            sb.append(base.charAt(random.nextInt(base.length())));
        }
        return sb.toString();
    }
}
