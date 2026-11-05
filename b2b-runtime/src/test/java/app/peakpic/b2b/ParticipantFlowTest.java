package app.peakpic.b2b;

import app.peakpic.b2b.auth.RefreshTokens;
import com.caestro.server.global.ratelimit.RedisRateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ParticipantFlowTest {
    private static final String CONTEXT = "/projects/yuja";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired RuntimeProperties properties;
    @Autowired com.caestro.server.domain.user.repository.UserRepository users;
    @MockitoBean RedisRateLimiter limiter;
    @MockitoBean RefreshTokens refresh;
    @MockitoBean LockProvider lockProvider;
    @BeforeEach void setup() {
        when(limiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(true);
        when(refresh.issue(anyLong())).thenReturn("test-refresh-token");
    }
    @Test void operatorAndParticipantCompleteARealDatabaseJourney() throws Exception {
        mvc.perform(admin(post(CONTEXT + "/admin/project/publish"))).andExpect(status().isConflict());
        byte[] png = png();
        JsonNode image = json(mvc.perform(admin(post(CONTEXT + "/admin/images")).contentType("image/png").content(png))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(get(image.get("imageUrl").asText().replace("http://localhost:8080", "")).contextPath(CONTEXT))
                .andExpect(status().isOk()).andExpect(content().bytes(png));
        String frames = """
                {"frames":[{"name":"유자","layout":"STRIP","imageId":"%s","windows":[
                {"x":0,"y":0,"w":1,"h":0.25,"rot":0},
                {"x":0,"y":0.25,"w":1,"h":0.25,"rot":0},
                {"x":0,"y":0.5,"w":1,"h":0.25,"rot":0},
                {"x":0,"y":0.75,"w":1,"h":0.25,"rot":0}]}]}
                """.formatted(image.get("id").asText());
        mvc.perform(admin(put(CONTEXT + "/admin/project/frames")).contentType("application/json").content(frames))
                .andExpect(status().isOk());
        mvc.perform(admin(post(CONTEXT + "/admin/project/publish"))).andExpect(status().isOk());
        JsonNode project = json(mvc.perform(get(CONTEXT + "/").contextPath(CONTEXT)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(project.get("frames").size()).isEqualTo(1);
        JsonNode guest = json(mvc.perform(post(CONTEXT + "/auth/guest").contextPath(CONTEXT))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String bearer = "Bearer " + guest.get("accessToken").asText();
        JsonNode frame = project.get("frames").get(0);
        String selection = "{\"frameId\":\"%s\",\"frameVersion\":%s}".formatted(frame.get("id").asText(), frame.get("version"));
        JsonNode created = json(mvc.perform(post(CONTEXT + "/fills").contextPath(CONTEXT).header("Authorization", bearer)
                .contentType("application/json").content(selection)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        String code = created.get("fill").get("code").asText();
        assertThat(created.get("shareUrl").asText()).isEqualTo("https://peakpic.app/go/yuja/" + code);
        assertThat(created.get("fill").get("expiresAt").asText()).isEqualTo(properties.downloadsUntil().toString());
        for (int slot = 0; slot < 4; slot++) {
            mvc.perform(put(CONTEXT + "/fills/" + code + "/slots/" + slot).contextPath(CONTEXT)
                    .header("Authorization", bearer).contentType("image/png").content(png)).andExpect(status().isOk());
        }
        JsonNode complete = json(mvc.perform(get(CONTEXT + "/fills/" + code).contextPath(CONTEXT).header("Authorization", bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for (var slot : complete.get("fill").get("slots")) {
            assertThat(slot.get("imageUrl").asText()).startsWith("http://localhost:8080/projects/yuja/fills/");
            mvc.perform(get(slot.get("imageUrl").asText().replace("http://localhost:8080", "")).contextPath(CONTEXT))
                    .andExpect(status().isOk()).andExpect(content().bytes(png));
        }
        mvc.perform(admin(put(CONTEXT + "/admin/project/frames")).contentType("application/json").content("{\"frames\":[]}"))
                .andExpect(status().isConflict());
        mvc.perform(admin(post(CONTEXT + "/admin/project/pause"))).andExpect(status().isOk());
        mvc.perform(get(CONTEXT + "/fills/" + code).contextPath(CONTEXT).header("Authorization", bearer)).andExpect(status().isOk());
        mvc.perform(post(CONTEXT + "/fills").contextPath(CONTEXT).header("Authorization", bearer)
                .contentType("application/json").content(selection)).andExpect(status().isNotFound());
    }
    @Test void missingKeysTokensAndB2cRoutesCannotBypassSecurity() throws Exception {
        mvc.perform(get(CONTEXT + "/admin/project").contextPath(CONTEXT)).andExpect(status().isForbidden());
        mvc.perform(get(CONTEXT + "/fills/ABC123").contextPath(CONTEXT)).andExpect(status().isUnauthorized());
        mvc.perform(post(CONTEXT + "/b2b/admin/projects").contextPath(CONTEXT)).andExpect(status().isUnauthorized());
        mvc.perform(post(CONTEXT + "/auth/kakao").contextPath(CONTEXT)).andExpect(status().isUnauthorized());
        mvc.perform(post(CONTEXT + "/auth/guest").contextPath(CONTEXT).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("status").value(401));
    }
    @Test void largeBodyAndRateLimitUseTheExistingErrorEnvelope() throws Exception {
        mvc.perform(post(CONTEXT + "/auth/guest").contextPath(CONTEXT).content(new byte[65537]))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("status").value(413));
        when(limiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(false);
        long before = users.count();
        mvc.perform(post(CONTEXT + "/auth/guest").contextPath(CONTEXT)).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(header().string("X-RateLimit-Scope", "guest-ip"))
                .andExpect(jsonPath("message").value("요청이 너무 잦습니다. 60초 뒤에 다시 시도해 주세요."));
        assertThat(users.count()).isEqualTo(before);
    }
    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return request.contextPath(CONTEXT).header("X-B2B-Admin-Key", properties.adminKey());
    }
    private JsonNode json(String text) throws Exception { return mapper.readTree(text); }
    private static byte[] png() throws Exception {
        var out = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(32, 128, BufferedImage.TYPE_INT_ARGB), "png", out); return out.toByteArray();
    }
}
