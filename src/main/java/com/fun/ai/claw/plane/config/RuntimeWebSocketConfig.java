package com.fun.ai.claw.plane.config;

import com.fun.ai.claw.plane.service.AgentSessionWebSocketHandler;
import com.fun.ai.claw.plane.service.TerminalWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class RuntimeWebSocketConfig implements WebSocketConfigurer {

    private final AgentSessionWebSocketHandler agentSessionWebSocketHandler;
    private final TerminalWebSocketHandler terminalWebSocketHandler;

    public RuntimeWebSocketConfig(AgentSessionWebSocketHandler agentSessionWebSocketHandler,
                                  TerminalWebSocketHandler terminalWebSocketHandler) {
        this.agentSessionWebSocketHandler = agentSessionWebSocketHandler;
        this.terminalWebSocketHandler = terminalWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(agentSessionWebSocketHandler, "/internal/v1/agent-session/ws")
                .setAllowedOriginPatterns("*");

        registry.addHandler(terminalWebSocketHandler, "/internal/v1/terminal/ws")
                .setAllowedOriginPatterns("*");
    }
}
