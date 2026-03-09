package com.fun.ai.claw.plane.controller;

import com.fun.ai.claw.plane.model.AgentDescriptorResponse;
import com.fun.ai.claw.plane.model.AgentSystemPromptResponse;
import com.fun.ai.claw.plane.model.HealthResponse;
import com.fun.ai.claw.plane.model.ListResponse;
import com.fun.ai.claw.plane.model.ReconcileCommandRequest;
import com.fun.ai.claw.plane.model.SkillDescriptorResponse;
import com.fun.ai.claw.plane.model.TaskExecutionRecord;
import com.fun.ai.claw.plane.model.TaskExecutionStatus;
import com.fun.ai.claw.plane.service.ReconcileService;
import com.fun.ai.claw.plane.service.RuntimeIntrospectionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/internal/v1")
public class InternalController {

    private final ReconcileService reconcileService;
    private final RuntimeIntrospectionService runtimeIntrospectionService;

    public InternalController(ReconcileService reconcileService,
                              RuntimeIntrospectionService runtimeIntrospectionService) {
        this.reconcileService = reconcileService;
        this.runtimeIntrospectionService = runtimeIntrospectionService;
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
}
