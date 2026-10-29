package com.caestro.server.domain.fill.controller.api;

import com.caestro.server.domain.fill.dto.request.CreateFillRequest;
import com.caestro.server.domain.fill.dto.request.UpdateFrameRequest;
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

    @Operation(summary = "프레임 바꾸기(주인)",
            description = "바탕색·배치·그림 프레임·커스텀 프레임·무테를 한 번에 바꿉니다(PUT 의미). 칸의 사진은 그대로이고, 커스텀 프레임 요소 PNG는 이 요청 뒤 frame-assets로 다시 올립니다.")
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "바뀐 세션"),
            @ApiResponse(responseCode = "400", description = "frame/layout 값이 유효하지 않음"),
            @ApiResponse(responseCode = "403", description = "주인이 아님"),
            @ApiResponse(responseCode = "404", description = "세션 없음"),
            @ApiResponse(responseCode = "410", description = "만료된 세션")
    })
    ResponseEntity<FillResponse> updateFrame(String code, UpdateFrameRequest request, CustomUserDetails userDetails);

    @Operation(summary = "세션 조회", description = "칸마다 사진 URL과 누가 채웠는지(filledByMe/filledByName)를 내려줍니다. 클라이언트는 화면이 열려 있는 동안 주기적으로 호출합니다.")
    @SecurityRequirement(name = "BearerAuth")
    @Parameter(name = "code", description = "세션 코드(6자리)", in = ParameterIn.PATH, required = true)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공. ETag 포함 — 화면을 보는 동안 3~5초마다 If-None-Match 로 다시 묻고, 304 면 그대로",
                    content = @Content(schema = @Schema(implementation = FillResponse.class))),
            @ApiResponse(responseCode = "304", description = "변경 없음(If-None-Match 가 현재 ETag 와 같음), 본문 없음"),
            @ApiResponse(responseCode = "401", description = "토큰이 없음"),
            @ApiResponse(responseCode = "404", description = "세션 없음"),
            @ApiResponse(responseCode = "410", description = "세션 만료")
    })
    ResponseEntity<FillResponse> get(String code, CustomUserDetails userDetails,
                                     @Parameter(description = "직전 응답의 ETag. 같으면 본문 없이 304") String ifNoneMatch);

    @Operation(summary = "칸 채우기",
            description = "본문은 이미지 바이트(image/jpeg 또는 image/png, 최대 3MB). 빈 칸이든 누가 채운 칸이든 누구나 넣을 수 있으며, "
                    + "채워진 칸이었으면 이전 사진은 클립과 함께 대기열(queue) 맨 아래로 내려갑니다. name은 웹 참여자의 표시 이름(선택).")
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "채움",
                    content = @Content(schema = @Schema(implementation = FillResponse.class))),
            @ApiResponse(responseCode = "400", description = "이미지가 아니거나 칸 번호가 잘못됨"),
            @ApiResponse(responseCode = "413", description = "이미지가 너무 큼")
    })
    ResponseEntity<FillResponse> fillSlot(String code, int index, String name, byte[] body, String contentType,
                                          CustomUserDetails userDetails);

    @Operation(summary = "칸 클립 올리기",
            description = "칸의 사진과 함께 찍힌 짧은 움직임(image/gif, 최대 6MB). 그 칸을 채운 본인만 올릴 수 있고, "
                    + "FillResponse.slots[].clipUrl로 내려갑니다. 칸 사진이 바뀌거나 칸을 비우면 함께 지워지고, 완성 GIF(gifUrl)도 지워집니다.")
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "붙임",
                    content = @Content(schema = @Schema(implementation = FillResponse.class))),
            @ApiResponse(responseCode = "400", description = "GIF가 아니거나 빈 칸"),
            @ApiResponse(responseCode = "403", description = "그 칸을 채운 사람이 아님"),
            @ApiResponse(responseCode = "413", description = "GIF가 너무 큼")
    })
    ResponseEntity<FillResponse> uploadSlotClip(String code, int index, byte[] body, String contentType,
                                                CustomUserDetails userDetails);

    @Operation(summary = "칸 비우기", description = "누구나 어느 칸이든 비울 수 있습니다. 사진은 지워지지 않고 대기열(queue) 맨 아래로 내려가 되돌릴 수 있습니다.")
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "비움")
    })
    ResponseEntity<FillResponse> clearSlot(String code, int index, CustomUserDetails userDetails);

    @Operation(summary = "대기열에서 지우기", description = "칸에서 밀려나 대기열에 있는 사진을 지웁니다(누구나). 사진·클립 바이트도 사라집니다.")
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "지움",
                    content = @Content(schema = @Schema(implementation = FillResponse.class))),
            @ApiResponse(responseCode = "404", description = "대기열에 그 사진이 없음")
    })
    ResponseEntity<FillResponse> deleteQueued(String code, long id, CustomUserDetails userDetails);

    @Operation(summary = "대기열에서 칸으로 되돌리기", description = "대기열의 사진을 slot 번 칸(0~3)에 넣습니다(누구나). "
            + "그 칸에 있던 사진은 대기열 맨 아래로 내려갑니다. 사진의 주인·이름·클립이 함께 돌아갑니다.")
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "되돌림",
                    content = @Content(schema = @Schema(implementation = FillResponse.class))),
            @ApiResponse(responseCode = "400", description = "칸 번호가 잘못됨"),
            @ApiResponse(responseCode = "404", description = "대기열에 그 사진이 없음")
    })
    ResponseEntity<FillResponse> restoreQueued(String code, long id, int slot, CustomUserDetails userDetails);

    @Operation(summary = "커스텀 프레임 요소 업로드", description = "세션 주인이 커스텀 프레임의 k번째 요소 PNG를 올립니다. 응답 url이 customFrame.layers[k].url에 채워집니다.")
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장",
                    content = @Content(schema = @Schema(implementation = AssetUrlResponse.class))),
            @ApiResponse(responseCode = "403", description = "주인이 아님")
    })
    ResponseEntity<AssetUrlResponse> uploadFrameAsset(String code, int k, byte[] body, String contentType,
                                                      CustomUserDetails userDetails);

    @Operation(summary = "완성 네컷 GIF 업로드", description = "세션 주인이 완성된 네컷의 움직이는 GIF를 올립니다(앱이 저장된 네컷을 링크로 공유할 때). "
            + "응답 url이 FillResponse.gifUrl에 실리며, 칸이나 프레임이 바뀌면 지워집니다. 최대 16MB.")
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장",
                    content = @Content(schema = @Schema(implementation = AssetUrlResponse.class))),
            @ApiResponse(responseCode = "400", description = "GIF가 아님"),
            @ApiResponse(responseCode = "403", description = "주인이 아님"),
            @ApiResponse(responseCode = "413", description = "GIF가 너무 큼")
    })
    ResponseEntity<AssetUrlResponse> uploadGif(String code, byte[] body, String contentType, CustomUserDetails userDetails);

    @Operation(summary = "세션 없애기", description = "세션 주인만. 칸·사진·요소가 모두 삭제되고 링크는 더 열리지 않습니다.")
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제"),
            @ApiResponse(responseCode = "403", description = "주인이 아님"),
            @ApiResponse(responseCode = "404", description = "세션 없음")
    })
    ResponseEntity<Void> delete(String code, CustomUserDetails userDetails);

    @Operation(summary = "이미지 바이트", description = "칸 사진·프레임 요소·GIF. 인증 없이 열리지만 세션 코드와 22자 이미지 id가 함께 맞아야 합니다. "
            + "저장소가 S3면 짧은 수명의 서명 주소로 302를 보내므로 클라이언트는 리다이렉트를 따라가야 합니다(브라우저·OkHttp 기본 동작).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "이미지 바이트(DB 저장소)"),
            @ApiResponse(responseCode = "302", description = "S3 서명 주소로 리다이렉트(S3 저장소)"),
            @ApiResponse(responseCode = "404", description = "없음")
    })
    ResponseEntity<byte[]> image(String code, String imageId);
}
