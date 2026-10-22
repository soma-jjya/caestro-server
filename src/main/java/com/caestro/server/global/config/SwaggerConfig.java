package com.caestro.server.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    public static final String BEARER_AUTH = "BearerAuth";
    public static final String STUDIO_KEY = "StudioKey";

    @Bean
    public OpenAPI openAPI() {
        Info info = new Info()
                .title("Caestro API")
                .version("1.0.0");

        SecurityScheme bearerAuthScheme = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT");

        return new OpenAPI()
                .info(info)
                .components(new Components()
                        .addSecuritySchemes(BEARER_AUTH, bearerAuthScheme)
                        // 디자이너 스튜디오 정적 키(헤더). catalog.studio-key
                        .addSecuritySchemes(STUDIO_KEY, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Studio-Key")));
    }
}
