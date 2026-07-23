package com.ragapp.unit;

import com.ragapp.dto.AuthDto;
import com.ragapp.entity.User;
import com.ragapp.exception.RagException;
import com.ragapp.repository.UserRepository;
import com.ragapp.security.JwtTokenProvider;
import com.ragapp.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService Unit Tests")
class AuthServiceTest {

    @Mock UserRepository        userRepository;
    @Mock PasswordEncoder       passwordEncoder;
    @Mock JwtTokenProvider      jwtTokenProvider;
    @Mock AuthenticationManager authenticationManager;

    @InjectMocks AuthService authService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(UUID.randomUUID())
                .username("testuser")
                .email("test@example.com")
                .passwordHash("hashed_password")
                .fullName("Test User")
                .roles(Set.of(User.Role.USER))
                .enabled(true)
                .build();
    }

    // ─── Registration ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("register() succeeds with valid request")
    void register_succeeds_with_valid_request() {
        AuthDto.RegisterRequest request = new AuthDto.RegisterRequest(
                "newuser", "new@example.com", "Password123!", "New User");

        when(userRepository.existsByUsername("newuser")).thenReturn(false);
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenReturn(testUser);
        when(jwtTokenProvider.generateAccessToken(any(com.ragapp.security.RagUserPrincipal.class))).thenReturn("access_token");
        when(jwtTokenProvider.generateRefreshToken(anyString())).thenReturn("refresh_token");
        when(jwtTokenProvider.getExpirationMs()).thenReturn(86400000L);

        AuthDto.LoginResponse response = authService.register(request);

        assertThat(response).isNotNull();
        assertThat(response.accessToken()).isEqualTo("access_token");
        assertThat(response.refreshToken()).isEqualTo("refresh_token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
    }

    @Test
    @DisplayName("register() throws CONFLICT when username taken")
    void register_throws_when_username_taken() {
        AuthDto.RegisterRequest request = new AuthDto.RegisterRequest(
                "testuser", "other@example.com", "Password123!", null);

        when(userRepository.existsByUsername("testuser")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(RagException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("register() throws CONFLICT when email taken")
    void register_throws_when_email_taken() {
        AuthDto.RegisterRequest request = new AuthDto.RegisterRequest(
                "newuser", "test@example.com", "Password123!", null);

        when(userRepository.existsByUsername("newuser")).thenReturn(false);
        when(userRepository.existsByEmail("test@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(RagException.class)
                .extracting("errorCode").isEqualTo("EMAIL_TAKEN");
    }

    // ─── Login ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("login() returns tokens on valid credentials")
    void login_returns_tokens_on_valid_credentials() {
        AuthDto.LoginRequest request = new AuthDto.LoginRequest("testuser", "Password123!");
        Authentication mockAuth = mock(Authentication.class);
        com.ragapp.security.RagUserPrincipal principal =
                com.ragapp.security.RagUserPrincipal.of(testUser);

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(mockAuth);
        when(mockAuth.getPrincipal()).thenReturn(principal);
        when(userRepository.findById(any(UUID.class))).thenReturn(Optional.of(testUser));
        when(jwtTokenProvider.generateAccessToken(any(com.ragapp.security.RagUserPrincipal.class))).thenReturn("access_token");
        when(jwtTokenProvider.generateRefreshToken(anyString())).thenReturn("refresh_token");
        when(jwtTokenProvider.getExpirationMs()).thenReturn(86400000L);
        doNothing().when(userRepository).updateLastLoginAt(any(UUID.class), any());

        AuthDto.LoginResponse response = authService.login(request);

        assertThat(response.accessToken()).isEqualTo("access_token");
        verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
    }

    @Test
    @DisplayName("login() propagates BadCredentialsException")
    void login_propagates_bad_credentials() {
        AuthDto.LoginRequest request = new AuthDto.LoginRequest("testuser", "wrong");
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BadCredentialsException.class);
    }
}
