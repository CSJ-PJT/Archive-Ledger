package com.archiveledger.ledger.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ArchiveRequestSecurityFilter extends OncePerRequestFilter {
    public static final String SOURCE_HEADER = "X-Archive-Source-System";
    public static final String SCOPE_HEADER = "X-Archive-Service-Scope";

    private final ArchiveSecurityProperties properties;
    private final Map<String, RateWindow> rateWindows = new ConcurrentHashMap<>();

    public ArchiveRequestSecurityFilter(ArchiveSecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!properties.enabled()) {
            chain.doFilter(request, response);
            return;
        }

        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank() && !properties.allowedOrigins().contains(origin)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "origin_not_allowed");
            return;
        }
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            writeCorsHeaders(request, response);
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        writeCorsHeaders(request, response);

        AccessRule rule = ruleFor(request);
        if (rule == null) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > properties.maxPayloadBytes()) {
            reject(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "payload_too_large");
            return;
        }
        String source = request.getHeader(SOURCE_HEADER);
        String scope = request.getHeader(SCOPE_HEADER);
        String token = bearerToken(request.getHeader("Authorization"));
        if (token == null || source == null || scope == null) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "service_credentials_required");
            return;
        }
        source = source.trim().toLowerCase();
        scope = canonicalScope(scope);
        if (!rule.source().equals(source) || !rule.scope().equals(scope)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "service_identity_or_scope_denied");
            return;
        }
        if (!constantTimeEquals(rule.token(), token)) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "invalid_service_token");
            return;
        }
        if (rule.write() && !allowRequest(source + ':' + request.getRequestURI())) {
            response.setHeader("Retry-After", "60");
            reject(response, 429, "write_rate_limit_exceeded");
            return;
        }
        chain.doFilter(request, response);
    }

    private AccessRule ruleFor(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if ("/actuator/health".equals(path) || "/actuator/health/liveness".equals(path) || "/actuator/health/readiness".equals(path)) {
            return null;
        }
        if (HttpMethod.POST.matches(method) && path.startsWith("/api/events/market")) {
            return new AccessRule("archive-market", "ledger:ingest", properties.marketIngestToken(), true);
        }
        if (HttpMethod.POST.matches(method) && path.startsWith("/api/events/nexus")) {
            return new AccessRule("archive-nexus", "ledger:ingest", properties.nexusIngestToken(), true);
        }
        if (HttpMethod.POST.matches(method) && path.startsWith("/api/events/logistics")) {
            return new AccessRule("archive-logistics", "ledger:ingest", properties.logisticsIngestToken(), true);
        }
        if (HttpMethod.POST.matches(method) && "/api/approvals/callback".equals(path)) {
            return new AccessRule("archive-os", "ledger:approval-callback", properties.archiveOsCallbackToken(), true);
        }
        if (HttpMethod.POST.matches(method)) {
            return new AccessRule("archive-os", "admin:operate", properties.adminToken(), true);
        }
        if (path.startsWith("/actuator/") || isSensitiveRead(path)) {
            return new AccessRule("archive-os", "authenticated:read", properties.readToken(), false);
        }
        return null;
    }

    private boolean isSensitiveRead(String path) {
        return path.startsWith("/api/events/received") || path.startsWith("/api/transactions")
                || path.startsWith("/api/ledger/") || path.startsWith("/api/settlements")
                || path.startsWith("/api/batches") || path.startsWith("/api/runtime-events")
                || path.startsWith("/api/runtime-outbound")
                || path.startsWith("/api/workforce/") || path.startsWith("/api/productivity/")
                || path.startsWith("/api/capacity/") || path.startsWith("/api/settlement-agency/");
    }

    private String canonicalScope(String scope) {
        if ("ledger:read".equals(scope) || "runtime:read".equals(scope)) return "authenticated:read";
        return scope;
    }

    private boolean allowRequest(String key) {
        Instant now = Instant.now();
        RateWindow window = rateWindows.compute(key, (ignored, current) -> {
            if (current == null || Duration.between(current.startedAt(), now).compareTo(Duration.ofMinutes(1)) >= 0) {
                return new RateWindow(now, 1);
            }
            return new RateWindow(current.startedAt(), current.requests() + 1);
        });
        return window.requests() <= properties.writeRequestsPerMinute();
    }

    private void writeCorsHeaders(HttpServletRequest request, HttpServletResponse response) {
        String origin = request.getHeader("Origin");
        if (origin != null && properties.allowedOrigins().contains(origin)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Vary", "Origin");
            response.setHeader("Access-Control-Allow-Headers", "Authorization, " + SOURCE_HEADER + ", " + SCOPE_HEADER + ", Content-Type");
            response.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            response.setHeader("Access-Control-Max-Age", "600");
        }
    }

    private String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String token = authorization.substring("Bearer ".length()).trim();
        return token.isBlank() ? null : token;
    }

    private boolean constantTimeEquals(String expected, String provided) {
        if (expected == null || expected.isBlank() || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }

    private void reject(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + code + "\"}");
    }

    private record AccessRule(String source, String scope, String token, boolean write) { }
    private record RateWindow(Instant startedAt, int requests) { }
}
