package com.ragapp.service;

import com.ragapp.dto.AuthDto;
import com.ragapp.entity.User;
import com.ragapp.exception.RagException;
import com.ragapp.repository.UserRepository;
import com.ragapp.security.JwtTokenProvider;
import com.ragapp.security.RagUserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository        userRepository;
    private final PasswordEncoder       passwordEncoder;
    private final JwtTokenProvider      jwtTokenProvider;
    private final AuthenticationManager authenticationManager;

    // ─── Registration ─────────────────────────────────────────────────────────

    @Transactional
    public AuthDto.LoginResponse register(AuthDto.RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new RagException("Username already taken: " + request.username(),
                    HttpStatus.CONFLICT, "USERNAME_TAKEN");
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new RagException("Email already registered: " + request.email(),
                    HttpStatus.CONFLICT, "EMAIL_TAKEN");
        }

        User user = User.builder()
                .username(request.username())
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.fullName())
                .roles(Set.of(User.Role.USER))
                .enabled(true)
                .build();

        user = userRepository.save(user);
        log.info("User registered: id={}, username={}", user.getId(), user.getUsername());

        return buildLoginResponse(user);
    }

    // ─── Login ────────────────────────────────────────────────────────────────

    @Transactional
    public AuthDto.LoginResponse login(AuthDto.LoginRequest request) {
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));

        RagUserPrincipal principal = (RagUserPrincipal) auth.getPrincipal();
        userRepository.updateLastLoginAt(principal.getId(), Instant.now());

        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new RagException("User not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        log.info("User logged in: id={}, username={}", user.getId(), user.getUsername());
        return buildLoginResponse(user);
    }

    // ─── Token Refresh ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public AuthDto.LoginResponse refresh(AuthDto.RefreshTokenRequest request) {
        String refreshToken = request.refreshToken();

        if (!jwtTokenProvider.validateToken(refreshToken) || !jwtTokenProvider.isRefreshToken(refreshToken)) {
            throw new RagException("Invalid or expired refresh token",
                    HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN");
        }

        String username = jwtTokenProvider.getUsernameFromToken(refreshToken);
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RagException("User not found",
                        HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        return buildLoginResponse(user);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private AuthDto.LoginResponse buildLoginResponse(User user) {
        RagUserPrincipal principal = RagUserPrincipal.of(user);
        String access  = jwtTokenProvider.generateAccessToken(principal);
        String refresh = jwtTokenProvider.generateRefreshToken(user.getUsername());
        long   expMs   = jwtTokenProvider.getExpirationMs();

        AuthDto.UserInfo userInfo = new AuthDto.UserInfo(
                user.getId(), user.getUsername(), user.getEmail(),
                user.getFullName(), user.getRoles(), user.getCreatedAt());

        return AuthDto.LoginResponse.of(access, refresh, expMs / 1000, userInfo);
    }
}
