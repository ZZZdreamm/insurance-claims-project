package com.kmultan.claims.application.workflow;

import java.time.Instant;
import java.util.UUID;

/** An open adjuster review task as seen by the console. */
public record ReviewTask(
        String taskId,
        UUID claimId,
        String assignee,
        Instant createdAt,
        Instant dueAt,
        String severity,
        boolean escalated
) {}
