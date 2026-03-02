package com.fun.ai.claw.plane.model;

import java.util.List;

public record ListResponse<T>(
        List<T> items
) {
}
