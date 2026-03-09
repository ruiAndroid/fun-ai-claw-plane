package com.fun.ai.claw.plane.service;

import com.fun.ai.claw.plane.config.DockerRuntimeProperties;
import com.fun.ai.claw.plane.model.AgentDescriptorResponse;
import com.fun.ai.claw.plane.model.AgentSystemPromptResponse;
import com.fun.ai.claw.plane.model.SkillDescriptorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RuntimeIntrospectionService {

    private static final String DEFAULT_CONFIG_PATH = "/data/zeroclaw/config.toml";
    private static final String DEFAULT_WORKSPACE_SKILLS_DIR = "/zeroclaw-data/workspace/skills";

    private static final Pattern AGENT_BLOCK_PATTERN = Pattern.compile(
            "(?ms)^\\s*\\[\\s*agents\\s*\\.\\s*(?:\"((?:\\\\.|[^\"\\\\])*)\"|'((?:\\\\.|[^'\\\\])*)'|([A-Za-z0-9_-]+))\\s*\\]\\s*(.*?)(?=^\\s*\\[[^\\]]+\\]|\\z)"
    );
    private static final Pattern CONFIG_DIR_ARG_PATTERN = Pattern.compile("(?:^|\\s)--config-dir(?:=|\\s+)(\\S+)");
    private static final Pattern ZEROCLAW_CONFIG_DIR_ENV_PATTERN = Pattern.compile("(?m)^ZEROCLAW_CONFIG_DIR=(.+)$");
    private static final Pattern PROVIDER_PATTERN = Pattern.compile("(?m)^\\s*provider\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern MODEL_PATTERN = Pattern.compile("(?m)^\\s*model\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern SYSTEM_PROMPT_PATTERN = Pattern.compile("(?m)^\\s*system_prompt\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern SYSTEM_PROMPT_LITERAL_PATTERN = Pattern.compile("(?m)^\\s*system_prompt\\s*=\\s*'([^'\\r\\n]*)'\\s*$");
    private static final Pattern SYSTEM_PROMPT_MULTILINE_BASIC_PATTERN = Pattern.compile("(?ms)^\\s*system_prompt\\s*=\\s*\"\"\"(.*?)\"\"\"\\s*$");
    private static final Pattern SYSTEM_PROMPT_MULTILINE_LITERAL_PATTERN = Pattern.compile("(?ms)^\\s*system_prompt\\s*=\\s*'''(.*?)'''\\s*$");
    private static final Pattern AGENTIC_PATTERN = Pattern.compile("(?m)^\\s*agentic\\s*=\\s*(true|false)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALLOWED_TOOLS_PATTERN = Pattern.compile("(?ms)^\\s*allowed_tools\\s*=\\s*\\[(.*?)]\\s*$");
    private static final Pattern ARRAY_QUOTED_STRING_PATTERN = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"|'((?:\\\\.|[^'\\\\])*)'");
    private static final Pattern SKILLS_BLOCK_PATTERN = Pattern.compile(
            "(?ms)^\\s*\\[\\s*skills\\s*]\\s*(.*?)(?=^\\s*\\[[^\\]]+\\]|\\z)"
    );
    private static final Pattern OPEN_SKILLS_ENABLED_PATTERN = Pattern.compile(
            "(?m)^\\s*open_skills_enabled\\s*=\\s*(true|false)\\s*$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern OPEN_SKILLS_DIR_PATTERN = Pattern.compile(
            "(?m)^\\s*open_skills_dir\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$"
    );

    private final DockerRuntimeProperties properties;
    private final Duration commandTimeout;

    public RuntimeIntrospectionService(DockerRuntimeProperties properties) {
        this.properties = properties;
        long timeoutSeconds = properties.getCommandTimeoutSeconds() > 0 ? properties.getCommandTimeoutSeconds() : 120;
        this.commandTimeout = Duration.ofSeconds(timeoutSeconds);
    }

    public List<AgentDescriptorResponse> listAgents(UUID instanceId) {
        List<LoadedConfig> loadedConfigs = readConfigs(instanceId);
        if (loadedConfigs.isEmpty()) {
            return List.of();
        }

        List<AgentDescriptorResponse> selectedAgents = List.of();
        for (LoadedConfig candidate : loadedConfigs) {
            List<AgentDescriptorResponse> candidateAgents = parseAgents(candidate.text(), candidate.path());
            if (selectedAgents.isEmpty()) {
                selectedAgents = candidateAgents;
            }
            if (!candidateAgents.isEmpty()) {
                selectedAgents = candidateAgents;
                break;
            }
        }

        return selectedAgents.stream()
                .sorted(Comparator.comparing(AgentDescriptorResponse::id))
                .toList();
    }

    public AgentSystemPromptResponse getAgentSystemPrompt(UUID instanceId, String agentId) {
        String normalizedAgentId = normalizeAgentId(agentId);
        for (LoadedConfig loadedConfig : readConfigs(instanceId)) {
            AgentBlock agentBlock = findAgentBlockOrNull(loadedConfig.text(), normalizedAgentId);
            if (agentBlock == null) {
                continue;
            }
            return new AgentSystemPromptResponse(
                    instanceId,
                    normalizedAgentId,
                    findSystemPromptValue(agentBlock.block()),
                    loadedConfig.path()
            );
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "agent not found");
    }

    public List<SkillDescriptorResponse> listSkills(UUID instanceId) {
        LoadedConfig loadedConfig = readConfig(instanceId);
        if (!StringUtils.hasText(loadedConfig.text())) {
            return List.of();
        }

        SkillsConfig skillsConfig = parseSkillsConfig(loadedConfig.text());
        List<String> skillDirs = resolveSkillDirs(instanceId, skillsConfig);
        if (skillDirs.isEmpty()) {
            return List.of();
        }

        String containerName = containerName(instanceId);
        LinkedHashMap<String, SkillDescriptorResponse> skillsById = new LinkedHashMap<>();
        for (String skillDir : skillDirs) {
            List<String> skillFiles = listSkillFiles(containerName, skillDir);
            for (String skillFilePath : skillFiles) {
                String skillId = resolveSkillId(skillFilePath);
                if (skillsById.containsKey(skillId)) {
                    continue;
                }
                CommandResult skillResult = runCommand(
                        dockerCommand(),
                        "exec",
                        containerName,
                        "/bin/busybox",
                        "cat",
                        skillFilePath
                );
                if (skillResult.exitCode != 0) {
                    continue;
                }
                skillsById.put(skillId, new SkillDescriptorResponse(
                        skillId,
                        skillFilePath,
                        normalizeText(skillResult.output)
                ));
            }
        }

        return skillsById.values().stream()
                .sorted(Comparator.comparing(SkillDescriptorResponse::id))
                .toList();
    }

    private LoadedConfig readConfig(UUID instanceId) {
        String containerName = containerName(instanceId);
        List<String> candidatePaths = resolveConfigPathCandidates(containerName);
        CommandResult lastFailure = null;
        for (String candidatePath : candidatePaths) {
            CommandResult configResult = runCommand(
                    dockerCommand(),
                    "exec",
                    containerName,
                    "/bin/busybox",
                    "cat",
                    candidatePath
            );
            if (configResult.exitCode == 0) {
                return new LoadedConfig(candidatePath, configResult.output);
            }
            lastFailure = configResult;
        }
        throw failedToReadInstanceConfig(lastFailure);
    }

    private List<LoadedConfig> readConfigs(UUID instanceId) {
        String containerName = containerName(instanceId);
        List<String> candidatePaths = resolveConfigPathCandidates(containerName);
        List<LoadedConfig> loadedConfigs = new ArrayList<>();
        CommandResult lastFailure = null;
        for (String candidatePath : candidatePaths) {
            CommandResult result = runCommand(
                    dockerCommand(),
                    "exec",
                    containerName,
                    "/bin/busybox",
                    "cat",
                    candidatePath
            );
            if (result.exitCode == 0) {
                loadedConfigs.add(new LoadedConfig(candidatePath, result.output));
            } else {
                lastFailure = result;
            }
        }
        if (!loadedConfigs.isEmpty()) {
            return loadedConfigs;
        }
        throw failedToReadInstanceConfig(lastFailure);
    }

    private ResponseStatusException failedToReadInstanceConfig(CommandResult lastFailure) {
        String details = lastFailure != null && StringUtils.hasText(lastFailure.output)
                ? ": " + lastFailure.output.trim()
                : "";
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "failed to read instance config" + details);
    }

    private List<String> resolveConfigPathCandidates(String containerName) {
        List<String> candidates = new ArrayList<>();
        String inspectedArgsPath = inspectContainerConfigPathFromArgs(containerName);
        if (StringUtils.hasText(inspectedArgsPath)) {
            candidates.add(inspectedArgsPath);
        }
        String inspectedEnvPath = inspectContainerConfigPathFromEnv(containerName);
        if (StringUtils.hasText(inspectedEnvPath) && !candidates.contains(inspectedEnvPath)) {
            candidates.add(inspectedEnvPath);
        }
        if (!candidates.contains(DEFAULT_CONFIG_PATH)) {
            candidates.add(DEFAULT_CONFIG_PATH);
        }
        return candidates;
    }

    private String inspectContainerConfigPathFromArgs(String containerName) {
        CommandResult inspect = runCommand(
                dockerCommand(),
                "inspect",
                "-f",
                "{{range .Args}}{{.}} {{end}}",
                containerName
        );
        if (inspect.exitCode != 0 || !StringUtils.hasText(inspect.output)) {
            return null;
        }
        Matcher matcher = CONFIG_DIR_ARG_PATTERN.matcher(inspect.output);
        if (!matcher.find()) {
            return null;
        }
        String configDir = matcher.group(1) == null ? "" : matcher.group(1).trim();
        if (!StringUtils.hasText(configDir)) {
            return null;
        }
        return configDir.endsWith("/") ? configDir + "config.toml" : configDir + "/config.toml";
    }

    private String inspectContainerConfigPathFromEnv(String containerName) {
        CommandResult inspect = runCommand(
                dockerCommand(),
                "inspect",
                "-f",
                "{{range .Config.Env}}{{println .}}{{end}}",
                containerName
        );
        if (inspect.exitCode != 0 || !StringUtils.hasText(inspect.output)) {
            return null;
        }
        Matcher matcher = ZEROCLAW_CONFIG_DIR_ENV_PATTERN.matcher(inspect.output);
        if (!matcher.find()) {
            return null;
        }
        String configDir = matcher.group(1) == null ? "" : matcher.group(1).trim();
        if (!StringUtils.hasText(configDir)) {
            return null;
        }
        return configDir.endsWith("/") ? configDir + "config.toml" : configDir + "/config.toml";
    }

    private List<AgentDescriptorResponse> parseAgents(String configText, String resolvedConfigPath) {
        List<AgentDescriptorResponse> agents = new ArrayList<>();
        Matcher blockMatcher = AGENT_BLOCK_PATTERN.matcher(configText);
        while (blockMatcher.find()) {
            String rawId = firstNonBlank(
                    blockMatcher.group(1),
                    blockMatcher.group(2),
                    blockMatcher.group(3)
            );
            String id = unescapeTomlString(rawId).trim();
            if (!StringUtils.hasText(id)) {
                continue;
            }
            String block = blockMatcher.group(4);
            agents.add(new AgentDescriptorResponse(
                    id,
                    findStringValue(PROVIDER_PATTERN, block),
                    findStringValue(MODEL_PATTERN, block),
                    findBooleanValue(AGENTIC_PATTERN, block),
                    findStringArrayValue(ALLOWED_TOOLS_PATTERN, block),
                    findSystemPromptValue(block),
                    resolvedConfigPath
            ));
        }
        return agents;
    }

    private AgentBlock findAgentBlockOrNull(String configText, String targetAgentId) {
        Matcher blockMatcher = AGENT_BLOCK_PATTERN.matcher(configText);
        while (blockMatcher.find()) {
            String rawId = firstNonBlank(
                    blockMatcher.group(1),
                    blockMatcher.group(2),
                    blockMatcher.group(3)
            );
            String id = unescapeTomlString(rawId).trim();
            if (StringUtils.hasText(id) && id.equals(targetAgentId)) {
                return new AgentBlock(id, blockMatcher.group(4));
            }
        }
        return null;
    }

    private String normalizeAgentId(String agentId) {
        if (!StringUtils.hasText(agentId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agentId is required");
        }
        return agentId.trim();
    }

    private String findSystemPromptValue(String block) {
        String basicValue = findStringValue(SYSTEM_PROMPT_PATTERN, block);
        if (basicValue != null) {
            return basicValue;
        }

        Matcher literalMatcher = SYSTEM_PROMPT_LITERAL_PATTERN.matcher(block);
        if (literalMatcher.find()) {
            return literalMatcher.group(1);
        }

        Matcher multilineBasicMatcher = SYSTEM_PROMPT_MULTILINE_BASIC_PATTERN.matcher(block);
        if (multilineBasicMatcher.find()) {
            return unescapeTomlString(normalizeTomlMultilineBody(multilineBasicMatcher.group(1)));
        }

        Matcher multilineLiteralMatcher = SYSTEM_PROMPT_MULTILINE_LITERAL_PATTERN.matcher(block);
        if (multilineLiteralMatcher.find()) {
            return normalizeTomlMultilineBody(multilineLiteralMatcher.group(1));
        }

        return null;
    }

    private SkillsConfig parseSkillsConfig(String configText) {
        Matcher blockMatcher = SKILLS_BLOCK_PATTERN.matcher(configText);
        if (!blockMatcher.find()) {
            return new SkillsConfig(false, null);
        }
        String block = blockMatcher.group(1);
        boolean openSkillsEnabled = false;
        Matcher enabledMatcher = OPEN_SKILLS_ENABLED_PATTERN.matcher(block);
        if (enabledMatcher.find()) {
            openSkillsEnabled = Boolean.parseBoolean(enabledMatcher.group(1).trim());
        }

        String openSkillsDir = null;
        Matcher dirMatcher = OPEN_SKILLS_DIR_PATTERN.matcher(block);
        if (dirMatcher.find()) {
            openSkillsDir = unescapeTomlString(dirMatcher.group(1)).trim();
        }
        return new SkillsConfig(openSkillsEnabled, openSkillsDir);
    }

    private List<String> resolveSkillDirs(UUID instanceId, SkillsConfig skillsConfig) {
        LinkedHashSet<String> orderedDirs = new LinkedHashSet<>();
        if (skillsConfig.openSkillsEnabled() && StringUtils.hasText(skillsConfig.openSkillsDir())) {
            orderedDirs.add(skillsConfig.openSkillsDir().trim());
        }
        orderedDirs.add(resolveWorkspaceSkillsDir());
        String agentWorkspacePath = resolveTemplate(
                properties.getAgentWorkspaceContainerPathTemplate(),
                instanceId,
                properties.getGatewayHostPort()
        );
        if (StringUtils.hasText(agentWorkspacePath)) {
            orderedDirs.add(joinContainerPath(agentWorkspacePath, "skills"));
        }
        return orderedDirs.stream()
                .filter(StringUtils::hasText)
                .toList();
    }

    private String resolveWorkspaceSkillsDir() {
        String workspaceAgentsFilePath = properties.getWorkspaceAgentsFilePath();
        if (!StringUtils.hasText(workspaceAgentsFilePath)) {
            return DEFAULT_WORKSPACE_SKILLS_DIR;
        }
        String normalized = workspaceAgentsFilePath.trim();
        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash <= 0) {
            return DEFAULT_WORKSPACE_SKILLS_DIR;
        }
        return normalized.substring(0, lastSlash) + "/skills";
    }

    private List<String> listSkillFiles(String containerName, String skillDir) {
        CommandResult listResult = runCommand(
                dockerCommand(),
                "exec",
                containerName,
                "/bin/busybox",
                "find",
                skillDir,
                "-mindepth",
                "2",
                "-maxdepth",
                "2",
                "-type",
                "f",
                "-name",
                "SKILL.md"
        );

        if (listResult.exitCode != 0) {
            String outputLower = listResult.output == null ? "" : listResult.output.toLowerCase(Locale.ROOT);
            if (outputLower.contains("no such file")) {
                return List.of();
            }
            String details = StringUtils.hasText(listResult.output) ? ": " + listResult.output.trim() : "";
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "failed to list skills" + details);
        }

        return listResult.output == null
                ? List.of()
                : listResult.output.lines()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private String resolveSkillId(String skillFilePath) {
        String normalized = skillFilePath == null ? "" : skillFilePath.replace("\\", "/");
        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash <= 0) {
            return normalized;
        }
        int parentSlash = normalized.lastIndexOf('/', lastSlash - 1);
        if (parentSlash >= 0 && parentSlash < lastSlash) {
            return normalized.substring(parentSlash + 1, lastSlash);
        }
        return normalized.substring(0, lastSlash);
    }

    private String normalizeText(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").trim();
    }

    private String normalizeTomlMultilineBody(String value) {
        if (value == null) {
            return "";
        }
        if (value.startsWith("\r\n")) {
            return value.substring(2);
        }
        if (value.startsWith("\n")) {
            return value.substring(1);
        }
        return value;
    }

    private String firstNonBlank(String... values) {
        if (values == null || values.length == 0) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private String findStringValue(Pattern pattern, String block) {
        Matcher matcher = pattern.matcher(block);
        if (!matcher.find()) {
            return null;
        }
        return unescapeTomlString(matcher.group(1)).trim();
    }

    private Boolean findBooleanValue(Pattern pattern, String block) {
        Matcher matcher = pattern.matcher(block);
        if (!matcher.find()) {
            return null;
        }
        return Boolean.parseBoolean(matcher.group(1).trim());
    }

    private List<String> findStringArrayValue(Pattern pattern, String block) {
        Matcher matcher = pattern.matcher(block);
        if (!matcher.find()) {
            return List.of();
        }
        String arrayBody = matcher.group(1);
        Matcher valueMatcher = ARRAY_QUOTED_STRING_PATTERN.matcher(arrayBody);
        List<String> values = new ArrayList<>();
        while (valueMatcher.find()) {
            String raw = StringUtils.hasText(valueMatcher.group(1))
                    ? valueMatcher.group(1)
                    : valueMatcher.group(2);
            String value = unescapeTomlString(raw).trim();
            if (StringUtils.hasText(value)) {
                values.add(value);
            }
        }
        return values;
    }

    private String unescapeTomlString(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch != '\\' || index + 1 >= value.length()) {
                builder.append(ch);
                continue;
            }
            char next = value.charAt(++index);
            switch (next) {
                case 'n' -> builder.append('\n');
                case 'r' -> builder.append('\r');
                case 't' -> builder.append('\t');
                case '"' -> builder.append('"');
                case '\\' -> builder.append('\\');
                default -> builder.append(next);
            }
        }
        return builder.toString();
    }

    private String containerName(UUID instanceId) {
        String prefix = StringUtils.hasText(properties.getContainerPrefix())
                ? properties.getContainerPrefix().trim()
                : "funclaw";
        return prefix + "-" + instanceId;
    }

    private String dockerCommand() {
        return StringUtils.hasText(properties.getCommand()) ? properties.getCommand().trim() : "docker";
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

    private String joinContainerPath(String basePath, String relativePath) {
        String normalizedBase = basePath == null ? "" : basePath.trim();
        String normalizedRelative = relativePath == null ? "" : relativePath.trim();
        if (!StringUtils.hasText(normalizedBase)) {
            return normalizedRelative;
        }
        if (!StringUtils.hasText(normalizedRelative)) {
            return normalizedBase;
        }
        if (normalizedRelative.startsWith("/")) {
            return normalizedRelative;
        }
        return normalizedBase.endsWith("/")
                ? normalizedBase + normalizedRelative
                : normalizedBase + "/" + normalizedRelative;
    }

    private CommandResult runCommand(String... command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            process.getInputStream().transferTo(output);
            boolean finished = process.waitFor(commandTimeout.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(124, "command timed out");
            }
            return new CommandResult(process.exitValue(), output.toString(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            return new CommandResult(1, "io error: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new CommandResult(130, "interrupted");
        }
    }

    private record AgentBlock(String id, String block) {
    }

    private record SkillsConfig(boolean openSkillsEnabled, String openSkillsDir) {
    }

    private record LoadedConfig(String path, String text) {
    }

    private record CommandResult(int exitCode, String output) {
    }
}
