package com.caestro.server.domain.fill.controller.api;

import com.caestro.server.domain.fill.dto.request.CreateFillRequest;
import com.caestro.server.domain.fill.dto.response.AssetUrlResponse;
import com.caestro.server.domain.fill.dto.response.FillResponse;
import com.caestro.server.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Fill", description = "함께 채우기 API — 링크로 공유한 네컷 프레임을 서로 다른 폰(또는 웹)에서 각자 채운다")
public interface FillApi {

    @Operation(summary = "함께 채우기 세션 생성",
            description = "프레임(Black/White), 배치(Strip/Grid), 선택한 그림 프레임 id, 커스텀 프레임 배치를 받아 6자리 코드의 세션을 만듭니다. "
                    + "링크는 https://peakpic.app/fill/{code} 입니다. 7일 뒤 만료됩니다.")
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공",
                    content = @Content(schema = @Schema(implementation = FillResponse.class))),
            @ApiResponse(responseCode = "400", description = "frame/layout 값이 유효하지 않음"),
            @ApiResponse(responseCode = "401", description = "토큰이 없음")
    })
    ResponseEntity<FillResponse> create(CreateFillRequest request, CustomUserDetails userDetails);

    @Operation(summary = "세션 조회", description = "칸마다 사진 URL과 누가 채웠는지(filledByMe/filledByName)를 내려줍니다. 클라이언트는 화면이 열려 있는 동안 주기적으로 호출합니다.")
    @SecurityRequirement(name = "BearerAuth")
    @Parameter(name = "code", description = "세션 코드(6자리)", in = ParameterIn.PATH, required = true)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = FillResponse.class))),
            @ApiResponse(responseCode = "401", description = "토큰이 없음"),
            @ApiResponse(responseCode = "404", description = "세션 없음"),
            @ApiResponse(responseCode = "410", description = "세션 만료")
    })
    ResponseEntity<FillResponse> get(String code, CustomUserDetails userDetails);

    @Operation(summary = "칸 채우기",
            description = "본문은 이미지 바이트(image/jpeg 또는 image/png, 최대 3MB). 비어 있거나 본인이 채운 칸만 가능하며, "
                    + "다른 사람이 채운 칸이면 409와 함께 최신 상태를 돌려줍니다. name은 웹 참여자의 표시 이름(선택).")
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "채움",
                    content = @Content(schema = @Schema(implementation = FillResponse.class))),
            @ApiResponse(responseCode = "400", description = "이미지가 아니거나 칸 번호가 잘못됨"),
            @ApiResponse(responseCode = "409", description = "다른 사람이 먼저 채운 칸"),
            @ApiResponse(responseCode = "413", description = "이미지가 너무 큼")
    })
    ResponseEntity<FillResponse> fillSlot(String code, int index, String name, byte[] body, String contentType,
                                          CustomUserDetails userDetails);

    @Operation(summary = "칸 비우기", description = "본인이 채운 칸, 또는 세션 주인은 어느 칸이든 비울 수 있습니다.")
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "비움"),
            @ApiResponse(responseCode = "403", description = "남이 채운 칸(주인 아님)")
    })
    ResponseEntity<FillResponse> clearSlot(String code, int index, CustomUserDetails userDetails);

    @Operation(summary = "커스텀 프레임 요소 업로드", description = "세션 주인이 커스텀 프레임의 k번째 요소 PNG를 올립니다. 응답 url이 customFrame.layers[k].url에 채워집니다.")
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장",
                    content = @Content(schema = @Schema(implementation = AssetUrlResponse.class))),
            @ApiResponse(responseCode = "403", description = "주인이 아님")
    })
    ResponseEntity<AssetUrlResponse> uploadFrameAsset(String code, int k, byte[] body, String contentType,
                                                      CustomUserDetails userDetails);

    @Operation(summary = "세션 없애기", description = "세션 주인만. 칸·사진·요소가 모두 삭제되고 링크는 더 열리지 않습니다.")
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제"),
            @ApiResponse(responseCode = "403", description = "주인이 아님"),
            @ApiResponse(responseCode = "404", description = "세션 없음")
    })
    ResponseEntity<Void> delete(String code, CustomUserDetails userDetails);

    @Operation(summary = "이미지 바이트", description = "칸 사진·프레임 요소. 인증 없이 열리지만 세션 코드와 22자 이미지 id가 함께 맞아야 합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "이미지"),
            @ApiResponse(responseCode = "404", description = "없음")
    })
    ResponseEntity<byte[]> image(String code, String imageId);
}
