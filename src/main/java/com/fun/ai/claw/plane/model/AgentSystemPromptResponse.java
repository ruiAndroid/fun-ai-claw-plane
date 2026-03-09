package com.fun.ai.claw.plane.model;

import java.util.UUID;

public record AgentSystemPromptResponse(
        UUID instanceId,
        String agentId,
        String systemPrompt,
        String configPath
) {
}
