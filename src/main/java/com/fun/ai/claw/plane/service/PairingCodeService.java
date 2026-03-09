package com.fun.ai.claw.plane.service;

import com.fun.ai.claw.plane.config.DockerRuntimeProperties;
import com.fun.ai.claw.plane.model.PairingCodeResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PairingCodeService {

    private static final Pattern PAIRED_TOKENS_PATTERN = Pattern.compile("(?is)\\bpaired_tokens\\s*=\\s*\\[(.*?)]");
    private static final Pattern TOKEN_FIELD_PATTERN = Pattern.compile("(?is)\\btoken\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern QUOTED_STRING_PATTERN = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");

    private final DockerRuntimeService dockerRuntimeService;
    private final String fixedPairingCode;
    private final String fixedLinkPath;
    private final String gatewayProbeScheme;
    private final String gatewayProbeHost;
    private final String autoAuthQueryParam;
    private final String authTokenQueryParam;
    private final String gatewayConfigPath;
    private final Duration authProbeTimeout;
    private final HttpClient authProbeClient;

    public PairingCodeService(DockerRuntimeService dockerRuntimeService,
                              DockerRuntimeProperties dockerRuntimeProperties,
                              @Value("${app.pairing-code.fixed-code:809393}") String fixedPairingCode,
                              @Value("${app.pairing-code.fixed-link-path:/}") String fixedLinkPath,
                              @Value("${app.pairing-code.gateway-probe-scheme:http}") String gatewayProbeScheme,
                              @Value("${app.pairing-code.gateway-probe-host:}") String gatewayProbeHost,
                              @Value("${app.pairing-code.auto-auth-query-param:autoAuth}") String autoAuthQueryParam,
                              @Value("${app.pairing-code.auth-token-query-param:authToken}") String authTokenQueryParam,
                              @Value("${app.pairing-code.auth-probe-timeout-seconds:5}") long authProbeTimeoutSeconds,
                              @Value("${app.pairing-code.gateway-config-path:}") String gatewayConfigPath) {
        this.dockerRuntimeService = dockerRuntimeService;
        this.fixedPairingCode = fixedPairingCode == null ? "" : fixedPairingCode.trim();
        this.fixedLinkPath = normalizeFixedLinkPath(fixedLinkPath);
        this.gatewayProbeScheme = normalizeProbeScheme(gatewayProbeScheme);
        this.gatewayProbeHost = requireProbeHost(gatewayProbeHost, dockerRuntimeProperties.getGatewayReadyHost());
        this.autoAuthQueryParam = StringUtils.hasText(autoAuthQueryParam) ? autoAuthQueryParam.trim() : "autoAuth";
        this.authTokenQueryParam = StringUtils.hasText(authTokenQueryParam) ? authTokenQueryParam.trim() : "authToken";
        long probeSeconds = authProbeTimeoutSeconds > 0 ? authProbeTimeoutSeconds : 5;
        this.authProbeTimeout = Duration.ofSeconds(probeSeconds);
        this.authProbeClient = HttpClient.newBuilder()
                .connectTimeout(this.authProbeTimeout)
                .build();
        this.gatewayConfigPath = StringUtils.hasText(gatewayConfigPath) ? gatewayConfigPath.trim() : null;
    }

    public PairingCodeResponse fetchPairingCode(UUID instanceId) {
        Instant fetchedAt = Instant.now();
        Integer gatewayHostPort = dockerRuntimeService.resolveInstanceGatewayHostPort(instanceId);

        String gatewayUrl = resolveGatewayUrl(instanceId, gatewayHostPort);
        String directGatewayProbeUrl = resolveGatewayProbeUrl(gatewayHostPort);
        if (!StringUtils.hasText(gatewayUrl)) {
            return new PairingCodeResponse(
                    instanceId,
                    fixedPairingCode,
                    null,
                    null,
                    "instance gateway url is not available",
                    fetchedAt
            );
        }

        String pairingLink = buildPairingLink(gatewayUrl);
        String authProbeBaseUrl = StringUtils.hasText(directGatewayProbeUrl) ? directGatewayProbeUrl : gatewayUrl;
        if (isUnauthenticatedAccessAvailable(authProbeBaseUrl)) {
            return new PairingCodeResponse(
                    instanceId,
                    null,
                    pairingLink,
                    null,
                    "pairing is disabled (require_pairing=false), open link directly",
                    fetchedAt
            );
        }
        List<String> candidateTokens = readPairedTokens(instanceId, gatewayHostPort);
        String usableToken = findUsableToken(candidateTokens, authProbeBaseUrl);

        if (StringUtils.hasText(usableToken)) {
            String tokenLink = appendQueryParam(pairingLink, autoAuthQueryParam, "1");
            tokenLink = appendQueryParam(tokenLink, authTokenQueryParam, usableToken);
            return new PairingCodeResponse(
                    instanceId,
                    fixedPairingCode,
                    tokenLink,
                    "Authorization: Bearer <validated token>",
                    "open pairing link for direct auto-login (no /pair request needed)",
                    fetchedAt
            );
        }

        if (!StringUtils.hasText(fixedPairingCode)) {
            return new PairingCodeResponse(
                    instanceId,
                    null,
                    pairingLink,
                    null,
                    candidateTokens.isEmpty()
                            ? "paired token not found and fixed pairing code is not configured"
                            : "paired token candidates found but all were invalid",
                    fetchedAt
            );
        }

        String pairEndpoint = buildPairEndpoint(gatewayUrl);
        String requestExample = "POST " + pairEndpoint + " with header X-Pairing-Code: " + fixedPairingCode;

        return new PairingCodeResponse(
                instanceId,
                fixedPairingCode,
                pairingLink,
                requestExample,
                candidateTokens.isEmpty()
                        ? "paired token not found; fallback to manual pairing request"
                        : "paired token candidates were invalid; fallback to manual pairing request",
                fetchedAt
        );
    }

    private List<String> readPairedTokens(UUID instanceId, Integer gatewayHostPort) {
        String configPath = resolveGatewayConfigPath(instanceId, gatewayHostPort);
        if (!StringUtils.hasText(configPath)) {
            return List.of();
        }
        String configText = dockerRuntimeService.readTextFileIfPresent(instanceId, configPath);
        if (!StringUtils.hasText(configText)) {
            return List.of();
        }
        return extractPairedTokens(configText);
    }

    private List<String> extractPairedTokens(String configText) {
        Matcher tokenArrayMatcher = PAIRED_TOKENS_PATTERN.matcher(configText);
        if (!tokenArrayMatcher.find()) {
            return List.of();
        }

        String tokenArrayBody = tokenArrayMatcher.group(1);
        Set<String> tokens = new LinkedHashSet<>();

        Matcher namedTokenMatcher = TOKEN_FIELD_PATTERN.matcher(tokenArrayBody);
        while (namedTokenMatcher.find()) {
            String token = unescapeTomlString(namedTokenMatcher.group(1));
            if (StringUtils.hasText(token)) {
                tokens.add(token.trim());
            }
        }

        if (tokens.isEmpty()) {
            Matcher tokenMatcher = QUOTED_STRING_PATTERN.matcher(tokenArrayBody);
            while (tokenMatcher.find()) {
                String token = unescapeTomlString(tokenMatcher.group(1));
                if (StringUtils.hasText(token)) {
                    tokens.add(token.trim());
                }
            }
        }

        return new ArrayList<>(tokens);
    }

    private String unescapeTomlString(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private String findUsableToken(List<String> candidateTokens, String gatewayUrl) {
        if (candidateTokens == null || candidateTokens.isEmpty()) {
            return null;
        }
        for (String token : candidateTokens) {
            if (!StringUtils.hasText(token)) {
                continue;
            }
            if (isTokenUsable(token.trim(), gatewayUrl)) {
                return token.trim();
            }
        }
        return null;
    }

    private boolean isTokenUsable(String token, String gatewayUrl) {
        try {
            URI statusUri = URI.create(buildApiStatusEndpoint(gatewayUrl));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(statusUri)
                    .timeout(authProbeTimeout)
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            HttpResponse<Void> response = authProbeClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String buildApiStatusEndpoint(String gatewayUrl) {
        String normalized = gatewayUrl.trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized + "/api/status";
    }

    private boolean isUnauthenticatedAccessAvailable(String gatewayUrl) {
        try {
            URI statusUri = URI.create(buildApiStatusEndpoint(gatewayUrl));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(statusUri)
                    .timeout(authProbeTimeout)
                    .GET()
                    .build();
            HttpResponse<Void> response = authProbeClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String normalizeFixedLinkPath(String path) {
        if (!StringUtils.hasText(path)) {
            return "/";
        }
        String trimmed = path.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private String normalizeProbeScheme(String scheme) {
        if (!StringUtils.hasText(scheme)) {
            return "http";
        }
        String normalized = scheme.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("http") && !normalized.equals("https")) {
            return "http";
        }
        return normalized;
    }

    private String requireProbeHost(String configuredHost, String fallbackHost) {
        if (StringUtils.hasText(configuredHost)) {
            return configuredHost.trim();
        }
        if (StringUtils.hasText(fallbackHost)) {
            return fallbackHost.trim();
        }
        if (!StringUtils.hasText(configuredHost)) {
            return "127.0.0.1";
        }
        return configuredHost.trim();
    }

    private String resolveGatewayUrl(UUID instanceId, Integer gatewayHostPort) {
        return dockerRuntimeService.resolveGatewayPublicUrl(instanceId, gatewayHostPort);
    }

    private String resolveGatewayConfigPath(UUID instanceId, Integer gatewayHostPort) {
        if (StringUtils.hasText(gatewayConfigPath)) {
            return gatewayConfigPath;
        }
        return dockerRuntimeService.resolveGatewayConfigPath(instanceId, gatewayHostPort);
    }

    private String resolveGatewayProbeUrl(Integer gatewayHostPort) {
        if (gatewayHostPort == null || gatewayHostPort <= 0 || gatewayHostPort > 65535) {
            return null;
        }
        return gatewayProbeScheme + "://" + gatewayProbeHost + ":" + gatewayHostPort;
    }

    private String buildPairingLink(String gatewayUrl) {
        String trimmedGatewayUrl = gatewayUrl.trim();
        if (!StringUtils.hasText(trimmedGatewayUrl)) {
            return fixedLinkPath;
        }
        if (trimmedGatewayUrl.endsWith("/")) {
            return trimmedGatewayUrl.substring(0, trimmedGatewayUrl.length() - 1) + fixedLinkPath;
        }
        return trimmedGatewayUrl + fixedLinkPath;
    }

    private String buildPairEndpoint(String gatewayUrl) {
        String trimmedGatewayUrl = gatewayUrl.trim();
        if (!StringUtils.hasText(trimmedGatewayUrl)) {
            return "/pair";
        }
        if (trimmedGatewayUrl.endsWith("/")) {
            return trimmedGatewayUrl.substring(0, trimmedGatewayUrl.length() - 1) + "/pair";
        }
        return trimmedGatewayUrl + "/pair";
    }

    private String appendQueryParam(String url, String key, String value) {
        if (!StringUtils.hasText(url) || !StringUtils.hasText(key) || value == null) {
            return url;
        }
        String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8);
        String encodedValue = URLEncoder.encode(value, StandardCharsets.UTF_8);
        String lowerUrl = url.toLowerCase(Locale.ROOT);
        if (lowerUrl.contains(encodedKey.toLowerCase(Locale.ROOT) + "=")) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + encodedKey + "=" + encodedValue;
    }
}
