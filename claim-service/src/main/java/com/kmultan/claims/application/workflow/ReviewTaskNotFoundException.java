package com.kmultan.claims.application.workflow;

public class ReviewTaskNotFoundException extends RuntimeException {
    public ReviewTaskNotFoundException(String taskId) {
        super("Review task %s not found or already completed".formatted(taskId));
    }
}
