package com.fun.ai.claw.plane.model;

import java.util.List;

public record ManagedSkillSyncRequest(
        List<ManagedSkillAssetRecord> items
) {
}
