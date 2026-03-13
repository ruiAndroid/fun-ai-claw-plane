package com.fun.ai.claw.plane.controller;

import com.fun.ai.claw.plane.model.AgentDescriptorResponse;
import com.fun.ai.claw.plane.model.AgentSystemPromptResponse;
import com.fun.ai.claw.plane.model.HealthResponse;
import com.fun.ai.claw.plane.model.ListResponse;
import com.fun.ai.claw.plane.model.ManagedSkillSyncRequest;
import com.fun.ai.claw.plane.model.PairingCodeResponse;
import com.fun.ai.claw.plane.model.ReconcileCommandRequest;
import com.fun.ai.claw.plane.model.SkillDescriptorResponse;
import com.fun.ai.claw.plane.model.TaskExecutionRecord;
import com.fun.ai.claw.plane.model.TaskExecutionStatus;
import com.fun.ai.claw.plane.service.DockerRuntimeService;
import com.fun.ai.claw.plane.service.PairingCodeService;
import com.fun.ai.claw.plane.service.ReconcileService;
import com.fun.ai.claw.plane.service.RuntimeIntrospectionService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/internal/v1")
public class InternalController {

    private final ReconcileService reconcileService;
    private final RuntimeIntrospectionService runtimeIntrospectionService;
    private final PairingCodeService pairingCodeService;
    private final DockerRuntimeService dockerRuntimeService;

    public InternalController(ReconcileService reconcileService,
                              RuntimeIntrospectionService runtimeIntrospectionService,
                              PairingCodeService pairingCodeService,
                              DockerRuntimeService dockerRuntimeService) {
        this.reconcileService = reconcileService;
        this.runtimeIntrospectionService = runtimeIntrospectionService;
        this.pairingCodeService = pairingCodeService;
        this.dockerRuntimeService = dockerRuntimeService;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("UP", "fun-ai-claw-plane");
    }

    @PostMapping("/reconcile")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskExecutionRecord reconcile(@Valid @RequestBody ReconcileCommandRequest request) {
        return reconcileService.accept(request);
    }

    @DeleteMapping("/instances/{instanceId}")
    public TaskExecutionRecord deleteInstance(@PathVariable UUID instanceId) {
        TaskExecutionRecord record = reconcileService.deleteInstance(instanceId);
        if (record.status() == TaskExecutionStatus.FAILED) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, record.message());
        }
        return record;
    }

    @GetMapping("/tasks")
    public ListResponse<TaskExecutionRecord> listTasks() {
        return new ListResponse<>(reconcileService.list());
    }

    @GetMapping("/instances/{instanceId}/agents")
    public ListResponse<AgentDescriptorResponse> listAgents(@PathVariable UUID instanceId) {
        return new ListResponse<>(runtimeIntrospectionService.listAgents(instanceId));
    }

    @GetMapping("/instances/{instanceId}/agents/{agentId}/system-prompt")
    public AgentSystemPromptResponse getAgentSystemPrompt(@PathVariable UUID instanceId,
                                                          @PathVariable String agentId) {
        return runtimeIntrospectionService.getAgentSystemPrompt(instanceId, agentId);
    }

    @GetMapping("/instances/{instanceId}/skills")
    public ListResponse<SkillDescriptorResponse> listSkills(@PathVariable UUID instanceId) {
        return new ListResponse<>(runtimeIntrospectionService.listSkills(instanceId));
    }

    @PostMapping("/instances/{instanceId}/skills/sync")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void syncManagedSkills(@PathVariable UUID instanceId,
                                  @RequestBody(required = false) ManagedSkillSyncRequest request) {
        dockerRuntimeService.syncManagedSkills(instanceId, request == null ? null : request.items());
    }

    @PutMapping(value = "/skill-packages/{skillKey}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void uploadSkillPackage(@PathVariable String skillKey,
                                   @RequestParam(defaultValue = "false") boolean overwrite,
                                   @RequestBody byte[] zipBytes) {
        dockerRuntimeService.importSkillPackage(skillKey, zipBytes, overwrite);
    }

    @DeleteMapping("/skill-packages/{skillKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSkillPackage(@PathVariable String skillKey) {
        dockerRuntimeService.deleteSkillPackage(skillKey);
    }

    @GetMapping("/instances/{instanceId}/pairing-code")
    public PairingCodeResponse getPairingCode(@PathVariable UUID instanceId) {
        return pairingCodeService.fetchPairingCode(instanceId);
    }

    @GetMapping(value = "/instances/{instanceId}/files", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> readInstanceFile(@PathVariable UUID instanceId,
                                                   @RequestParam String path) {
        String normalizedPath = requireAbsolutePath(path);
        String content;
        try {
            content = dockerRuntimeService.readTextFileIfPresent(instanceId, normalizedPath);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        if (content == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "runtime file not found: " + normalizedPath);
        }
        return ResponseEntity.ok(content.getBytes(StandardCharsets.UTF_8));
    }

    @PutMapping(value = "/instances/{instanceId}/files", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void writeInstanceFile(@PathVariable UUID instanceId,
                                  @RequestParam String path,
                                  @RequestParam(defaultValue = "true") boolean overwrite,
                                  @RequestBody byte[] content) {
        String normalizedPath = requireAbsolutePath(path);
        try {
            dockerRuntimeService.writeFile(instanceId, normalizedPath, content, overwrite);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ex.getMessage());
        }
    }

    private String requireAbsolutePath(String path) {
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path must not be blank");
        }
        String normalizedPath = path.trim();
        if (!normalizedPath.startsWith("/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path must be absolute");
        }
        return normalizedPath;
    }
}
