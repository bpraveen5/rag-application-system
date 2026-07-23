package com.ragapp.config;

import com.ragapp.exception.RateLimitException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token-bucket rate limiting per IP address.
 * Different buckets for /chat vs /documents/upload endpoints.
 * Backed by an in-memory ConcurrentHashMap; swap for Redis bucket store
 * in multi-node deployments via {@code bucket4j-redis}.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class RateLimitingFilter extends OncePerRequestFilter {

    private final AppProperties props;

    // Separate bucket maps per endpoint class
    private final Map<String, Bucket> chatBuckets   = new ConcurrentHashMap<>();
    private final Map<String, Bucket> uploadBuckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest  request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain         chain)
            throws ServletException, IOException {

        if (!props.getRateLimiting().isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        String path     = request.getRequestURI();
        String clientIp = extractClientIp(request);

        try {
            if (path.contains("/chat")) {
                checkLimit(chatBuckets, clientIp, props.getRateLimiting().getChat(), "chat");
            } else if (path.contains("/documents/upload")) {
                checkLimit(uploadBuckets, clientIp, props.getRateLimiting().getUpload(), "upload");
            }
        } catch (RateLimitException ex) {
            response.setStatus(429);
            response.setContentType("application/problem+json");
            response.getWriter().write("""
                    {"status":429,"title":"Too Many Requests","detail":"%s","errorCode":"RATE_LIMIT_EXCEEDED"}
                    """.formatted(ex.getMessage()));
            return;
        }

        chain.doFilter(request, response);
    }

    private void checkLimit(Map<String, Bucket> buckets, String clientIp,
                             AppProperties.RateLimiting.Bucket cfg, String endpoint) {
        Bucket bucket = buckets.computeIfAbsent(clientIp, k -> buildBucket(cfg));
        if (!bucket.tryConsume(1)) {
            log.warn("Rate limit exceeded for IP={} on endpoint={}", clientIp, endpoint);
            throw new RateLimitException(
                    "Rate limit exceeded for " + endpoint + ". Try again in a moment.");
        }
    }

    private Bucket buildBucket(AppProperties.RateLimiting.Bucket cfg) {
        Bandwidth limit = Bandwidth.classic(
                cfg.capacity(),
                Refill.intervally(cfg.refillTokens(),
                        Duration.ofSeconds(cfg.refillDurationSeconds())));
        return Bucket.builder().addLimit(limit).build();
    }

    private String extractClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String xrip = request.getHeader("X-Real-IP");
        if (xrip != null && !xrip.isBlank()) return xrip;
        return request.getRemoteAddr();
    }
}
