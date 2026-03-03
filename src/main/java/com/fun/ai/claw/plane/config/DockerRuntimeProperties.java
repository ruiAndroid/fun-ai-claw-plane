package com.fun.ai.claw.plane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.docker")
public class DockerRuntimeProperties {

    private boolean enabled = true;
    private String command = "docker";
    private String containerPrefix = "funclaw";
    private String restartPolicy = "unless-stopped";
    private int gatewayHostPort = 42617;
    private int gatewayContainerPort = 42617;
    private String gatewayHost = "0.0.0.0";
    private boolean uiPathRoutingEnabled = true;
    private String gatewayBasePathTemplate = "/fun-claw/ui-controller/{instanceId}";
    private String gatewayPublicUrlTemplate = "http://8.152.159.249/fun-claw/ui-controller/{instanceId}";
    private String gatewayConfigDirTemplate = "/data/zeroclaw";
    private String gatewayBasePathOption = "--base-path";
    private String gatewayPublicUrlOption = "--public-url";
    private String gatewayConfigDirOption = "--config-dir";
    private boolean allowPublicBind = true;
    private String apiKey;
    private long stopTimeoutSeconds = 20;
    private long commandTimeoutSeconds = 120;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String getContainerPrefix() {
        return containerPrefix;
    }

    public void setContainerPrefix(String containerPrefix) {
        this.containerPrefix = containerPrefix;
    }

    public String getRestartPolicy() {
        return restartPolicy;
    }

    public void setRestartPolicy(String restartPolicy) {
        this.restartPolicy = restartPolicy;
    }

    public int getGatewayHostPort() {
        return gatewayHostPort;
    }

    public void setGatewayHostPort(int gatewayHostPort) {
        this.gatewayHostPort = gatewayHostPort;
    }

    public int getGatewayContainerPort() {
        return gatewayContainerPort;
    }

    public void setGatewayContainerPort(int gatewayContainerPort) {
        this.gatewayContainerPort = gatewayContainerPort;
    }

    public String getGatewayHost() {
        return gatewayHost;
    }

    public void setGatewayHost(String gatewayHost) {
        this.gatewayHost = gatewayHost;
    }

    public boolean isUiPathRoutingEnabled() {
        return uiPathRoutingEnabled;
    }

    public void setUiPathRoutingEnabled(boolean uiPathRoutingEnabled) {
        this.uiPathRoutingEnabled = uiPathRoutingEnabled;
    }

    public String getGatewayBasePathTemplate() {
        return gatewayBasePathTemplate;
    }

    public void setGatewayBasePathTemplate(String gatewayBasePathTemplate) {
        this.gatewayBasePathTemplate = gatewayBasePathTemplate;
    }

    public String getGatewayPublicUrlTemplate() {
        return gatewayPublicUrlTemplate;
    }

    public void setGatewayPublicUrlTemplate(String gatewayPublicUrlTemplate) {
        this.gatewayPublicUrlTemplate = gatewayPublicUrlTemplate;
    }

    public String getGatewayBasePathOption() {
        return gatewayBasePathOption;
    }

    public void setGatewayBasePathOption(String gatewayBasePathOption) {
        this.gatewayBasePathOption = gatewayBasePathOption;
    }

    public String getGatewayConfigDirTemplate() {
        return gatewayConfigDirTemplate;
    }

    public void setGatewayConfigDirTemplate(String gatewayConfigDirTemplate) {
        this.gatewayConfigDirTemplate = gatewayConfigDirTemplate;
    }

    public String getGatewayPublicUrlOption() {
        return gatewayPublicUrlOption;
    }

    public void setGatewayPublicUrlOption(String gatewayPublicUrlOption) {
        this.gatewayPublicUrlOption = gatewayPublicUrlOption;
    }

    public String getGatewayConfigDirOption() {
        return gatewayConfigDirOption;
    }

    public void setGatewayConfigDirOption(String gatewayConfigDirOption) {
        this.gatewayConfigDirOption = gatewayConfigDirOption;
    }

    public boolean isAllowPublicBind() {
        return allowPublicBind;
    }

    public void setAllowPublicBind(boolean allowPublicBind) {
        this.allowPublicBind = allowPublicBind;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public long getStopTimeoutSeconds() {
        return stopTimeoutSeconds;
    }

    public void setStopTimeoutSeconds(long stopTimeoutSeconds) {
        this.stopTimeoutSeconds = stopTimeoutSeconds;
    }

    public long getCommandTimeoutSeconds() {
        return commandTimeoutSeconds;
    }

    public void setCommandTimeoutSeconds(long commandTimeoutSeconds) {
        this.commandTimeoutSeconds = commandTimeoutSeconds;
    }
}
