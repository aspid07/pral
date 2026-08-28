package com.lowcode.platform.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public class AuthDto {

    public record RegisterRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, message = "password must be at least 8 characters") String password,
            @NotBlank String displayName) {}

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}

    // accessToken (не "token") — с появлением refresh-токена в теле ответа
    // недостаточно назвать поле однозначно: refresh-токен в тело НЕ попадает
    // вообще (уходит отдельно, httpOnly-cookie, см. AuthController) — это
    // единственный токен в теле ответа, поэтому имя должно явно называть, какой
    // именно это токен, а не токен вообще.
    public record TokenResponse(String accessToken, UUID userId, String email, String displayName) {}
}
