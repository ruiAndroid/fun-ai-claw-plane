package com.fun.ai.claw.plane.service;

import com.fun.ai.claw.plane.config.DockerRuntimeProperties;
import com.fun.ai.claw.plane.model.CommandAction;
import com.fun.ai.claw.plane.model.ManagedSkillAssetRecord;
import com.fun.ai.claw.plane.repository.ManagedSkillAssetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DockerRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(DockerRuntimeService.class);
    private static final String AGENTS_MD_CONTENT_PAYLOAD_KEY = "agentsMdContent";
    private static final String AGENTS_MD_OVERWRITE_PAYLOAD_KEY = "agentsMdOverwrite";
    private static final String CONFIG_TOML_CONTENT_PAYLOAD_KEY = "configTomlContent";
    private static final String CONFIG_TOML_OVERWRITE_PAYLOAD_KEY = "configTomlOverwrite";
    private static final Pattern LONG_OPTION_PATTERN = Pattern.compile("--[a-zA-Z0-9][a-zA-Z0-9-]*");
    private static final Pattern REQUIRE_PAIRING_PATTERN =
            Pattern.compile("(?m)^\\s*require_pairing\\s*=\\s*(true|false)\\s*$");
    private static final Pattern GATEWAY_HOST_PATTERN =
            Pattern.compile("(?m)^\\s*host\\s*=\\s*\"[^\"]*\"\\s*$");
    private static final Pattern ALLOW_PUBLIC_BIND_PATTERN =
            Pattern.compile("(?m)^\\s*allow_public_bind\\s*=\\s*(true|false)\\s*$");
    private static final Pattern GATEWAY_SECTION_PATTERN = Pattern.compile("(?m)^\\[gateway\\]\\s*$");
    private static final Pattern MODEL_ROUTE_SECTION_PATTERN = Pattern.compile("(?m)^\\[\\[model_routes\\]\\]\\s*$");
    private static final Pattern QUERY_CLASSIFICATION_RULE_SECTION_PATTERN =
            Pattern.compile("(?m)^\\[\\[query_classification\\.rules\\]\\]\\s*$");
    private static final Pattern SECTION_HEADER_PATTERN =
            Pattern.compile("(?m)^(?:\\[[^\\[\\]\\r\\n]+\\]|\\[\\[[^\\[\\]\\r\\n]+\\]\\])\\s*$");
    private static final Pattern BROKEN_SECTION_HEADER_WITH_SETTING_PATTERN =
            Pattern.compile("(?m)^(\\s*(?:\\[[^\\[\\]\\r\\n]+\\]|\\[\\[[^\\[\\]\\r\\n]+\\]\\]))(\\s*[A-Za-z0-9_-]+\\s*=.*)$");
    private static final Pattern DEFAULT_PROVIDER_PATTERN =
            Pattern.compile("(?m)^\\s*default_provider\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern DEFAULT_MODEL_PATTERN =
            Pattern.compile("(?m)^\\s*default_model\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern DEFAULT_TEMPERATURE_PATTERN =
            Pattern.compile("(?m)^\\s*default_temperature\\s*=\\s*([-+]?[0-9]+(?:\\.[0-9]+)?)\\s*$");
    private static final Pattern DELEGATE_PROVIDER_PATTERN =
            Pattern.compile("(?m)^\\s*provider\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern DELEGATE_MODEL_PATTERN =
            Pattern.compile("(?m)^\\s*model\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern DELEGATE_API_KEY_PATTERN =
            Pattern.compile("(?m)^\\s*api_key\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern DELEGATE_TEMPERATURE_PATTERN =
            Pattern.compile("(?m)^\\s*temperature\\s*=\\s*([-+]?[0-9]+(?:\\.[0-9]+)?)\\s*$");
    private static final Pattern MAX_ITERATIONS_PATTERN =
            Pattern.compile("(?m)^\\s*max_iterations\\s*=\\s*(\\d+)\\s*$");
    private static final Pattern HINT_PATTERN =
            Pattern.compile("(?m)^\\s*hint\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern KEYWORDS_ARRAY_PATTERN =
            Pattern.compile("(?ms)^\\s*keywords\\s*=\\s*\\[(.*?)]\\s*$");
    private static final Pattern LITERALS_ARRAY_PATTERN =
            Pattern.compile("(?ms)^\\s*literals\\s*=\\s*\\[(.*?)]\\s*$");
    private static final Pattern QUOTED_STRING_PATTERN =
            Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern MANIFEST_AGENT_ID_PATTERN =
            Pattern.compile("\"agent_id\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern MANIFEST_AGENT_ID_CAMEL_PATTERN =
            Pattern.compile("\"agentId\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern MANIFEST_MODE_PATTERN =
            Pattern.compile("\"mode\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern MANIFEST_ENTRY_SKILL_PATTERN =
            Pattern.compile("\"entry_skill\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern MANIFEST_ENTRY_SKILL_CAMEL_PATTERN =
            Pattern.compile("\"entrySkill\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern MANIFEST_SKILLS_ARRAY_PATTERN =
            Pattern.compile("(?s)\"skills\"\\s*:\\s*\\[(.*?)]");
    private static final Pattern MANIFEST_ALLOWED_TOOLS_ARRAY_PATTERN =
            Pattern.compile("(?s)\"allowed_tools\"\\s*:\\s*\\[(.*?)]");
    private static final Pattern MANIFEST_ALLOWED_TOOLS_ARRAY_CAMEL_PATTERN =
            Pattern.compile("(?s)\"allowedTools\"\\s*:\\s*\\[(.*?)]");
    private static final Pattern MANIFEST_AGENTIC_PATTERN =
            Pattern.compile("\"agentic\"\\s*:\\s*(true|false)");
    private static final String LEGACY_NOVEL_SCRIPT_HINT = "novel_script";
    private static final Set<String> DELEGATE_AGENTIC_SETTING_KEYS = Set.of(
            "agentic",
            "allowed_tools",
            "max_iterations"
    );
    private static final Set<String> NOVEL_SCRIPT_RULE_KEYWORDS = Set.of(
            "小说转剧本",
            "一句话剧本",
            "剧本",
            "分集大纲",
            "故事梗概",
            "角色设定"
    );
    private static final Set<String> NOVEL_SCRIPT_RULE_LITERALS = Set.of(
            "script_type=",
            "expected_episode_count",
            "target_audience"
    );
    private final DockerRuntimeProperties properties;
    private final ResourceLoader resourceLoader;
    private final ManagedSkillAssetRepository managedSkillAssetRepository;
    private final Map<String, Set<String>> gatewayOptionsByImage = new ConcurrentHashMap<>();
    private final HttpClient healthProbeClient;

    public DockerRuntimeService(DockerRuntimeProperties properties,
                                ResourceLoader resourceLoader,
                                ManagedSkillAssetRepository managedSkillAssetRepository) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
        this.managedSkillAssetRepository = managedSkillAssetRepository;
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
        String agentsMdContent = resolveAgentsMdContent(payload);
        boolean agentsMdOverwrite = resolveAgentsMdOverwrite(payload);
        String configTomlContent = resolveConfigTomlContent(payload);
        boolean configTomlOverwrite = resolveConfigTomlOverwrite(payload);
        return switch (action) {
            case START -> startInstance(instanceId, image, gatewayHostPort, agentsMdContent, agentsMdOverwrite,
                    configTomlContent, configTomlOverwrite);
            case STOP -> stopInstance(instanceId);
            case RESTART, ROLLBACK -> restartInstance(instanceId, image, gatewayHostPort, agentsMdContent, agentsMdOverwrite,
                    configTomlContent, configTomlOverwrite);
            case DELETE -> deleteInstance(instanceId);
        };
    }

    public boolean isInstanceContainerPresent(UUID instanceId) {
        return containerExists(containerName(instanceId));
    }

    public boolean isInstanceContainerRunning(UUID instanceId) {
        return containerRunning(containerName(instanceId));
    }

    public Integer resolveInstanceGatewayHostPort(UUID instanceId) {
        return resolveGatewayHostPortForProbe(containerName(instanceId), null);
    }

    public String resolveGatewayPublicUrl(UUID instanceId, Integer gatewayHostPort) {
        int resolvedPort = gatewayHostPort != null ? gatewayHostPort : properties.getGatewayHostPort();
        return resolveTemplate(properties.getGatewayPublicUrlTemplate(), instanceId, resolvedPort);
    }

    public String resolveGatewayConfigPath(UUID instanceId, Integer gatewayHostPort) {
        int resolvedPort = gatewayHostPort != null ? gatewayHostPort : properties.getGatewayHostPort();
        String configDir = resolveTemplate(properties.getGatewayConfigDirTemplate(), instanceId, resolvedPort);
        return joinContainerPath(configDir, "config.toml");
    }

    public String readTextFileIfPresent(UUID instanceId, String path) {
        String normalizedPath = requireAbsolutePath(path);
        CommandResult result = runDocker(List.of(
                properties.getCommand(),
                "exec",
                containerName(instanceId),
                "/bin/busybox",
                "cat",
                normalizedPath
        ));
        if (result.exitCode != 0) {
            return null;
        }
        return result.output;
    }

    public void writeFile(UUID instanceId, String path, byte[] content, boolean overwrite) {
        String normalizedPath = requireAbsolutePath(path);
        String containerName = containerName(instanceId);
        String parentDir = parentDirOf(normalizedPath);
        if (!StringUtils.hasText(parentDir)) {
            throw new IllegalArgumentException("file path must have a parent directory");
        }
        if (!overwrite && fileExistsInContainer(containerName, normalizedPath)) {
            return;
        }

        byte[] bytes = content == null ? new byte[0] : content;
        String commandScript = "/bin/busybox mkdir -p " + shellQuote(parentDir)
                + " && /bin/busybox dd of=" + shellQuote(normalizedPath) + " conv=fsync";
        CommandResult write = runDockerWithInput(
                List.of(
                        properties.getCommand(),
                        "exec",
                        "-i",
                        containerName,
                        "/bin/busybox",
                        "sh",
                        "-lc",
                        commandScript
                ),
                bytes
        );
        if (write.exitCode != 0) {
            throw new DockerOperationException("failed to write file " + normalizedPath + ": " + write.output.trim());
        }
    }

    public void syncManagedSkills(UUID instanceId) {
        materializeManagedSkillsHostDir(instanceId, null);
        String containerName = containerName(instanceId);
        if (!containerExists(containerName) || !containerRunning(containerName)) {
            return;
        }
        syncManagedSkillsIntoRunningContainerIfNeeded(instanceId, containerName);
    }

    private String startInstance(UUID instanceId,
                                 String image,
                                 Integer gatewayHostPort,
                                 String agentsMdContent,
                                 boolean agentsMdOverwrite,
                                 String configTomlContent,
                                 boolean configTomlOverwrite) {
        String containerName = containerName(instanceId);
        materializeManagedSkillsHostDir(instanceId, gatewayHostPort);
        boolean createdNow = false;
        if (!containerExists(containerName)) {
            if (!StringUtils.hasText(image)) {
                throw new DockerOperationException("image is required to create container");
            }
            createContainer(containerName, instanceId, image.trim(), gatewayHostPort);
            createdNow = true;
        }

        if (containerRunning(containerName)) {
            boolean configChanged = syncRuntimeConfigToml(containerName, configTomlContent, configTomlOverwrite);
            if (configChanged) {
                runDockerChecked(List.of(properties.getCommand(), "restart", containerName), "failed to restart container");
            }
            enforceWorkspaceAgentsGuide(containerName, agentsMdContent, agentsMdOverwrite);
            enforceRuntimeConfigPolicy(containerName, instanceId, gatewayHostPort, configTomlContent != null);
            waitForGatewayReady(containerName, resolveGatewayHostPortForProbe(containerName, gatewayHostPort));
            return "Container already running: " + containerName;
        }

        try {
            syncRuntimeConfigToml(containerName, configTomlContent, configTomlOverwrite);
            runDockerChecked(List.of(properties.getCommand(), "start", containerName), "failed to start container");
            enforceWorkspaceAgentsGuide(containerName, agentsMdContent, agentsMdOverwrite);
            syncManagedSkillsIntoRunningContainerIfNeeded(instanceId, containerName);
            enforceRuntimeConfigPolicy(containerName, instanceId, gatewayHostPort, configTomlContent != null);
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

    private String restartInstance(UUID instanceId,
                                   String image,
                                   Integer gatewayHostPort,
                                   String agentsMdContent,
                                   boolean agentsMdOverwrite,
                                   String configTomlContent,
                                   boolean configTomlOverwrite) {
        String containerName = containerName(instanceId);
        materializeManagedSkillsHostDir(instanceId, gatewayHostPort);
        if (!containerExists(containerName)) {
            if (!StringUtils.hasText(image)) {
                throw new DockerOperationException("image is required to create container for restart");
            }
            createContainer(containerName, instanceId, image.trim(), gatewayHostPort);
            try {
                syncRuntimeConfigToml(containerName, configTomlContent, configTomlOverwrite);
                runDockerChecked(List.of(properties.getCommand(), "start", containerName), "failed to start container");
                enforceWorkspaceAgentsGuide(containerName, agentsMdContent, agentsMdOverwrite);
                syncManagedSkillsIntoRunningContainerIfNeeded(instanceId, containerName);
                enforceRuntimeConfigPolicy(containerName, instanceId, gatewayHostPort, configTomlContent != null);
                waitForGatewayReady(containerName, resolveGatewayHostPortForProbe(containerName, gatewayHostPort));
            } catch (DockerOperationException ex) {
                removeContainerQuietly(containerName);
                throw ex;
            }
            return "Container created and started: " + containerName;
        }

        syncRuntimeConfigToml(containerName, configTomlContent, configTomlOverwrite);
        runDockerChecked(List.of(properties.getCommand(), "restart", containerName), "failed to restart container");
        enforceWorkspaceAgentsGuide(containerName, agentsMdContent, agentsMdOverwrite);
        syncManagedSkillsIntoRunningContainerIfNeeded(instanceId, containerName);
        enforceRuntimeConfigPolicy(containerName, instanceId, gatewayHostPort, configTomlContent != null);
        waitForGatewayReady(containerName, resolveGatewayHostPortForProbe(containerName, gatewayHostPort));
        return "Container restarted: " + containerName;
    }

    private String deleteInstance(UUID instanceId) {
        String containerName = containerName(instanceId);
        if (!containerExists(containerName)) {
            cleanupManagedSkillsHostDir(instanceId, null);
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
        cleanupManagedSkillsHostDir(instanceId, null);
        return "Container deleted: " + containerName;
    }

    private void createContainer(String containerName, UUID instanceId, String image, Integer gatewayHostPort) {
        int hostPort = gatewayHostPort != null ? gatewayHostPort : properties.getGatewayHostPort();
        if (hostPort <= 0 || hostPort > 65535) {
            throw new DockerOperationException("invalid gateway host port: " + hostPort);
        }
        String gatewayConfigDir = resolveTemplate(properties.getGatewayConfigDirTemplate(), instanceId, hostPort);

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
        if (StringUtils.hasText(gatewayConfigDir)) {
            command.add("-e");
            command.add("ZEROCLAW_CONFIG_DIR=" + gatewayConfigDir.trim());
            command.add("-w");
            command.add(gatewayConfigDir.trim());
        }
        if (StringUtils.hasText(properties.getApiKey())) {
            command.add("-e");
            command.add("API_KEY=" + properties.getApiKey().trim());
        }
        if (StringUtils.hasText(properties.getRestartPolicy())) {
            command.add("--restart");
            command.add(properties.getRestartPolicy().trim());
        }
        command.add("--ulimit");
        command.add("nofile=65536:65536");
        appendAgentWorkspaceMountArgs(command, instanceId, hostPort);
        appendInstanceSkillsMountArgs(command, instanceId, hostPort);
        command.add(image);
        command.add("gateway");
        command.add("--host");
        command.add(properties.getGatewayHost());
        command.add("--port");
        command.add(String.valueOf(properties.getGatewayContainerPort()));
        Set<String> supportedOptions = detectGatewayOptions(image);
        appendGatewaySecurityArgs(command, supportedOptions);
        appendGatewayPathRoutingArgs(command, supportedOptions, instanceId, hostPort, gatewayConfigDir);
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

    private void appendInstanceSkillsMountArgs(List<String> command, UUID instanceId, int gatewayHostPort) {
        if (!properties.isInstanceSkillsMountEnabled()) {
            return;
        }
        String hostPath = resolveManagedSkillsHostPath(instanceId, gatewayHostPort);
        String containerPath = normalizeManagedSkillsContainerPath();
        if (!StringUtils.hasText(hostPath) || !StringUtils.hasText(containerPath)) {
            log.warn("skip managed skills mount because hostPath or containerPath is blank");
            return;
        }
        String mountExpr = properties.isInstanceSkillsMountReadOnly()
                ? hostPath.trim() + ":" + containerPath + ":ro"
                : hostPath.trim() + ":" + containerPath;
        command.add("-v");
        command.add(mountExpr);
    }

    private void materializeManagedSkillsHostDir(UUID instanceId, Integer gatewayHostPort) {
        String hostPath = resolveManagedSkillsHostPath(instanceId, gatewayHostPort);
        if (!StringUtils.hasText(hostPath)) {
            log.warn("skip managed skills materialization because host path is blank");
            return;
        }
        try {
            reconcileManagedSkillsDirectory(Path.of(hostPath.trim()), loadManagedSkillAssets(instanceId));
        } catch (IOException ex) {
            throw new DockerOperationException("failed to materialize managed skills for " + instanceId + ": " + ex.getMessage(), ex);
        }
    }

    private void cleanupManagedSkillsHostDir(UUID instanceId, Integer gatewayHostPort) {
        String hostPath = resolveManagedSkillsHostPath(instanceId, gatewayHostPort);
        if (!StringUtils.hasText(hostPath)) {
            return;
        }
        try {
            deleteRecursively(Path.of(hostPath.trim()));
        } catch (IOException ex) {
            log.warn("failed to clean managed skills host dir for {}: {}", instanceId, ex.getMessage());
        }
    }

    private String resolveManagedSkillsHostPath(UUID instanceId, Integer gatewayHostPort) {
        int resolvedPort = gatewayHostPort != null ? gatewayHostPort : properties.getGatewayHostPort();
        return resolveTemplate(properties.getInstanceSkillsHostPathTemplate(), instanceId, resolvedPort);
    }

    private String normalizeManagedSkillsContainerPath() {
        return StringUtils.hasText(properties.getInstanceSkillsContainerPath())
                ? properties.getInstanceSkillsContainerPath().trim()
                : "/workspace/skills";
    }

    private List<ManagedSkillAssetRecord> loadManagedSkillAssets(UUID instanceId) {
        return managedSkillAssetRepository.findEnabledByInstanceId(instanceId);
    }

    private void reconcileManagedSkillsDirectory(Path rootDir, List<ManagedSkillAssetRecord> assets) throws IOException {
        Files.createDirectories(rootDir);
        Set<String> targetSkillKeys = new LinkedHashSet<>();
        for (ManagedSkillAssetRecord asset : assets) {
            String skillKey = asset.skillKey() == null ? "" : asset.skillKey().trim();
            if (!StringUtils.hasText(skillKey)) {
                continue;
            }
            targetSkillKeys.add(skillKey);
            writeManagedSkillDirectory(rootDir, skillKey, asset.skillMd());
        }

        try (var children = Files.list(rootDir)) {
            for (Path child : children.toList()) {
                String name = child.getFileName() == null ? "" : child.getFileName().toString();
                if (!StringUtils.hasText(name) || name.startsWith(".")) {
                    continue;
                }
                if (!targetSkillKeys.contains(name)) {
                    deleteRecursively(child);
                }
            }
        }
    }

    private void writeManagedSkillDirectory(Path rootDir, String skillKey, String skillMd) throws IOException {
        Path targetDir = rootDir.resolve(skillKey);
        Path stagingDir = rootDir.resolve("." + skillKey + ".tmp-" + UUID.randomUUID());
        deleteRecursively(stagingDir);
        Files.createDirectories(stagingDir);
        Files.writeString(
                stagingDir.resolve("SKILL.md"),
                skillMd == null ? "" : skillMd,
                StandardCharsets.UTF_8
        );
        deleteRecursively(targetDir);
        try {
            Files.move(stagingDir, targetDir, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            Files.move(stagingDir, targetDir, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void syncManagedSkillsIntoRunningContainerIfNeeded(UUID instanceId, String containerName) {
        if (!containerRunning(containerName)) {
            return;
        }
        String managedSkillsContainerPath = normalizeManagedSkillsContainerPath();
        if (containerHasMountDestination(containerName, managedSkillsContainerPath)) {
            return;
        }
        syncManagedSkillsIntoContainer(instanceId, containerName, managedSkillsContainerPath, loadManagedSkillAssets(instanceId));
    }

    private boolean containerHasMountDestination(String containerName, String destinationPath) {
        if (!StringUtils.hasText(destinationPath)) {
            return false;
        }
        CommandResult inspect = runDocker(List.of(
                properties.getCommand(),
                "inspect",
                "-f",
                "{{range .Mounts}}{{println .Destination}}{{end}}",
                containerName
        ));
        if (inspect.exitCode != 0 || !StringUtils.hasText(inspect.output)) {
            return false;
        }
        return inspect.output.lines()
                .map(String::trim)
                .anyMatch(destinationPath::equals);
    }

    private void syncManagedSkillsIntoContainer(UUID instanceId,
                                                String containerName,
                                                String containerRootPath,
                                                List<ManagedSkillAssetRecord> assets) {
        if (!StringUtils.hasText(containerRootPath)) {
            return;
        }
        Set<String> targetSkillKeys = new LinkedHashSet<>();
        for (ManagedSkillAssetRecord asset : assets) {
            String skillKey = asset.skillKey() == null ? "" : asset.skillKey().trim();
            if (!StringUtils.hasText(skillKey)) {
                continue;
            }
            targetSkillKeys.add(skillKey);
            writeFile(instanceId, joinContainerPath(joinContainerPath(containerRootPath, skillKey), "SKILL.md"),
                    (asset.skillMd() == null ? "" : asset.skillMd()).getBytes(StandardCharsets.UTF_8), true);
        }
        for (String existingSkillDir : listManagedSkillDirsInContainer(containerName, containerRootPath)) {
            if (!targetSkillKeys.contains(existingSkillDir)) {
                removeContainerDirectory(containerName, joinContainerPath(containerRootPath, existingSkillDir));
            }
        }
    }

    private List<String> listManagedSkillDirsInContainer(String containerName, String containerRootPath) {
        CommandResult listResult = runDocker(List.of(
                properties.getCommand(),
                "exec",
                containerName,
                "/bin/busybox",
                "sh",
                "-lc",
                "/bin/busybox test -d " + shellQuote(containerRootPath)
                        + " && /bin/busybox find " + shellQuote(containerRootPath)
                        + " -mindepth 1 -maxdepth 1 -type d"
        ));
        if (listResult.exitCode != 0 || !StringUtils.hasText(listResult.output)) {
            return List.of();
        }
        return listResult.output.lines()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(path -> {
                    int lastSlash = path.lastIndexOf('/');
                    return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
                })
                .filter(name -> !name.startsWith("."))
                .distinct()
                .toList();
    }

    private void removeContainerDirectory(String containerName, String containerPath) {
        CommandResult result = runDocker(List.of(
                properties.getCommand(),
                "exec",
                containerName,
                "/bin/busybox",
                "rm",
                "-rf",
                containerPath
        ));
        if (result.exitCode != 0) {
            throw new DockerOperationException("failed to remove container directory " + containerPath + ": "
                    + (result.output == null ? "" : result.output.trim()));
        }
    }

    private void deleteRecursively(Path target) throws IOException {
        if (target == null || !Files.exists(target)) {
            return;
        }
        try (var walk = Files.walk(target)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
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
                                              int gatewayHostPort,
                                              String gatewayConfigDir) {
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
                gatewayConfigDir
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

    private boolean syncRuntimeConfigToml(String containerName, String configTomlContent, boolean overwrite) {
        if (configTomlContent == null) {
            return false;
        }
        String normalizedContent = normalizeConfigText(configTomlContent);
        String runtimeConfigPath = "/data/zeroclaw/config.toml";
        if (!overwrite && fileExistsInContainer(containerName, runtimeConfigPath)) {
            return false;
        }
        if (containerRunning(containerName)) {
            CommandResult read = runDocker(List.of(
                    properties.getCommand(),
                    "exec",
                    containerName,
                    "/bin/busybox",
                    "cat",
                    runtimeConfigPath
            ));
            if (read.exitCode == 0 && normalizeConfigText(read.output).equals(normalizedContent)) {
                return false;
            }
        }
        CommandResult write = writeConfigToContainer(containerName, normalizedContent);
        if (write.exitCode != 0) {
            throw new DockerOperationException("failed to write config.toml: " + write.output.trim());
        }
        log.info("synced config.toml for {} to {}", containerName, runtimeConfigPath);
        return true;
    }

    private void enforceRuntimeConfigPolicy(String containerName,
                                            UUID instanceId,
                                            Integer gatewayHostPort,
                                            boolean authoritativeConfigProvided) {
        boolean enforceGatewaySection = properties.isGatewayRuntimeConfigPatchEnabled() && !properties.isRequirePairing();
        boolean enforceDelegateAgentSection = !authoritativeConfigProvided &&
                properties.isDelegateAgentRuntimeConfigPatchEnabled() && properties.isDelegateAgentProfileEnabled();
        boolean enforceModelRouteSection = !authoritativeConfigProvided && properties.isModelRouteRuntimeConfigPatchEnabled();
        boolean enforceQueryClassificationRuleSection =
                !authoritativeConfigProvided && properties.isQueryClassificationRuleRuntimeConfigPatchEnabled();
        if (!properties.isAnyRuntimeConfigPatchEnabled()
                || (!enforceGatewaySection
                && !enforceDelegateAgentSection
                && !enforceModelRouteSection
                && !enforceQueryClassificationRuleSection)) {
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
            DelegateAgentManifestSpec delegateAgentManifest = enforceDelegateAgentSection
                    ? resolveDelegateAgentManifest(
                    containerName,
                    instanceId,
                    gatewayHostPort != null ? gatewayHostPort : properties.getGatewayHostPort()
            )
                    : null;
            String rewrittenConfig = rewriteRuntimeSettings(originalConfig, delegateAgentManifest);
            if (rewrittenConfig.equals(originalConfig)) {
                return;
            }

            waitForGatewayReady(containerName, resolveGatewayHostPortForProbe(containerName, gatewayHostPort));
            CommandResult write = writeConfigToContainer(containerName, rewrittenConfig);
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
                restoreOriginalConfig(containerName, originalConfig, "container restart command failed after runtime config enforcement");
                log.warn("container restart after runtime config enforcement failed for {}: {}", containerName, restart.output.trim());
                return;
            }
            log.info("enforced runtime config policy in {}", containerName);
            return;
        }
    }

    private String rewriteRuntimeSettings(String config, DelegateAgentManifestSpec delegateAgentManifest) {
        String rewritten = sanitizeBrokenSectionHeaders(config);
        rewritten = rewriteGatewaySettings(rewritten);
        DelegateAgentProfileSpec delegateAgentProfile = resolveDelegateAgentProfileSpec(rewritten);
        rewritten = rewriteModelRouteSettings(rewritten, delegateAgentProfile);
        rewritten = rewriteQueryClassificationRuleSettings(rewritten, delegateAgentProfile);
        return rewriteDelegateAgentProfileSettings(rewritten, delegateAgentProfile, delegateAgentManifest);
    }

    private String sanitizeBrokenSectionHeaders(String config) {
        String original = config == null ? "" : config;
        Matcher matcher = BROKEN_SECTION_HEADER_WITH_SETTING_PATTERN.matcher(original);
        if (!matcher.find()) {
            return original;
        }
        return matcher.replaceAll("$1\n$2");
    }

    private String rewriteGatewaySettings(String config) {
        String original = config == null ? "" : config;
        if (!properties.isGatewayRuntimeConfigPatchEnabled() || properties.isRequirePairing()) {
            return original;
        }
        String targetHost = StringUtils.hasText(properties.getGatewayHost())
                ? properties.getGatewayHost().trim()
                : "0.0.0.0";
        RenderedSection fragment = loadRenderedSection(
                properties.getGatewaySectionFragmentPath(),
                Map.of(
                        "gatewayHost", escapeTomlString(targetHost),
                        "allowPublicBind", String.valueOf(properties.isAllowPublicBind())
                )
        );
        return mergeNamedSection(original, GATEWAY_SECTION_PATTERN, fragment);
    }

    private DelegateAgentProfileSpec resolveDelegateAgentProfileSpec(String config) {
        String original = config == null ? "" : config;
        String delegateAgentId = StringUtils.hasText(properties.getDelegateAgentProfileId())
                ? properties.getDelegateAgentProfileId().trim()
                : "";
        if (!StringUtils.hasText(delegateAgentId)) {
            return null;
        }

        SectionMatch delegateAgentSection = findDelegateAgentSection(original, delegateAgentId);
        SectionMatch managedModelRouteSection = findManagedModelRouteSection(original, delegateAgentId);
        String resolvedProvider = firstNonBlank(
                properties.getDelegateAgentProviderOverride(),
                delegateAgentSection == null ? null : findStringValue(DELEGATE_PROVIDER_PATTERN, delegateAgentSection.body()),
                managedModelRouteSection == null ? null : findStringValue(DELEGATE_PROVIDER_PATTERN, managedModelRouteSection.body()),
                findStringValue(DEFAULT_PROVIDER_PATTERN, original)
        );
        String resolvedModel = firstNonBlank(
                properties.getDelegateAgentModelOverride(),
                delegateAgentSection == null ? null : findStringValue(DELEGATE_MODEL_PATTERN, delegateAgentSection.body()),
                managedModelRouteSection == null ? null : findStringValue(DELEGATE_MODEL_PATTERN, managedModelRouteSection.body()),
                findStringValue(DEFAULT_MODEL_PATTERN, original)
        );
        String resolvedTemperature = firstNonBlank(
                properties.getDelegateAgentTemperatureOverride(),
                delegateAgentSection == null ? null : findNumericValue(DELEGATE_TEMPERATURE_PATTERN, delegateAgentSection.body()),
                managedModelRouteSection == null ? null : findNumericValue(DELEGATE_TEMPERATURE_PATTERN, managedModelRouteSection.body()),
                findNumericValue(DEFAULT_TEMPERATURE_PATTERN, original)
        );
        String resolvedRouteApiKey = managedModelRouteSection == null
                ? null
                : findStringValue(DELEGATE_API_KEY_PATTERN, managedModelRouteSection.body());

        if (!StringUtils.hasText(resolvedProvider) || !StringUtils.hasText(resolvedModel)) {
            log.warn("skip delegate agent profile enforcement for {}: unable to resolve provider/model from config.toml",
                    delegateAgentId);
            return null;
        }
        String normalizedTemperature = StringUtils.hasText(resolvedTemperature) ? resolvedTemperature.trim() : null;
        String normalizedRouteApiKey = StringUtils.hasText(resolvedRouteApiKey) ? resolvedRouteApiKey.trim() : null;
        return new DelegateAgentProfileSpec(
                delegateAgentId,
                resolvedProvider.trim(),
                resolvedModel.trim(),
                normalizedTemperature,
                normalizedRouteApiKey
        );
    }

    private String rewriteModelRouteSettings(String config, DelegateAgentProfileSpec delegateAgentProfile) {
        String original = config == null ? "" : config;
        if (!properties.isModelRouteRuntimeConfigPatchEnabled() || delegateAgentProfile == null) {
            return original;
        }
        RenderedSection fragment = loadRenderedSection(
                properties.getModelRouteSectionFragmentPath(),
                Map.of(
                        "delegateAgentId", escapeTomlString(delegateAgentProfile.agentId()),
                        "delegateProvider", escapeTomlString(delegateAgentProfile.provider()),
                        "delegateModel", escapeTomlString(delegateAgentProfile.model()),
                        "delegateRouteApiKeyLine", buildQuotedStringLine("api_key", delegateAgentProfile.routeApiKey())
                )
        );
        SectionMatch sectionMatch = findManagedModelRouteSection(original, delegateAgentProfile.agentId());
        if (sectionMatch != null) {
            String sectionBody = removeSettingBlocks(sectionMatch.body(), Set.of("temperature"));
            String rewrittenSection = mergeSectionBody(sectionBody, fragment.body());
            if (rewrittenSection.equals(normalizeSectionBody(sectionMatch.body()))) {
                return original;
            }
            return original.substring(0, sectionMatch.bodyStart())
                    + rewrittenSection
                    + original.substring(sectionMatch.bodyEnd());
        }
        String suffix = original.endsWith("\n") ? "" : "\n";
        return original + suffix + fragment.header() + "\n" + normalizeSectionBody(fragment.body());
    }

    private String rewriteQueryClassificationRuleSettings(String config, DelegateAgentProfileSpec delegateAgentProfile) {
        String original = config == null ? "" : config;
        if (!properties.isQueryClassificationRuleRuntimeConfigPatchEnabled() || delegateAgentProfile == null) {
            return original;
        }
        RenderedSection fragment = loadRenderedSection(
                properties.getQueryClassificationRuleSectionFragmentPath(),
                Map.of("delegateAgentId", escapeTomlString(delegateAgentProfile.agentId()))
        );
        return mergeArraySection(
                original,
                QUERY_CLASSIFICATION_RULE_SECTION_PATTERN,
                fragment,
                match -> matchesManagedHint(match.body(), delegateAgentProfile.agentId())
                        || containsAnyQuotedValue(findDelimitedValue(KEYWORDS_ARRAY_PATTERN, match.body()), NOVEL_SCRIPT_RULE_KEYWORDS)
                        || containsAnyQuotedValue(findDelimitedValue(LITERALS_ARRAY_PATTERN, match.body()), NOVEL_SCRIPT_RULE_LITERALS)
        );
    }

    private String rewriteDelegateAgentProfileSettings(String config,
                                                       DelegateAgentProfileSpec delegateAgentProfile,
                                                       DelegateAgentManifestSpec delegateAgentManifest) {
        String original = config == null ? "" : config;
        if (!properties.isDelegateAgentRuntimeConfigPatchEnabled()
                || !properties.isDelegateAgentProfileEnabled()
                || delegateAgentProfile == null) {
            return original;
        }

        boolean managedDelegateAgent = delegateAgentManifest != null
                && delegateAgentProfile.agentId().equals(delegateAgentManifest.agentId())
                && delegateAgentManifest.managedMode();
        boolean managedAgenticDelegate = managedDelegateAgent && delegateAgentManifest.agenticMode();
        String delegateAllowedToolsBlock = managedAgenticDelegate
                ? buildTomlStringArrayBlock("allowed_tools", delegateAgentManifest.allowedTools())
                : "";
        String delegateMaxIterationsLine = managedAgenticDelegate
                ? buildMaxIterationsLine(delegateAgentManifest.maxIterations(), original, delegateAgentProfile.agentId())
                : "";
        RenderedSection fragment = loadRenderedSection(
                properties.getDelegateAgentSectionFragmentPath(),
                Map.of(
                        "delegateAgentId", escapeTomlString(delegateAgentProfile.agentId()),
                        "delegateProvider", escapeTomlString(delegateAgentProfile.provider()),
                        "delegateModel", escapeTomlString(delegateAgentProfile.model()),
                        "delegateTemperatureLine", buildTemperatureLine(delegateAgentProfile.temperature()),
                        "delegateAgenticLine", managedAgenticDelegate ? "agentic = true" : "",
                        "delegateAllowedToolsBlock", delegateAllowedToolsBlock,
                        "delegateMaxIterationsLine", delegateMaxIterationsLine
                )
        );

        SectionMatch sectionMatch = findDelegateAgentSection(original, delegateAgentProfile.agentId());
        if (sectionMatch != null) {
            String sectionBody = managedAgenticDelegate
                    ? sectionMatch.body()
                    : removeSettingBlocks(sectionMatch.body(), DELEGATE_AGENTIC_SETTING_KEYS);
            String rewrittenSection = mergeSectionBody(sectionBody, fragment.body());
            if (rewrittenSection.equals(sectionBody)) {
                return original;
            }
            return original.substring(0, sectionMatch.bodyStart())
                    + rewrittenSection
                    + original.substring(sectionMatch.bodyEnd());
        }

        String suffix = original.endsWith("\n") ? "" : "\n";
        return original + suffix + fragment.header() + "\n" + normalizeSectionBody(fragment.body());
    }

    private RenderedSection loadRenderedSection(String resourceLocation, Map<String, String> variables) {
        String normalizedLocation = StringUtils.hasText(resourceLocation)
                ? resourceLocation.trim()
                : "";
        if (!StringUtils.hasText(normalizedLocation)) {
            throw new DockerOperationException("config fragment path is blank");
        }
        Resource resource = resourceLoader.getResource(normalizedLocation);
        if (!resource.exists()) {
            throw new DockerOperationException("config fragment not found: " + normalizedLocation);
        }
        try {
            String template = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String rendered = renderTemplate(template, variables);
            Matcher sectionHeaderMatcher = SECTION_HEADER_PATTERN.matcher(rendered);
            if (!sectionHeaderMatcher.find()) {
                throw new DockerOperationException("config fragment missing section header: " + normalizedLocation);
            }
            String header = sectionHeaderMatcher.group().trim();
            String body = rendered.substring(sectionHeaderMatcher.end());
            return new RenderedSection(header, body);
        } catch (IOException ex) {
            throw new DockerOperationException("failed to read config fragment: " + normalizedLocation + " (" + ex.getMessage() + ")");
        }
    }

    private String renderTemplate(String template, Map<String, String> variables) {
        String rendered = template == null ? "" : template.replace("\r\n", "\n");
        if (variables == null || variables.isEmpty()) {
            return rendered;
        }
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String placeholder = "{{" + entry.getKey() + "}}";
            String value = entry.getValue() == null ? "" : entry.getValue();
            rendered = rendered.replace(placeholder, value);
        }
        return rendered;
    }

    private String mergeNamedSection(String originalConfig, Pattern sectionPattern, RenderedSection fragment) {
        String original = originalConfig == null ? "" : originalConfig;
        SectionMatch sectionMatch = findSection(original, sectionPattern);
        if (sectionMatch != null) {
            String rewrittenSection = mergeSectionBody(sectionMatch.body(), fragment.body());
            if (rewrittenSection.equals(sectionMatch.body())) {
                return original;
            }
            return original.substring(0, sectionMatch.bodyStart())
                    + rewrittenSection
                    + original.substring(sectionMatch.bodyEnd());
        }
        String suffix = original.endsWith("\n") ? "" : "\n";
        return original + suffix + fragment.header() + "\n" + normalizeSectionBody(fragment.body());
    }

    private String mergeArraySection(String originalConfig,
                                     Pattern sectionPattern,
                                     RenderedSection fragment,
                                     Predicate<SectionMatch> selector) {
        String original = originalConfig == null ? "" : originalConfig;
        List<SectionMatch> matches = findSections(original, sectionPattern);
        SectionMatch sectionMatch = null;
        for (SectionMatch candidate : matches) {
            if (selector == null || selector.test(candidate)) {
                sectionMatch = candidate;
                break;
            }
        }
        if (sectionMatch == null && matches.size() == 1) {
            sectionMatch = matches.get(0);
        }
        String replacement = fragment.header() + "\n" + normalizeSectionBody(fragment.body());
        for (SectionMatch candidate : matches) {
            String current = original.substring(candidate.headerStart(), candidate.bodyEnd());
            if (replacement.equals(current)) {
                return original;
            }
        }
        if (sectionMatch != null) {
            String current = original.substring(sectionMatch.headerStart(), sectionMatch.bodyEnd());
            if (replacement.equals(current)) {
                return original;
            }
            return original.substring(0, sectionMatch.headerStart())
                    + replacement
                    + original.substring(sectionMatch.bodyEnd());
        }
        if (!matches.isEmpty()) {
            SectionMatch lastMatch = matches.get(matches.size() - 1);
            String prefix = original.substring(0, lastMatch.bodyEnd());
            String suffix = original.substring(lastMatch.bodyEnd());
            String normalizedPrefix = prefix.endsWith("\n") ? prefix : prefix + "\n";
            String normalizedReplacement = replacement.endsWith("\n") ? replacement : replacement + "\n";
            return normalizedPrefix + normalizedReplacement + suffix;
        }
        String suffix = original.endsWith("\n") ? "" : "\n";
        return original + suffix + replacement;
    }

    private String mergeSectionBody(String existingBody, String desiredBody) {
        String rewritten = normalizeSectionBody(existingBody);
        for (SettingBlock block : parseSettingBlocks(desiredBody)) {
            rewritten = setOrReplaceSettingBlock(
                    rewritten,
                    buildSettingBlockPattern(block.key()),
                    normalizeSectionBody(block.body())
            );
        }
        return rewritten;
    }

    private String removeSettingBlocks(String sectionBody, Set<String> keysToRemove) {
        if (!StringUtils.hasText(sectionBody) || keysToRemove == null || keysToRemove.isEmpty()) {
            return normalizeSectionBody(sectionBody);
        }
        StringBuilder builder = new StringBuilder();
        for (SettingBlock block : parseSettingBlocks(sectionBody)) {
            if (keysToRemove.contains(block.key())) {
                continue;
            }
            builder.append(normalizeSectionBody(block.body()));
        }
        return normalizeSectionBody(builder.toString());
    }

    private List<SettingBlock> parseSettingBlocks(String sectionBody) {
        List<SettingBlock> blocks = new ArrayList<>();
        if (!StringUtils.hasText(sectionBody)) {
            return blocks;
        }
        String normalized = sectionBody.replace("\r\n", "\n");
        String[] lines = normalized.split("\n", -1);
        String currentKey = null;
        StringBuilder currentBody = new StringBuilder();
        Pattern keyPattern = Pattern.compile("^\\s*([A-Za-z0-9_-]+)\\s*=.*$");
        for (String line : lines) {
            Matcher matcher = keyPattern.matcher(line);
            if (matcher.matches()) {
                if (currentKey != null) {
                    blocks.add(new SettingBlock(currentKey, currentBody.toString()));
                }
                currentKey = matcher.group(1);
                currentBody = new StringBuilder().append(line).append('\n');
                continue;
            }
            if (currentKey != null) {
                currentBody.append(line).append('\n');
            }
        }
        if (currentKey != null) {
            blocks.add(new SettingBlock(currentKey, currentBody.toString()));
        }
        return blocks;
    }

    private Pattern buildSettingBlockPattern(String key) {
        return Pattern.compile(
                "(?ms)^\\s*" + Pattern.quote(key) + "\\s*=.*?(?=^\\s*[A-Za-z0-9_-]+\\s*=|\\z)"
        );
    }

    private String normalizeSectionBody(String body) {
        String normalized = body == null ? "" : body.replace("\r\n", "\n");
        while (normalized.startsWith("\n")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.isEmpty() && !normalized.endsWith("\n")) {
            normalized += "\n";
        }
        return normalized;
    }

    private CommandResult writeConfigToContainer(String containerName, String configText) {
        if (containerRunning(containerName)) {
            CommandResult inlineWrite = runDockerWithInput(
                    List.of(
                            properties.getCommand(),
                            "exec",
                            "-u",
                            "0",
                            "-i",
                            containerName,
                            "/bin/busybox",
                            "sh",
                            "-lc",
                            "/bin/busybox cat > /data/zeroclaw/config.toml && /bin/busybox chmod 644 /data/zeroclaw/config.toml"
                    ),
                    (configText == null ? "" : configText).getBytes(StandardCharsets.UTF_8)
            );
            if (inlineWrite.exitCode == 0) {
                return inlineWrite;
            }
            log.warn("inline config write failed for {}: {}, fallback to docker cp",
                    containerName,
                    inlineWrite.output == null ? "" : inlineWrite.output.trim());
        }

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("fun-ai-claw-config-", ".toml");
            Files.writeString(tempFile, configText == null ? "" : configText, StandardCharsets.UTF_8);
            tempFile.toFile().setReadable(true, false);
            tempFile.toFile().setWritable(true, true);

            CommandResult copyResult = runDocker(List.of(
                    properties.getCommand(),
                    "cp",
                    tempFile.toString(),
                    containerName + ":/data/zeroclaw/config.toml"
            ));
            if (copyResult.exitCode != 0) {
                return copyResult;
            }
            if (containerRunning(containerName)) {
                CommandResult chmodResult = runDocker(List.of(
                        properties.getCommand(),
                        "exec",
                        "-u",
                        "0",
                        containerName,
                        "/bin/busybox",
                        "chmod",
                        "644",
                        "/data/zeroclaw/config.toml"
                ));
                if (chmodResult.exitCode != 0) {
                    log.warn("failed to chmod config.toml in {} after docker cp: {}",
                            containerName,
                            chmodResult.output == null ? "" : chmodResult.output.trim());
                }
            }
            return copyResult;
        } catch (IOException ex) {
            return new CommandResult(1, "io error: " + ex.getMessage());
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // Best effort cleanup only.
                }
            }
        }
    }

    private void restoreOriginalConfig(String containerName, String originalConfig, String reason) {
        if (originalConfig == null) {
            log.error("failed to restore config for {} after runtime patch failure: original config is null ({})",
                    containerName,
                    reason);
            return;
        }
        CommandResult rollbackWrite = writeConfigToContainer(containerName, originalConfig);
        if (rollbackWrite.exitCode != 0) {
            log.error("failed to restore original config for {} after runtime patch failure ({}): {}",
                    containerName,
                    reason,
                    rollbackWrite.output == null ? "" : rollbackWrite.output.trim());
            return;
        }
        CommandResult rollbackRestart = runDocker(List.of(properties.getCommand(), "restart", containerName));
        if (rollbackRestart.exitCode != 0) {
            log.error("failed to restart {} after restoring original config ({}): {}",
                    containerName,
                    reason,
                    rollbackRestart.output == null ? "" : rollbackRestart.output.trim());
            return;
        }
        try {
            waitForGatewayReady(containerName, resolveGatewayHostPortForProbe(containerName, null));
            log.warn("restored original config for {} after runtime patch failure: {}", containerName, reason);
        } catch (DockerOperationException ex) {
            log.error("restored original config for {} but gateway is still unhealthy after rollback: {}",
                    containerName,
                    ex.getMessage());
        }
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

    private String setOrReplaceSettingBlock(String section, Pattern blockPattern, String replacementBlock) {
        Matcher matcher = blockPattern.matcher(section);
        if (matcher.find()) {
            return matcher.replaceFirst(Matcher.quoteReplacement(replacementBlock));
        }
        String normalized = section;
        if (!normalized.isEmpty() && !normalized.endsWith("\n")) {
            normalized += "\n";
        }
        return normalized + replacementBlock;
    }

    private String buildTemperatureLine(String temperature) {
        return StringUtils.hasText(temperature) ? "temperature = " + temperature.trim() : "";
    }

    private String buildQuotedStringLine(String key, String value) {
        return StringUtils.hasText(key) && StringUtils.hasText(value)
                ? key.trim() + " = \"" + escapeTomlString(value.trim()) + "\""
                : "";
    }

    private boolean matchesManagedHint(String sectionBody, String delegateAgentId) {
        String hint = findStringValue(HINT_PATTERN, sectionBody);
        if (!StringUtils.hasText(hint)) {
            return false;
        }
        String normalizedHint = hint.trim();
        return delegateAgentId.equals(normalizedHint) || LEGACY_NOVEL_SCRIPT_HINT.equals(normalizedHint);
    }

    private boolean containsAnyQuotedValue(String delimitedValues, Set<String> expectedValues) {
        if (!StringUtils.hasText(delimitedValues) || expectedValues == null || expectedValues.isEmpty()) {
            return false;
        }
        for (String value : parseQuotedStringArray(delimitedValues)) {
            if (expectedValues.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private List<String> parseQuotedStringArray(String delimitedValues) {
        if (!StringUtils.hasText(delimitedValues)) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Matcher matcher = QUOTED_STRING_PATTERN.matcher(delimitedValues);
        while (matcher.find()) {
            String value = unescapeJsonString(matcher.group(1));
            if (StringUtils.hasText(value)) {
                values.add(value.trim());
            }
        }
        return List.copyOf(values);
    }

    private String buildTomlStringArrayBlock(String key, List<String> values) {
        if (!StringUtils.hasText(key) || values == null || values.isEmpty()) {
            return "";
        }
        List<String> normalizedValues = values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        if (normalizedValues.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(key.trim()).append(" = [\n");
        for (String value : normalizedValues) {
            builder.append("    \"")
                    .append(escapeTomlString(value))
                    .append("\",\n");
        }
        builder.setLength(builder.length() - 2);
        builder.append('\n');
        builder.append(']');
        return builder.toString();
    }

    private String buildMaxIterationsLine(Integer manifestMaxIterations, String config, String delegateAgentId) {
        int configuredMaxIterations = manifestMaxIterations != null && manifestMaxIterations > 0
                ? manifestMaxIterations
                : properties.getDelegateAgentMaxIterations();
        if (configuredMaxIterations <= 0) {
            SectionMatch sectionMatch = findDelegateAgentSection(config, delegateAgentId);
            String existingValue = sectionMatch == null ? null : findNumericValue(MAX_ITERATIONS_PATTERN, sectionMatch.body());
            return StringUtils.hasText(existingValue) ? "max_iterations = " + existingValue.trim() : "";
        }
        return "max_iterations = " + configuredMaxIterations;
    }

    private DelegateAgentManifestSpec resolveDelegateAgentManifest(String containerName,
                                                                  UUID instanceId,
                                                                  int gatewayHostPort) {
        if (!properties.isDelegateAgentProfileEnabled()) {
            return null;
        }
        String delegateAgentId = StringUtils.hasText(properties.getDelegateAgentProfileId())
                ? properties.getDelegateAgentProfileId().trim()
                : "";
        if (!StringUtils.hasText(delegateAgentId)) {
            return null;
        }
        String workspacePath = resolveTemplate(properties.getAgentWorkspaceContainerPathTemplate(), instanceId, gatewayHostPort);
        if (!StringUtils.hasText(workspacePath)) {
            log.warn("skip delegate agent allowlist sync for {}: workspace path is blank", delegateAgentId);
            return null;
        }
        String manifestRelativePath = StringUtils.hasText(properties.getDelegateAgentManifestRelativePath())
                ? properties.getDelegateAgentManifestRelativePath().trim()
                : "agent.minifest.json";
        String manifestPath = joinContainerPath(workspacePath.trim(), manifestRelativePath);
        CommandResult read = runDocker(List.of(
                properties.getCommand(),
                "exec",
                containerName,
                "/bin/busybox",
                "cat",
                manifestPath
        ));
        if (read.exitCode != 0 || !StringUtils.hasText(read.output())) {
            log.warn("skip delegate agent allowlist sync for {}: unable to read manifest {} ({})",
                    delegateAgentId,
                    manifestPath,
                    read.output == null ? "" : read.output.trim());
            return null;
        }

        String manifestText = read.output;
        String manifestAgentId = firstNonBlank(
                findJsonStringValue(MANIFEST_AGENT_ID_PATTERN, manifestText),
                findJsonStringValue(MANIFEST_AGENT_ID_CAMEL_PATTERN, manifestText)
        );
        if (StringUtils.hasText(manifestAgentId) && !delegateAgentId.equals(manifestAgentId.trim())) {
            log.warn("skip delegate agent allowlist sync for {}: manifest agent_id={} does not match",
                    delegateAgentId,
                    manifestAgentId.trim());
            return null;
        }
        String manifestMode = findJsonStringValue(MANIFEST_MODE_PATTERN, manifestText);
        String entrySkill = firstNonBlank(
                findJsonStringValue(MANIFEST_ENTRY_SKILL_PATTERN, manifestText),
                findJsonStringValue(MANIFEST_ENTRY_SKILL_CAMEL_PATTERN, manifestText)
        );
        String skillsArrayBody = findDelimitedValue(MANIFEST_SKILLS_ARRAY_PATTERN, manifestText);
        boolean managedMode = "managed".equalsIgnoreCase(manifestMode)
                || StringUtils.hasText(entrySkill)
                || StringUtils.hasText(skillsArrayBody);
        if (!managedMode) {
            log.warn("skip delegate agent profile sync for {}: manifest {} is not a managed delegate",
                    delegateAgentId,
                    manifestPath);
            return null;
        }
        boolean agenticMode = findJsonBooleanValue(MANIFEST_AGENTIC_PATTERN, manifestText, false);
        String allowedToolsArrayBody = firstNonBlank(
                findDelimitedValue(MANIFEST_ALLOWED_TOOLS_ARRAY_PATTERN, manifestText),
                findDelimitedValue(MANIFEST_ALLOWED_TOOLS_ARRAY_CAMEL_PATTERN, manifestText)
        );
        List<String> allowedTools = parseQuotedStringArray(allowedToolsArrayBody);
        if (agenticMode && allowedTools.isEmpty()) {
            log.warn("delegate manifest {} for {} requested agentic mode without explicit allowed_tools; disabling agentic sync",
                    manifestPath,
                    delegateAgentId);
            agenticMode = false;
        }
        if (!agenticMode && (StringUtils.hasText(entrySkill) || StringUtils.hasText(skillsArrayBody))) {
            log.info("delegate manifest {} for {} exposes prompt skills only; skip agentic allowlist injection",
                    manifestPath,
                    delegateAgentId);
        }
        return new DelegateAgentManifestSpec(
                delegateAgentId,
                true,
                manifestPath,
                allowedTools,
                properties.getDelegateAgentMaxIterations(),
                agenticMode
        );
    }

    private SectionMatch findDelegateAgentSection(String config, String delegateAgentId) {
        if (!StringUtils.hasText(config) || !StringUtils.hasText(delegateAgentId)) {
            return null;
        }
        SectionMatch directAgentSection = findSection(config, buildAgentSectionPattern(delegateAgentId));
        if (directAgentSection != null) {
            return directAgentSection;
        }
        return findSection(config, buildModelRoutesAgentSectionPattern(delegateAgentId));
    }

    private SectionMatch findManagedModelRouteSection(String config, String delegateAgentId) {
        List<SectionMatch> matches = findSections(config, MODEL_ROUTE_SECTION_PATTERN);
        for (SectionMatch candidate : matches) {
            if (matchesManagedHint(candidate.body(), delegateAgentId)) {
                return candidate;
            }
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private SectionMatch findSection(String config, Pattern sectionPattern) {
        List<SectionMatch> sections = findSections(config, sectionPattern);
        return sections.isEmpty() ? null : sections.get(0);
    }

    private List<SectionMatch> findSections(String config, Pattern sectionPattern) {
        List<SectionMatch> matches = new ArrayList<>();
        if (!StringUtils.hasText(config) || sectionPattern == null) {
            return matches;
        }
        Matcher sectionMatcher = sectionPattern.matcher(config);
        while (sectionMatcher.find()) {
            int headerStart = sectionMatcher.start();
            int headerEnd = sectionMatcher.end();
            int bodyStart = headerEnd;
            if (bodyStart < config.length()) {
                char next = config.charAt(bodyStart);
                if (next == '\r') {
                    bodyStart++;
                    if (bodyStart < config.length() && config.charAt(bodyStart) == '\n') {
                        bodyStart++;
                    }
                } else if (next == '\n') {
                    bodyStart++;
                }
            }
            int bodyEnd = config.length();
            Matcher sectionHeaderMatcher = SECTION_HEADER_PATTERN.matcher(config);
            while (sectionHeaderMatcher.find(bodyStart)) {
                if (sectionHeaderMatcher.start() > bodyStart) {
                    bodyEnd = sectionHeaderMatcher.start();
                    break;
                }
            }
            matches.add(new SectionMatch(
                    headerStart,
                    headerEnd,
                    bodyStart,
                    bodyEnd,
                    config.substring(headerStart, headerEnd).trim(),
                    config.substring(bodyStart, bodyEnd)
            ));
        }
        return matches;
    }

    private Pattern buildAgentSectionPattern(String delegateAgentId) {
        return Pattern.compile(
                "(?m)^\\[\\s*agents\\s*\\.\\s*(?:\""
                        + Pattern.quote(escapeTomlString(delegateAgentId))
                        + "\"|'"
                        + Pattern.quote(delegateAgentId)
                        + "'|"
                        + Pattern.quote(delegateAgentId)
                        + ")\\s*]\\s*$"
        );
    }

    private Pattern buildModelRoutesAgentSectionPattern(String delegateAgentId) {
        return Pattern.compile(
                "(?m)^\\[\\s*model_routes\\s*\\.\\s*agents\\s*\\.\\s*(?:\""
                        + Pattern.quote(escapeTomlString(delegateAgentId))
                        + "\"|'"
                        + Pattern.quote(delegateAgentId)
                        + "'|"
                        + Pattern.quote(delegateAgentId)
                        + ")\\s*]\\s*$"
        );
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

    private String escapeTomlString(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private String findStringValue(Pattern pattern, String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find() || matcher.groupCount() < 1) {
            return null;
        }
        String value = matcher.group(1);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.replace("\\\"", "\"").replace("\\\\", "\\").trim();
    }

    private String findNumericValue(Pattern pattern, String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find() || matcher.groupCount() < 1) {
            return null;
        }
        String value = matcher.group(1);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String findJsonStringValue(Pattern pattern, String text) {
        String value = findDelimitedValue(pattern, text);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return unescapeJsonString(value);
    }

    private String findDelimitedValue(Pattern pattern, String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find() || matcher.groupCount() < 1) {
            return null;
        }
        String value = matcher.group(1);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private boolean findJsonBooleanValue(Pattern pattern, String text, boolean defaultValue) {
        if (!StringUtils.hasText(text) || pattern == null) {
            return defaultValue;
        }
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find() || matcher.groupCount() < 1) {
            return defaultValue;
        }
        String value = matcher.group(1);
        if (!StringUtils.hasText(value)) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private String unescapeJsonString(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current != '\\' || i == value.length() - 1) {
                builder.append(current);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case '"', '\\', '/' -> builder.append(next);
                case 'b' -> builder.append('\b');
                case 'f' -> builder.append('\f');
                case 'n' -> builder.append('\n');
                case 'r' -> builder.append('\r');
                case 't' -> builder.append('\t');
                case 'u' -> {
                    if (i + 4 >= value.length()) {
                        builder.append("\\u");
                        continue;
                    }
                    String hex = value.substring(i + 1, i + 5);
                    try {
                        builder.append((char) Integer.parseInt(hex, 16));
                        i += 4;
                    } catch (NumberFormatException ex) {
                        builder.append("\\u").append(hex);
                        i += 4;
                    }
                }
                default -> builder.append(next);
            }
        }
        String normalized = builder.toString().trim();
        return StringUtils.hasText(normalized) ? normalized : null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
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

        String logsTail = readContainerLogsTail(containerName, 40);
        String logSuffix = StringUtils.hasText(logsTail)
                ? " logs: " + logsTail
                : "";
        throw new DockerOperationException(
                "gateway readiness check timed out after "
                        + timeoutSeconds
                        + "s for "
                        + containerName
                        + " at "
                        + uri
                        + " (last error: "
                        + lastError
                        + ")"
                        + logSuffix
        );
    }

    private String readContainerLogsTail(String containerName, int tailLines) {
        int safeTailLines = tailLines > 0 ? tailLines : 40;
        try {
            CommandResult result = runDocker(List.of(
                    properties.getCommand(),
                    "logs",
                    "--tail",
                    String.valueOf(safeTailLines),
                    containerName
            ));
            if (result.exitCode != 0 || !StringUtils.hasText(result.output)) {
                return null;
            }
            return result.output.replace("\r", " ").replace("\n", " | ").trim();
        } catch (DockerOperationException ex) {
            log.warn("failed to read container logs for {}: {}", containerName, ex.getMessage());
            return null;
        }
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

    private String resolveAgentsMdContent(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        if (!payload.containsKey(AGENTS_MD_CONTENT_PAYLOAD_KEY)) {
            return null;
        }
        Object raw = payload.get(AGENTS_MD_CONTENT_PAYLOAD_KEY);
        if (raw instanceof String content) {
            return content;
        }
        return null;
    }

    private String resolveConfigTomlContent(Map<String, Object> payload) {
        if (payload == null || !payload.containsKey(CONFIG_TOML_CONTENT_PAYLOAD_KEY)) {
            return null;
        }
        Object raw = payload.get(CONFIG_TOML_CONTENT_PAYLOAD_KEY);
        if (raw instanceof String content) {
            return normalizeConfigText(content);
        }
        return null;
    }

    private boolean resolveAgentsMdOverwrite(Map<String, Object> payload) {
        if (payload == null || !payload.containsKey(AGENTS_MD_OVERWRITE_PAYLOAD_KEY)) {
            return true;
        }
        Object raw = payload.get(AGENTS_MD_OVERWRITE_PAYLOAD_KEY);
        if (raw instanceof Boolean bool) {
            return bool;
        }
        if (raw instanceof String text && StringUtils.hasText(text)) {
            String normalized = text.trim().toLowerCase();
            if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized)) {
                return true;
            }
            if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized) || "off".equals(normalized)) {
                return false;
            }
        }
        return true;
    }

    private boolean resolveConfigTomlOverwrite(Map<String, Object> payload) {
        if (payload == null || !payload.containsKey(CONFIG_TOML_OVERWRITE_PAYLOAD_KEY)) {
            return true;
        }
        Object raw = payload.get(CONFIG_TOML_OVERWRITE_PAYLOAD_KEY);
        if (raw instanceof Boolean bool) {
            return bool;
        }
        if (raw instanceof String text && StringUtils.hasText(text)) {
            String normalized = text.trim().toLowerCase();
            if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized)) {
                return true;
            }
            if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized) || "off".equals(normalized)) {
                return false;
            }
        }
        return true;
    }

    private void enforceWorkspaceAgentsGuide(String containerName, String agentsMdContent, boolean overwrite) {
        if (!properties.isWorkspaceAgentsAutoSyncEnabled()) {
            return;
        }
        if (agentsMdContent == null) {
            return;
        }
        String targetPath = properties.getWorkspaceAgentsFilePath();
        if (!StringUtils.hasText(targetPath)) {
            log.warn("skip AGENTS.md sync for {}: workspaceAgentsFilePath is blank", containerName);
            return;
        }
        String normalizedPath = targetPath.trim();
        if (!normalizedPath.startsWith("/")) {
            log.warn("skip AGENTS.md sync for {}: workspaceAgentsFilePath must be absolute but was {}", containerName, normalizedPath);
            return;
        }
        String parentDir = parentDirOf(normalizedPath);
        if (!StringUtils.hasText(parentDir)) {
            log.warn("skip AGENTS.md sync for {}: cannot derive parent dir from {}", containerName, normalizedPath);
            return;
        }
        if (!overwrite && fileExistsInContainer(containerName, normalizedPath)) {
            return;
        }

        String commandScript = "/bin/busybox mkdir -p " + shellQuote(parentDir)
                + " && /bin/busybox dd of=" + shellQuote(normalizedPath) + " conv=fsync";
        CommandResult write = runDockerWithInput(
                List.of(
                        properties.getCommand(),
                        "exec",
                        "-i",
                        containerName,
                        "/bin/busybox",
                        "sh",
                        "-lc",
                        commandScript
                ),
                agentsMdContent.getBytes(StandardCharsets.UTF_8)
        );
        if (write.exitCode != 0) {
            log.warn("failed to sync AGENTS.md for {} (path={}): {}", containerName, normalizedPath, write.output.trim());
            return;
        }
        log.info("synced AGENTS.md for {} to {}", containerName, normalizedPath);
    }

    private boolean fileExistsInContainer(String containerName, String path) {
        CommandResult result = runDocker(List.of(
                properties.getCommand(),
                "exec",
                containerName,
                "/bin/busybox",
                "test",
                "-f",
                path
        ));
        return result.exitCode == 0;
    }

    private String requireAbsolutePath(String path) {
        if (!StringUtils.hasText(path)) {
            throw new IllegalArgumentException("file path must not be blank");
        }
        String normalizedPath = path.trim();
        if (!normalizedPath.startsWith("/")) {
            throw new IllegalArgumentException("file path must be absolute");
        }
        return normalizedPath;
    }

    private String parentDirOf(String path) {
        if (!StringUtils.hasText(path)) {
            return null;
        }
        int slash = path.lastIndexOf('/');
        if (slash <= 0) {
            return null;
        }
        return path.substring(0, slash);
    }

    private String shellQuote(String text) {
        if (text == null) {
            return "''";
        }
        return "'" + text.replace("'", "'\"'\"'") + "'";
    }

    private String containerName(UUID instanceId) {
        return properties.getContainerPrefix() + "-" + instanceId;
    }

    private record DelegateAgentProfileSpec(String agentId,
                                            String provider,
                                            String model,
                                            String temperature,
                                            String routeApiKey) {
    }

    private record DelegateAgentManifestSpec(String agentId,
                                             boolean managedMode,
                                             String manifestPath,
                                             List<String> allowedTools,
                                             Integer maxIterations,
                                             boolean agenticMode) {
    }

    private record SectionMatch(int headerStart,
                                int headerEnd,
                                int bodyStart,
                                int bodyEnd,
                                String header,
                                String body) {
    }

    private record RenderedSection(String header, String body) {
    }

    private record SettingBlock(String key, String body) {
    }

    private record CommandResult(int exitCode, String output) {
    }

    private String normalizeConfigText(String text) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").trim();
        if (normalized.isEmpty()) {
            return "";
        }
        return normalized + "\n";
    }
}


