package com.caestro.server.domain.fill.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FillCodeGeneratorTest {

    private final FillCodeGenerator generator = new FillCodeGenerator();

    @Test
    @DisplayName("초대 코드는 6자리이고 혼동 글자(0/O/1/I)를 쓰지 않는다")
    void codeIsSixUnambiguousCharacters() {
        for (int i = 0; i < 500; i++) {
            String code = generator.next();
            assertThat(code).hasSize(6).matches("[A-HJ-NP-Z2-9]{6}");
        }
    }

    @Test
    @DisplayName("이미지 id는 22자 영숫자라 URL에 그대로 들어가고 추측이 불가능하다")
    void imageIdIsUrlSafeAndLong() {
        String id = generator.imageId();
        assertThat(id).hasSize(22).matches("[A-Za-z0-9]{22}");
        assertThat(generator.imageId()).isNotEqualTo(id);
    }
}
