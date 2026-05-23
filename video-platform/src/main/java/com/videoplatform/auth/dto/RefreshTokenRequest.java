package com.videoplatform.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RefreshTokenRequest {

    @NotBlank(message = "refresh_token 不能为空")
    private String refreshToken;
}
