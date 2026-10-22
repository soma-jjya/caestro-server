package com.caestro.server.domain.dailylimit.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 하루 한도 하나를 쓴다.
 *
 * @param projectId 같은 것을 한 번만 세는 키(앱의 네컷 id). 다시 보내도 그날은 한 번이다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DailyUsageConsumeRequest(

        @NotBlank(message = "projectId 가 필요합니다")
        @Size(max = 64, message = "projectId 는 64자까지입니다")
        @Pattern(regexp = "[A-Za-z0-9._\\-]+", message = "projectId 는 영문·숫자·._- 만 쓸 수 있습니다")
        String projectId
) {
}
