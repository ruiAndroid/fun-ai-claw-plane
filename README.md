# fun-ai-claw-plane

Execution plane service for reconciling claw instance commands.

## Tech Stack

- Java 17+
- Spring Boot 4.0.3
- Spring WebMVC + Validation + Actuator

## Run

```bash
mvn spring-boot:run
```

Default port: `8090`

## Current Scope

- `GET /internal/v1/health`
- `POST /internal/v1/reconcile`
- `DELETE /internal/v1/instances/{instanceId}`
- `GET /internal/v1/tasks`

Current implementation executes real Docker commands on host for:

- `START`: create (if absent) + start container
- `STOP`: stop container
- `RESTART`/`ROLLBACK`: restart container (or create+start if absent)
- `DELETE`: stop container gracefully, then remove container

Container name pattern: `${DOCKER_CONTAINER_PREFIX}-${instanceId}` (default prefix: `funclaw`).

Required: plane process user must be allowed to run Docker CLI.

Docker-related environment variables:

- `DOCKER_RUNTIME_ENABLED` (default: `true`)
- `DOCKER_CMD` (default: `docker`)
- `DOCKER_CONTAINER_PREFIX` (default: `funclaw`)
- `DOCKER_RESTART_POLICY` (default: `unless-stopped`)
- `DOCKER_GATEWAY_HOST_PORT` (default: `42617`)
- `DOCKER_GATEWAY_CONTAINER_PORT` (default: `42617`)
- `DOCKER_GATEWAY_HOST` (default: `0.0.0.0`)
- `DOCKER_ALLOW_PUBLIC_BIND` (default: `true`)
- `DOCKER_AGENT_WORKSPACE_MOUNT_ENABLED` (default: `false`, optional env override)
- `DOCKER_AGENT_WORKSPACE_HOST_PATH_TEMPLATE` (default: empty, optional env override)
- `DOCKER_AGENT_WORKSPACE_CONTAINER_PATH_TEMPLATE` (default: `/workspace/agent-mgc-novel-script`, optional env override)
- `DOCKER_AGENT_WORKSPACE_MOUNT_READ_ONLY` (default: `true`, optional env override)
- `DOCKER_WORKSPACE_AGENTS_AUTO_SYNC_ENABLED` (default: `true`)
- `DOCKER_WORKSPACE_AGENTS_FILE_PATH` (default: `/zeroclaw-data/workspace/AGENTS.md`)
- `DOCKER_DELEGATE_AGENT_PROFILE_ENABLED` (default: `true`)
- `DOCKER_DELEGATE_AGENT_PROFILE_ID` (default: `mgc-novel-to-script`)
- `DOCKER_DELEGATE_AGENT_PROVIDER_OVERRIDE` (default: empty, falls back to `default_provider`)
- `DOCKER_DELEGATE_AGENT_MODEL_OVERRIDE` (default: empty, falls back to `default_model`)
- `DOCKER_DELEGATE_AGENT_TEMPERATURE_OVERRIDE` (default: empty, falls back to `default_temperature`)
- `ZEROCLAW_API_KEY` (default: empty)
- `DOCKER_STOP_TIMEOUT_SECONDS` (default: `20`)
- `DOCKER_COMMAND_TIMEOUT_SECONDS` (default: `120`)

Notes:
- `workspace-agents-file-path` should be an absolute container path.
- If runtime receives empty `agentsMdContent` with overwrite enabled, it will overwrite/clear existing workspace `AGENTS.md` to avoid stale prompts.
- Plane now best-effort prewarms a delegate profile at `[agents."mgc-novel-to-script"]` inside container `config.toml`.
- By default, that profile reuses `default_provider`, `default_model`, and `default_temperature` from the runtime config, so the main agent does not need to call `model_routing_config` just to bootstrap this sub-agent.
- If you want a dedicated model for the sub-agent, set the `DOCKER_DELEGATE_AGENT_*_OVERRIDE` env vars explicitly.
- Runtime patch sections are now backed by fragment files under [zeroclaw-fragments/gateway.toml](/D:/dev/AI/AIPro/fun-ai-claw/fun-ai-claw-plane/src/main/resources/zeroclaw-fragments/gateway.toml), [zeroclaw-fragments/model-route.toml](/D:/dev/AI/AIPro/fun-ai-claw/fun-ai-claw-plane/src/main/resources/zeroclaw-fragments/model-route.toml), [zeroclaw-fragments/query-classification-rule.toml](/D:/dev/AI/AIPro/fun-ai-claw/fun-ai-claw-plane/src/main/resources/zeroclaw-fragments/query-classification-rule.toml), and [zeroclaw-fragments/delegate-agent.toml](/D:/dev/AI/AIPro/fun-ai-claw/fun-ai-claw-plane/src/main/resources/zeroclaw-fragments/delegate-agent.toml).
- Their load paths are configured in [application.yml](/D:/dev/AI/AIPro/fun-ai-claw/fun-ai-claw-plane/src/main/resources/application.yml) under `app.docker.*-fragment-path`.

Recommended (config file first): set these in `src/main/resources/application.yml` under `app.docker`.

Example env overrides (optional):

```bash
export DOCKER_AGENT_WORKSPACE_MOUNT_ENABLED=true
export DOCKER_AGENT_WORKSPACE_HOST_PATH_TEMPLATE=/opt/agents/agent-mgc-novel-script
export DOCKER_AGENT_WORKSPACE_CONTAINER_PATH_TEMPLATE=/workspace/agent-mgc-novel-script
export DOCKER_AGENT_WORKSPACE_MOUNT_READ_ONLY=true
```

## Update Script

Use `update-claw-plane.sh` for one-command update on server:

```bash
chmod +x /opt/fun-ai-claw-plane/update-claw-plane.sh
/opt/fun-ai-claw-plane/update-claw-plane.sh
```

Optional environment variables:

- `APP_DIR` (default: `/opt/fun-ai-claw-plane`)
- `SERVICE_NAME` (default: `fun-ai-claw-plane`)
- `GIT_REMOTE` (default: `origin`)
- `GIT_BRANCH` (default: `main`)
- `HEALTH_URL` (default: `http://127.0.0.1:8090/internal/v1/health`)
- `MVN_CMD` (default: `mvn`, Maven >= `3.6.3`)
