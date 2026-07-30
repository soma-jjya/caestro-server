package com.caestro.server.domain.auth.oauth;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GoogleAuthConfig {

    /**
     * 구글 ID token 로컬 검증기.
     * 구글 공개키(JWKS)를 캐시해 서명·issuer를 검증하고, 허용 audience(우리 client id)에 대해서만 통과시킨다.
     * 웹/Android/iOS가 서로 다른 client id를 audience로 쓸 수 있어 목록으로 허용한다.
     *
     * @param clientId         구글 client id (기본 audience)
     * @param allowedAudiences 추가 허용 audience 목록(콤마 구분, 없으면 clientId만 허용)
     */
    @Bean
    public GoogleIdTokenVerifier googleIdTokenVerifier(
            @Value("${google.client-id}") String clientId,
            @Value("${google.allowed-audiences:}") String allowedAudiences) {
        List<String> audiences = Arrays.stream(allowedAudiences.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (audiences.isEmpty()) {
            audiences = List.of(clientId);
        }
        return new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(audiences)
                .build();
    }
}
