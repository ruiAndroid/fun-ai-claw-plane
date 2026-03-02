package com.fun.ai.claw.plane.controller;

import com.fun.ai.claw.plane.model.HealthResponse;
import com.fun.ai.claw.plane.model.ListResponse;
import com.fun.ai.claw.plane.model.ReconcileCommandRequest;
import com.fun.ai.claw.plane.model.TaskExecutionRecord;
import com.fun.ai.claw.plane.model.TaskExecutionStatus;
import com.fun.ai.claw.plane.service.ReconcileService;
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

    public InternalController(ReconcileService reconcileService) {
        this.reconcileService = reconcileService;
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
}
