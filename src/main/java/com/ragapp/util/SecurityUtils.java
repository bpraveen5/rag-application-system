package com.ragapp.util;

import com.ragapp.security.RagUserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

/**
 * Convenience helpers for accessing the authenticated principal
 * from any layer without autowiring Spring Security beans directly.
 */
public final class SecurityUtils {

    private SecurityUtils() {}

    public static Optional<RagUserPrincipal> getCurrentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return Optional.empty();
        if (auth.getPrincipal() instanceof RagUserPrincipal p) return Optional.of(p);
        return Optional.empty();
    }

    public static UUID getCurrentUserId() {
        return getCurrentPrincipal()
                .map(RagUserPrincipal::getId)
                .orElseThrow(() -> new IllegalStateException("No authenticated user in security context"));
    }

    public static String getCurrentUsername() {
        return getCurrentPrincipal()
                .map(RagUserPrincipal::getUsername)
                .orElse("anonymous");
    }

    public static String getCurrentTenantId() {
        return getCurrentPrincipal()
                .map(RagUserPrincipal::getTenantId)
                .orElse(null);
    }

    public static boolean hasRole(String role) {
        return getCurrentPrincipal()
                .map(p -> p.getAuthorities().stream()
                        .anyMatch(a -> a.getAuthority().equals("ROLE_" + role)))
                .orElse(false);
    }

    public static boolean isAdmin() {
        return hasRole("ADMIN");
    }
}
