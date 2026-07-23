package com.ragapp.dto;

import com.ragapp.entity.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * DTOs for authentication flows.
 * All DTOs are Java Records – immutable value objects.
 */
public final class AuthDto {

    private AuthDto() {}

    // ─── Request DTOs ──────────────────────────────────────────────────────────

    public record LoginRequest(
        @NotBlank(message = "Username is required")
        String username,

        @NotBlank(message = "Password is required")
        String password
    ) {}

    public record RegisterRequest(
        @NotBlank(message = "Username is required")
        @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
        String username,

        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        String email,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 100, message = "Password must be between 8 and 100 characters")
        String password,

        @Size(max = 255, message = "Full name must be at most 255 characters")
        String fullName
    ) {}

    public record RefreshTokenRequest(
        @NotBlank(message = "Refresh token is required")
        String refreshToken
    ) {}

    // ─── Response DTOs ─────────────────────────────────────────────────────────

    public record LoginResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UserInfo user
    ) {
        public static LoginResponse of(String access, String refresh, long expiresIn, UserInfo user) {
            return new LoginResponse(access, refresh, "Bearer", expiresIn, user);
        }
    }

    public record UserInfo(
        UUID id,
        String username,
        String email,
        String fullName,
        Set<User.Role> roles,
        Instant createdAt
    ) {}

    public record MessageResponse(String message) {}
}
