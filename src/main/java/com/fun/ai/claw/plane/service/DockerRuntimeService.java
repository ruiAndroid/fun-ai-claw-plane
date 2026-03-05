package com.fun.ai.claw.plane.service;

import com.fun.ai.claw.plane.config.DockerRuntimeProperties;
import com.fun.ai.claw.plane.model.CommandAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DockerRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(DockerRuntimeService.class);
    private static final Pattern LONG_OPTION_PATTERN = Pattern.compile("--[a-zA-Z0-9][a-zA-Z0-9-]*");
    private static final Pattern REQUIRE_PAIRING_PATTERN =
            Pattern.compile("(?m)^\\s*require_pairing\\s*=\\s*(true|false)\\s*$");
    private static final Pattern GATEWAY_HOST_PATTERN =
            Pattern.compile("(?m)^\\s*host\\s*=\\s*\"[^\"]*\"\\s*$");
    private static final Pattern ALLOW_PUBLIC_BIND_PATTERN =
            Pattern.compile("(?m)^\\s*allow_public_bind\\s*=\\s*(true|false)\\s*$");
    private static final Pattern GATEWAY_SECTION_PATTERN = Pattern.compile("(?m)^\\[gateway\\]\\s*$");
    private static final Pattern SKILLS_SECTION_PATTERN = Pattern.compile("(?m)^\\[skills\\]\\s*$");
    private static final Pattern OPEN_SKILLS_ENABLED_PATTERN =
            Pattern.compile("(?m)^\\s*open_skills_enabled\\s*=\\s*(true|false)\\s*$");
    private static final Pattern OPEN_SKILLS_DIR_PATTERN =
            Pattern.compile("(?m)^\\s*open_skills_dir\\s*=\\s*\"[^\"]*\"\\s*$");
    private static final Pattern PROMPT_INJECTION_MODE_PATTERN =
            Pattern.compile("(?m)^\\s*prompt_injection_mode\\s*=\\s*\"[^\"]*\"\\s*$");
    private static final Pattern SECTION_HEADER_PATTERN = Pattern.compile("(?m)^\\[[^\\]]+\\]\\s*$");

    private final DockerRuntimeProperties properties;
    private final Map<String, Set<String>> gatewayOptionsByImage = new ConcurrentHashMap<>();
    private final HttpClient healthProbeClient;

    public DockerRuntimeService(DockerRuntimeProperties properties) {
        this.properties = properties;
        this.healthProbeClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    public String execute(UUID instanceId, CommandAction action, Map<String, Object> payload) {
        if (!properties.isEnabled()) {
            throw new DockerOperationException("docker runtime is disabled");
        }

        String image = resolveImage(payload);
        Integer gatewayHostPort = resolveGatewayHostPort(payload);
        return switch (action) {
            case START -> startInstance(instanceId, image, gatewayHostPort);
            case STOP -> stopInstance(instanceId);
            case RESTART, ROLLBACK -> restartInstance(instanceId, image, gatewayHostPort);
            case DELETE -> deleteInstance(instanceId);
        };
    }

    private String startInstance(UUID instanceId, String image, Integer gatewayHostPort) {
        String containerName = containerName(instanceId);
        boolean createdNow = false;
        if (!containerExists(containerName)) {
            if (!StringUtils.hasText(image)) {
                throw new DockerOperationException("image is required to create container");
            }
            createContainer(containerName, instanceId, image.trim(), gatewayHostPort);
            createdNow = true;
        }

        if (containerRunning(containerName)) {
            enforceRuntimeConfigPolicy(containerName, instanceId, gatewayHostPort);
            waitForGatewayReady(containerName, resolveGatewayHostPortForProbe(containerName, gatewayHostPort));
            return "Container already running: " + containerName;
        }

        try {
            runDockerChecked(List.of(properties.getCommand(), "start", containerName), "failed to start container");
            enforceRuntimeConfigPolicy(containerName, instanceId, gatewayHostPort);
            waitForGatewayReady(containerName, resolveGatewayHostPortForProbe(containerName, gatewayHostPort));
        } catch (DockerOperationException ex) {
            if (createdNow) {
                removeContainerQuietly(containerName);
            }
            throw ex;
        }
        return "Container started: " + containerName;
    }

    private String stopInstance(UUID instanceId) {
        String containerName = containerName(instanceId);
        if (!containerExists(containerName)) {
            return "Container not found, stop skipped: " + containerName;
        }

        if (!containerRunning(containerName)) {
            return "Container already stopped: " + containerName;
        }

        runDockerChecked(List.of(properties.getCommand(), "stop", containerName), "failed to stop container");
        return "Container stopped: " + containerName;
    }

    private String restartInstance(UUID instanceId, String image, Integer gatewayHostPort) {
        String containerName = containerName(instanceId);
        if (!containerExists(containerName)) {
            if (!StringUtils.hasText(image)) {
                throw new DockerOperationException("image is required to create container for restart");
            }
            createContainer(containerName, instanceId, image.trim(), gatewayHostPort);
            try {
                runDockerChecked(List.of(properties.getCommand(), "start", containerName), "failed to start container");
                enforceRuntimeConfigPolicy(containerName, instanceId, gatewayHostPort);
                waitForGatewayReady(containerName, resolveGatewayHostPortForProbe(containerName, gatewayHostPort));
            } catch (DockerOperationException ex) {
                removeContainerQuietly(containerName);
                throw ex;
            }
            return "Container created and started: " + containerName;
        }

        runDockerChecked(List.of(properties.getCommand(), "restart", containerName), "failed to restart container");
        enforceRuntimeConfigPolicy(containerName, instanceId, gatewayHostPort);
        waitForGatewayReady(containerName, resolveGatewayHostPortForProbe(containerName, gatewayHostPort));
        return "Container restarted: " + containerName;
    }

    private String deleteInstance(UUID instanceId) {
        String containerName = containerName(instanceId);
        if (!containerExists(containerName)) {
            return "Container not found, delete skipped: " + containerName;
        }

        if (containerRunning(containerName)) {
            runDockerChecked(
                    List.of(
                            properties.getCommand(),
                            "stop",
                            "-t",
                            String.valueOf(properties.getStopTimeoutSeconds()),
                            containerName
                    ),
                    "failed to stop container before delete"
            );
        }

        runDockerChecked(List.of(properties.getCommand(), "rm", containerName), "failed to delete container");
        return "Container deleted: " + containerName;
    }

    private void createContainer(String containerName, UUID instanceId, String image, Integer gatewayHostPort) {
        int hostPort = gatewayHostPort != null ? gatewayHostPort : properties.getGatewayHostPort();
        if (hostPort <= 0 || hostPort > 65535) {
            throw new DockerOperationException("invalid gateway host port: " + hostPort);
        }

        List<String> command = new ArrayList<>();
        command.add(properties.getCommand());
        command.add("create");
        command.add("--name");
        command.add(containerName);
        command.add("--label");
        command.add("fun.ai.instance-id=" + instanceId);
        command.add("-p");
        command.add(hostPort + ":" + properties.getGatewayContainerPort());
        command.add("-e");
        command.add("ZEROCLAW_GATEWAY_PORT=" + properties.getGatewayContainerPort());
        command.add("-e");
        command.add("ZEROCLAW_ALLOW_PUBLIC_BIND=" + properties.isAllowPublicBind());
        command.add("-e");
        command.add("ZEROCLAW_REQUIRE_PAIRING=" + properties.isRequirePairing());
        if (StringUtils.hasText(properties.getApiKey())) {
            command.add("-e");
            command.add("API_KEY=" + properties.getApiKey().trim());
        }
        String openSkillsDir = resolveOpenSkillsDir(instanceId, hostPort);
        if (StringUtils.hasText(openSkillsDir)) {
            command.add("-e");
            command.add("ZEROCLAW_OPEN_SKILLS_ENABLED=true");
            command.add("-e");
            command.add("ZEROCLAW_OPEN_SKILLS_DIR=" + openSkillsDir);
            command.add("-e");
            command.add("ZEROCLAW_SKILLS_PROMPT_MODE=full");
        }
        if (StringUtils.hasText(properties.getRestartPolicy())) {
            command.add("--restart");
            command.add(properties.getRestartPolicy().trim());
        }
        appendAgentWorkspaceMountArgs(command, instanceId, hostPort);
        command.add(image);
        command.add("gateway");
        command.add("--host");
        command.add(properties.getGatewayHost());
        command.add("--port");
        command.add(String.valueOf(properties.getGatewayContainerPort()));
        Set<String> supportedOptions = detectGatewayOptions(image);
        appendGatewaySecurityArgs(command, supportedOptions);
        appendGatewayPathRoutingArgs(command, supportedOptions, instanceId, hostPort);
        runDockerChecked(command, "failed to create container");
    }

    private void appendAgentWorkspaceMountArgs(List<String> command, UUID instanceId, int gatewayHostPort) {
        if (!properties.isAgentWorkspaceMountEnabled()) {
            return;
        }
        String hostPath = resolveTemplate(properties.getAgentWorkspaceHostPathTemplate(), instanceId, gatewayHostPort);
        String containerPath = resolveTemplate(properties.getAgentWorkspaceContainerPathTemplate(), instanceId, gatewayHostPort);
        if (!StringUtils.hasText(hostPath) || !StringUtils.hasText(containerPath)) {
            log.warn("skip agent workspace mount because hostPath or containerPath is blank");
            return;
        }
        String mountExpr = properties.isAgentWorkspaceMountReadOnly()
                ? hostPath.trim() + ":" + containerPath.trim() + ":ro"
                : hostPath.trim() + ":" + containerPath.trim();
        command.add("-v");
        command.add(mountExpr);
    }

    private void appendGatewaySecurityArgs(List<String> command, Set<String> supportedOptions) {
        appendGatewayOption(
                command,
                supportedOptions,
                properties.getGatewayRequirePairingOption(),
                String.valueOf(properties.isRequirePairing())
        );
    }

    private void appendGatewayPathRoutingArgs(List<String> command,
                                              Set<String> supportedOptions,
                                              UUID instanceId,
                                              int gatewayHostPort) {
        if (!properties.isUiPathRoutingEnabled()) {
            return;
        }
        appendGatewayOption(
                command,
                supportedOptions,
                properties.getGatewayBasePathOption(),
                resolveTemplate(properties.getGatewayBasePathTemplate(), instanceId, gatewayHostPort)
        );
        appendGatewayOption(
                command,
                supportedOptions,
                properties.getGatewayPublicUrlOption(),
                resolveTemplate(properties.getGatewayPublicUrlTemplate(), instanceId, gatewayHostPort)
        );
        appendGatewayOption(
                command,
                supportedOptions,
                properties.getGatewayConfigDirOption(),
                resolveTemplate(properties.getGatewayConfigDirTemplate(), instanceId, gatewayHostPort)
        );
    }

    private void appendGatewayOption(List<String> command, Set<String> supportedOptions, String option, String value) {
        if (!StringUtils.hasText(option) || !StringUtils.hasText(value)) {
            return;
        }
        String normalizedOption = option.trim();
        if (!supportedOptions.contains(normalizedOption)) {
            log.info("skip unsupported zeroclaw gateway option: {}", normalizedOption);
            return;
        }
        command.add(normalizedOption);
        command.add(value);
    }

    private String resolveTemplate(String template, UUID instanceId, int gatewayHostPort) {
        if (!StringUtils.hasText(template)) {
            return null;
        }
        return template.trim()
                .replace("{instanceId}", instanceId.toString())
                .replace("{gatewayHostPort}", String.valueOf(gatewayHostPort))
                .replace("{gatewayContainerPort}", String.valueOf(properties.getGatewayContainerPort()));
    }

    private Set<String> detectGatewayOptions(String image) {
        return gatewayOptionsByImage.computeIfAbsent(image, this::fetchGatewayOptions);
    }

    private Set<String> fetchGatewayOptions(String image) {
        List<String> command = List.of(properties.getCommand(), "run", "--rm", image, "gateway", "--help");
        CommandResult result = runDocker(command);
        if (result.exitCode != 0) {
            log.warn("failed to inspect zeroclaw gateway help for image {}: {}", image, result.output);
            return Set.of();
        }

        Set<String> options = new HashSet<>();
        Matcher matcher = LONG_OPTION_PATTERN.matcher(result.output);
        while (matcher.find()) {
            options.add(matcher.group());
        }
        if (options.isEmpty()) {
            log.warn("no long options detected from zeroclaw gateway help for image {}", image);
            return Set.of();
        }
        log.info("detected zeroclaw gateway options for image {}: {}", image, options);
        return Set.copyOf(options);
    }

    private boolean containerExists(String containerName) {
        CommandResult result = runDocker(List.of(properties.getCommand(), "container", "inspect", containerName));
        return result.exitCode == 0;
    }

    private boolean containerRunning(String containerName) {
        CommandResult result = runDocker(List.of(properties.getCommand(), "inspect", "-f", "{{.State.Running}}", containerName));
        if (result.exitCode != 0) {
            return false;
        }
        return "true".equalsIgnoreCase(result.output.trim());
    }

    private void runDockerChecked(List<String> command, String messagePrefix) {
        CommandResult result = runDocker(command);
        if (result.exitCode == 0) {
            return;
        }
        String details = result.output.isBlank() ? "exit code " + result.exitCode : result.output.trim();
        throw new DockerOperationException(messagePrefix + ": " + details);
    }

    private void removeContainerQuietly(String containerName) {
        runDocker(List.of(properties.getCommand(), "rm", containerName));
    }

    private String resolveOpenSkillsDir(UUID instanceId, int gatewayHostPort) {
        String containerPath = resolveTemplate(properties.getAgentWorkspaceContainerPathTemplate(), instanceId, gatewayHostPort);
        if (!StringUtils.hasText(containerPath)) {
            return null;
        }
        String trimmed = containerPath.trim();
        if (trimmed.endsWith("/")) {
            return trimmed + "skills";
        }
        return trimmed + "/skills";
    }

    private void enforceRuntimeConfigPolicy(String containerName, UUID instanceId, Integer gatewayHostPort) {
        String openSkillsDir = resolveOpenSkillsDir(
                instanceId,
                gatewayHostPort != null ? gatewayHostPort : properties.getGatewayHostPort()
        );
        boolean enforceGatewayPairing = !properties.isRequirePairing();
        boolean enforceSkills = StringUtils.hasText(openSkillsDir);
        if (!enforceGatewayPairing && !enforceSkills) {
            return;
        }

        // Best-effort enforcement without relying on container sed/awk availability.
        for (int attempt = 1; attempt <= 30; attempt++) {
            CommandResult read = runDocker(List.of(
                    properties.getCommand(),
                    "exec",
                    containerName,
                    "/bin/busybox",
                    "cat",
                    "/data/zeroclaw/config.toml"
            ));
            if (read.exitCode != 0 || read.output == null) {
                if (attempt == 30) {
                    String details = read.output == null ? "" : read.output.trim();
                    log.warn("skip pairing enforcement for {}: unable to read config.toml ({})", containerName, details);
                } else {
                    sleepSilently(1000L);
                }
                continue;
            }

            String originalConfig = read.output;
            String rewrittenConfig = rewriteRuntimeSettings(originalConfig, openSkillsDir);
            if (rewrittenConfig.equals(originalConfig)) {
                return;
            }

            CommandResult write = runDockerWithInput(
                    List.of(
                            properties.getCommand(),
                            "exec",
                            "-i",
                            containerName,
                            "/bin/busybox",
                            "dd",
                            "of=/data/zeroclaw/config.toml",
                            "conv=fsync"
                    ),
                    rewrittenConfig.getBytes(StandardCharsets.UTF_8)
            );
            if (write.exitCode != 0) {
                if (attempt == 30) {
                    log.warn("skip pairing enforcement for {}: failed to write config.toml ({})", containerName, write.output.trim());
                } else {
                    sleepSilently(1000L);
                }
                continue;
            }

            CommandResult restart = runDocker(List.of(properties.getCommand(), "restart", containerName));
            if (restart.exitCode != 0) {
                log.warn("container restart after runtime config enforcement failed for {}: {}", containerName, restart.output.trim());
                return;
            }
            log.info("enforced runtime config policy in {}", containerName);
            return;
        }
    }

    private String rewriteRuntimeSettings(String config, String openSkillsDir) {
        String rewritten = rewriteGatewaySettings(config);
        return rewriteSkillsSettings(rewritten, openSkillsDir);
    }

    private String rewriteGatewaySettings(String config) {
        String original = config == null ? "" : config;
        if (properties.isRequirePairing()) {
            return original;
        }
        String targetHost = StringUtils.hasText(properties.getGatewayHost())
                ? properties.getGatewayHost().trim()
                : "0.0.0.0";
        String targetRequirePairing = "require_pairing = false";
        String targetAllowPublicBind = "allow_public_bind = " + properties.isAllowPublicBind();
        String targetHostLine = "host = \"" + targetHost + "\"";
        if (original.isBlank()) {
            return "[gateway]\n"
                    + targetHostLine + "\n"
                    + targetRequirePairing + "\n"
                    + targetAllowPublicBind + "\n";
        }

        Matcher gatewayMatcher = GATEWAY_SECTION_PATTERN.matcher(original);
        if (gatewayMatcher.find()) {
            int sectionStart = gatewayMatcher.end();
            int sectionEnd = original.length();
            Matcher sectionHeaderMatcher = SECTION_HEADER_PATTERN.matcher(original);
            while (sectionHeaderMatcher.find(sectionStart)) {
                if (sectionHeaderMatcher.start() > sectionStart) {
                    sectionEnd = sectionHeaderMatcher.start();
                    break;
                }
            }

            String gatewaySection = original.substring(sectionStart, sectionEnd);
            String rewrittenSection = setUniqueSettingLine(gatewaySection, REQUIRE_PAIRING_PATTERN, targetRequirePairing);
            rewrittenSection = setUniqueSettingLine(rewrittenSection, GATEWAY_HOST_PATTERN, targetHostLine);
            rewrittenSection = setUniqueSettingLine(rewrittenSection, ALLOW_PUBLIC_BIND_PATTERN, targetAllowPublicBind);
            if (rewrittenSection.equals(gatewaySection)) {
                return original;
            }
            return original.substring(0, sectionStart) + rewrittenSection + original.substring(sectionEnd);
        }

        String suffix = original.endsWith("\n") ? "" : "\n";
        return original + suffix + "[gateway]\n"
                + targetHostLine + "\n"
                + targetRequirePairing + "\n"
                + targetAllowPublicBind + "\n";
    }

    private String rewriteSkillsSettings(String config, String openSkillsDir) {
        String original = config == null ? "" : config;
        if (!StringUtils.hasText(openSkillsDir)) {
            return original;
        }
        String targetOpenSkillsEnabled = "open_skills_enabled = true";
        String targetOpenSkillsDir = "open_skills_dir = \"" + escapeTomlString(openSkillsDir.trim()) + "\"";
        String targetPromptInjectionMode = "prompt_injection_mode = \"full\"";

        if (original.isBlank()) {
            return "[skills]\n"
                    + targetOpenSkillsEnabled + "\n"
                    + targetOpenSkillsDir + "\n"
                    + targetPromptInjectionMode + "\n";
        }

        Matcher skillsMatcher = SKILLS_SECTION_PATTERN.matcher(original);
        if (skillsMatcher.find()) {
            int sectionStart = skillsMatcher.end();
            int sectionEnd = original.length();
            Matcher sectionHeaderMatcher = SECTION_HEADER_PATTERN.matcher(original);
            while (sectionHeaderMatcher.find(sectionStart)) {
                if (sectionHeaderMatcher.start() > sectionStart) {
                    sectionEnd = sectionHeaderMatcher.start();
                    break;
                }
            }

            String skillsSection = original.substring(sectionStart, sectionEnd);
            String rewrittenSection = setUniqueSettingLine(skillsSection, OPEN_SKILLS_ENABLED_PATTERN, targetOpenSkillsEnabled);
            rewrittenSection = setUniqueSettingLine(rewrittenSection, OPEN_SKILLS_DIR_PATTERN, targetOpenSkillsDir);
            rewrittenSection = setUniqueSettingLine(rewrittenSection, PROMPT_INJECTION_MODE_PATTERN, targetPromptInjectionMode);
            if (rewrittenSection.equals(skillsSection)) {
                return original;
            }
            return original.substring(0, sectionStart) + rewrittenSection + original.substring(sectionEnd);
        }

        String suffix = original.endsWith("\n") ? "" : "\n";
        return original + suffix + "[skills]\n"
                + targetOpenSkillsEnabled + "\n"
                + targetOpenSkillsDir + "\n"
                + targetPromptInjectionMode + "\n";
    }

    private String setUniqueSettingLine(String section, Pattern linePattern, String replacementLine) {
        String[] lines = section.split("\\R", -1);
        StringBuilder builder = new StringBuilder(section.length() + replacementLine.length() + 8);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (linePattern.matcher(line).matches()) {
                continue;
            }
            builder.append(line);
            if (i < lines.length - 1) {
                builder.append('\n');
            }
        }
        String normalized = builder.toString();
        if (!normalized.isEmpty() && !normalized.endsWith("\n")) {
            normalized += "\n";
        }
        return normalized + replacementLine + "\n";
    }

    private String escapeTomlString(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private void sleepSilently(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private Integer resolveGatewayHostPortForProbe(String containerName, Integer requestedPort) {
        if (requestedPort != null && requestedPort > 0 && requestedPort <= 65535) {
            return requestedPort;
        }
        CommandResult result = runDocker(List.of(
                properties.getCommand(),
                "port",
                containerName,
                properties.getGatewayContainerPort() + "/tcp"
        ));
        if (result.exitCode != 0 || !StringUtils.hasText(result.output)) {
            return null;
        }
        String[] lines = result.output.split("\\R");
        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (!StringUtils.hasText(line)) {
                continue;
            }
            int lastColon = line.lastIndexOf(':');
            if (lastColon < 0 || lastColon == line.length() - 1) {
                continue;
            }
            String maybePort = line.substring(lastColon + 1).trim();
            try {
                int parsed = Integer.parseInt(maybePort);
                if (parsed > 0 && parsed <= 65535) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // Continue parsing other lines.
            }
        }
        return null;
    }

    private void waitForGatewayReady(String containerName, Integer gatewayHostPort) {
        if (gatewayHostPort == null) {
            log.warn("skip gateway readiness check for {}: gateway host port is unknown", containerName);
            return;
        }

        long timeoutSeconds = properties.getGatewayReadyTimeoutSeconds() > 0
                ? properties.getGatewayReadyTimeoutSeconds()
                : 30L;
        long intervalMillis = properties.getGatewayReadyProbeIntervalMillis() > 0
                ? properties.getGatewayReadyProbeIntervalMillis()
                : 500L;
        String readyHost = StringUtils.hasText(properties.getGatewayReadyHost())
                ? properties.getGatewayReadyHost().trim()
                : "127.0.0.1";
        String readyPath = StringUtils.hasText(properties.getGatewayReadyPath())
                ? properties.getGatewayReadyPath().trim()
                : "/health";
        if (!readyPath.startsWith("/")) {
            readyPath = "/" + readyPath;
        }

        URI uri = URI.create("http://" + readyHost + ":" + gatewayHostPort + readyPath);
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        String lastError = "unknown";

        while (System.currentTimeMillis() <= deadline) {
            if (!containerRunning(containerName)) {
                lastError = "container is not running";
                sleepSilently(intervalMillis);
                continue;
            }

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();
                HttpResponse<Void> response = healthProbeClient.send(request, HttpResponse.BodyHandlers.discarding());
                int code = response.statusCode();
                if (code >= 100) {
                    return;
                }
                lastError = "http status " + code;
            } catch (IOException ex) {
                lastError = ex.getMessage();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new DockerOperationException("gateway readiness check interrupted");
            }
            sleepSilently(intervalMillis);
        }

        log.warn("gateway readiness check timed out after {}s for {} at {} (last error: {})",
                timeoutSeconds,
                containerName,
                uri,
                lastError);
    }

    private CommandResult runDocker(List<String> command) {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            process.getOutputStream().close();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            process.getInputStream().transferTo(output);
            boolean finished = process.waitFor(properties.getCommandTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new DockerOperationException("docker command timed out");
            }
            return new CommandResult(process.exitValue(), output.toString(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new DockerOperationException("failed to execute docker command: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new DockerOperationException("docker command interrupted");
        }
    }

    private CommandResult runDockerWithInput(List<String> command, byte[] stdinBytes) {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            if (stdinBytes != null && stdinBytes.length > 0) {
                try (OutputStream os = process.getOutputStream()) {
                    os.write(stdinBytes);
                    os.flush();
                }
            } else {
                process.getOutputStream().close();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            process.getInputStream().transferTo(output);
            boolean finished = process.waitFor(properties.getCommandTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new DockerOperationException("docker command timed out");
            }
            return new CommandResult(process.exitValue(), output.toString(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new DockerOperationException("failed to execute docker command: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new DockerOperationException("docker command interrupted");
        }
    }

    private String resolveImage(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        Object rawImage = payload.get("image");
        if (rawImage instanceof String image && StringUtils.hasText(image)) {
            return image;
        }
        return null;
    }

    private Integer resolveGatewayHostPort(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        Object rawGatewayHostPort = payload.get("gatewayHostPort");
        if (rawGatewayHostPort == null) {
            return null;
        }
        if (rawGatewayHostPort instanceof Number numberValue) {
            return numberValue.intValue();
        }
        if (rawGatewayHostPort instanceof String stringValue && StringUtils.hasText(stringValue)) {
            try {
                return Integer.parseInt(stringValue.trim());
            } catch (NumberFormatException ex) {
                throw new DockerOperationException("invalid gateway host port: " + stringValue);
            }
        }
        throw new DockerOperationException("invalid gateway host port payload");
    }

    private String containerName(UUID instanceId) {
        return properties.getContainerPrefix() + "-" + instanceId;
    }

    private record CommandResult(int exitCode, String output) {
    }
}
