package com.caestro.server.domain.push.sender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.caestro.server.global.resilience.ExternalApiGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** FCM 발송기(#206): 요청 모양(토큰·data.type·Bearer)과 응답별 결과. FCM 은 목 서버다. */
class FcmPushSenderTest {

    private static final String SEND = "https://fcm.googleapis.com/v1/projects/peakpic-test/messages:send";

    private MockRestServiceServer server;
    private FcmPushSender sender;
    private CircuitBreakerRegistry registry;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(FcmPushSender.BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        GoogleCredentials credentials = GoogleCredentials.create(new AccessToken("test-access-token", null));
        registry = CircuitBreakerRegistry.ofDefaults();
        sender = new FcmPushSender("peakpic-test", credentials, builder.build(), new ObjectMapper(),
                new ExternalApiGuard(registry));
    }

    @Test
    @DisplayName("보내기: 프로젝트 경로·Bearer·토큰·data.type/code 를 싣고 200 이면 SENT")
    void send_buildsFcmV1Message() {
        server.expect(requestTo(SEND))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-access-token"))
                .andExpect(jsonPath("$.message.token").value("tok-1"))
                .andExpect(jsonPath("$.message.notification.title").value("네컷이 완성됐어요"))
                .andExpect(jsonPath("$.message.data.type").value("fill_completed"))
                .andExpect(jsonPath("$.message.data.code").value("K7X2MQ"))
                .andExpect(jsonPath("$.message.android.priority").value("HIGH"))
                .andRespond(withSuccess("{\"name\":\"projects/peakpic-test/messages/1\"}", MediaType.APPLICATION_JSON));

        assertThat(sender.send("tok-1", PushMessage.fillCompleted("K7X2MQ"))).isEqualTo(PushResult.SENT);
        server.verify();
    }

    @Test
    @DisplayName("없는 기기: 404 UNREGISTERED 와 400 '유효하지 않은 토큰' 둘 다 UNREGISTERED — 토큰을 끄게 한다")
    void deadTokens_areUnregistered() {
        // 기대는 요청 전에 순서대로 미리 건다(MockRestServiceServer 규칙)
        server.expect(requestTo(SEND)).andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"code\":404,\"status\":\"NOT_FOUND\",\"details\":[{\"errorCode\":\"UNREGISTERED\"}]}}"));
        server.expect(requestTo(SEND)).andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"code\":400,\"message\":\"The registration token is not a valid FCM registration token\"}}"));

        assertThat(sender.send("tok-gone", PushMessage.test())).isEqualTo(PushResult.UNREGISTERED);
        assertThat(sender.send("garbage", PushMessage.test())).isEqualTo(PushResult.UNREGISTERED);
        server.verify();
    }

    @Test
    @DisplayName("[재현] 들여쓰기 있는 404 본문(원문 200자 넘고 공백 축약 뒤 192자)도 UNREGISTERED 이고 서킷 실패로 세지 않는다 (#269)")
    void prettyPrintedDeadTokenBody_isStillUnregistered() {
        // FCM 이 실제로 보내는 모양: 줄바꿈·들여쓰기 JSON. 로그용으로 공백을 줄이면 200자보다 짧아진다
        String pretty = """
                {
                  "error": {
                    "code": 404,
                    "status": "NOT_FOUND",
                    "details": [
                      {
                        "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError",
                        "errorCode": "UNREGISTERED"
                      }
                    ]
                  }
                }""";
        assertThat(pretty.length()).isGreaterThan(200);
        assertThat(pretty.replaceAll("\\s+", " ").length()).isLessThan(200);
        for (int i = 0; i < 5; i++) {
            server.expect(requestTo(SEND)).andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body(pretty));
        }

        for (int i = 0; i < 5; i++) {
            assertThat(sender.send("tok-gone-" + i, PushMessage.fillExpiring("K7X2MQ", "제주"))).isEqualTo(PushResult.UNREGISTERED);
        }
        // 4xx 는 서킷 표본에서 빠진다 — 죽은 토큰이 몰려도 서킷이 열리면 안 된다
        assertThat(registry.circuitBreaker(FcmPushSender.CIRCUIT).getMetrics().getNumberOfFailedCalls()).isZero();
        server.verify();
    }

    @Test
    @DisplayName("FCM 장애(5xx)는 FAILED 로 답하고 예외를 밖으로 내지 않는다")
    void serverError_isFailed() {
        server.expect(requestTo(SEND)).andRespond(withServerError());
        assertThat(sender.send("tok-1", PushMessage.test())).isEqualTo(PushResult.FAILED);
    }

    @Test
    @DisplayName("설정이 없으면 FCM 을 부르지 않고 FAILED")
    void unconfigured_isFailed() {
        FcmPushSender unconfigured = new FcmPushSender("", null, RestClient.builder().build(), new ObjectMapper(),
                new ExternalApiGuard(CircuitBreakerRegistry.ofDefaults()));
        assertThat(unconfigured.configured()).isFalse();
        assertThat(unconfigured.send("tok-1", PushMessage.test())).isEqualTo(PushResult.FAILED);
    }
}
