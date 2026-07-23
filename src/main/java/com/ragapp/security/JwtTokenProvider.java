package com.ragapp.security;

import com.ragapp.config.AppProperties;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Issues and validates JWT access and refresh tokens.
 * Uses HMAC-SHA256 signed with a secret from configuration.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private final AppProperties props;

    // ─── Token Generation ──────────────────────────────────────────────────────

    public String generateAccessToken(Authentication authentication) {
        RagUserPrincipal principal = (RagUserPrincipal) authentication.getPrincipal();
        return buildToken(principal.getUsername(), principal.getId().toString(),
                getRoles(authentication), props.getSecurity().getJwt().getExpirationMs());
    }

    public String generateAccessToken(RagUserPrincipal principal) {
        return buildToken(principal.getUsername(), principal.getId().toString(),
                principal.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority).toList(),
                props.getSecurity().getJwt().getExpirationMs());
    }

    public String generateRefreshToken(String username) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claim("type", "refresh")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + props.getSecurity().getJwt().getRefreshExpirationMs()))
                .signWith(signingKey())
                .compact();
    }

    private String buildToken(String username, String userId, List<String> roles, long expirationMs) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claim("userId", userId)
                .claim("roles", roles)
                .claim("type", "access")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs))
                .signWith(signingKey())
                .compact();
    }

    // ─── Token Parsing ─────────────────────────────────────────────────────────

    public String getUsernameFromToken(String token) {
        return parseClaims(token).getSubject();
    }

    public UUID getUserIdFromToken(String token) {
        String userId = (String) parseClaims(token).get("userId");
        return UUID.fromString(userId);
    }

    @SuppressWarnings("unchecked")
    public List<String> getRolesFromToken(String token) {
        return (List<String>) parseClaims(token).get("roles");
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (ExpiredJwtException ex) {
            log.warn("JWT token is expired: {}", ex.getMessage());
        } catch (MalformedJwtException ex) {
            log.warn("JWT token is malformed: {}", ex.getMessage());
        } catch (SecurityException ex) {
            log.warn("JWT signature is invalid: {}", ex.getMessage());
        } catch (UnsupportedJwtException ex) {
            log.warn("JWT token is unsupported: {}", ex.getMessage());
        } catch (IllegalArgumentException ex) {
            log.warn("JWT claims string is empty: {}", ex.getMessage());
        }
        return false;
    }

    public boolean isRefreshToken(String token) {
        return "refresh".equals(parseClaims(token).get("type"));
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey signingKey() {
        byte[] keyBytes = Decoders.BASE64.decode(
                java.util.Base64.getEncoder().encodeToString(
                        props.getSecurity().getJwt().getSecret().getBytes()));
        return Keys.hmacShaKeyFor(keyBytes);
    }

    private List<String> getRoles(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
    }

    public long getExpirationMs() {
        return props.getSecurity().getJwt().getExpirationMs();
    }
}
