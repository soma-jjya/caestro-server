package com.caestro.server.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caestro.server.global.config.SecurityConfig;
import com.caestro.server.global.exception.error.ErrorCode;
import com.caestro.server.global.jwt.JwtProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 요청 쪽 원인의 표준 예외가 500이 아닌 본래 상태 코드로 응답하는지 검증한다 (#137).
 * 2026-09-29 운영에서 POST /auth/callback 24건이 500으로 응답해 5xx 알람이 울린 결함의 재현.
 */
@WebMvcTest(controllers = GlobalExceptionHandlerWebTest.ProbeController.class)
@Import({SecurityConfig.class, GlobalExceptionHandlerWebTest.ProbeController.class})
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private RedisTemplate<String, String> redisTemplate;

    // 메인 클래스의 @EnableJpaAuditing이 웹 슬라이스에서 요구하는 빈
    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    @DisplayName("인증 예외 구간의 없는 경로는 404를 반환한다 (운영 알람 재현: POST /auth/callback)")
    void 인증_예외_구간의_없는_경로는_404() throws Exception {
        mockMvc.perform(post("/auth/callback"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("인증된 사용자가 없는 경로를 호출해도 404를 반환한다")
    void 인증된_사용자의_없는_경로는_404() throws Exception {
        mockMvc.perform(post("/frames").with(user("1")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("있는 경로를 지원하지 않는 메서드로 호출하면 405를 반환한다")
    void 미지원_메서드는_405() throws Exception {
        mockMvc.perform(put("/auth/probe"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    @DisplayName("업로드 크기 초과는 413을 반환한다")
    void 업로드_크기_초과는_413() throws Exception {
        mockMvc.perform(post("/auth/probe/upload"))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.status").value(413));
    }

    @Test
    @DisplayName("읽을 수 없는 본문은 400을 반환한다")
    void 읽을_수_없는_본문은_400() throws Exception {
        mockMvc.perform(post("/auth/probe/valid").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("지원하지 않는 미디어 타입은 415를 반환한다")
    void 미지원_미디어_타입은_415() throws Exception {
        mockMvc.perform(post("/auth/probe/valid").contentType(MediaType.TEXT_PLAIN).content("name"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415));
    }

    @Test
    @DisplayName("실제 서버 오류는 500이며 로그에 메서드와 경로가 남는다")
    void 서버_오류는_500이고_경로가_로그에_남는다(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/auth/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value(ErrorCode.INTERNAL_SERVER_ERROR.getMessage()));

        assertThat(output.getAll()).contains("GET /auth/probe/boom");
    }

    @Test
    @DisplayName("[회귀] Bean Validation 실패는 필드 메시지와 함께 400을 반환한다")
    void 검증_실패는_필드_메시지와_400() throws Exception {
        mockMvc.perform(post("/auth/probe/valid").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("이름은 필수입니다"));
    }

    @Test
    @DisplayName("[회귀] CustomException은 ErrorCode의 상태와 메시지로 응답한다")
    void 커스텀_예외는_에러코드대로_응답() throws Exception {
        mockMvc.perform(get("/auth/probe/custom"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(ErrorCode.USER_NOT_FOUND.getMessage()));
    }

    // 예외를 일부러 일으키는 테스트 전용 컨트롤러 (permitAll 구간인 /auth 아래에 둔다)
    @RestController
    static class ProbeController {

        record Body(@NotBlank(message = "이름은 필수입니다") String name) {
        }

        @GetMapping("/auth/probe")
        String probe() {
            return "ok";
        }

        @GetMapping("/auth/probe/boom")
        String boom() {
            throw new IllegalStateException("boom");
        }

        @GetMapping("/auth/probe/custom")
        String custom() {
            throw new CustomException(ErrorCode.USER_NOT_FOUND);
        }

        @PostMapping("/auth/probe/upload")
        String upload() {
            throw new MaxUploadSizeExceededException(1024);
        }

        @PostMapping("/auth/probe/valid")
        String valid(@Valid @RequestBody Body body) {
            return body.name();
        }
    }
}
