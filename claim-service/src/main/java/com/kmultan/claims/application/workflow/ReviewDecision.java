package com.kmultan.claims.application.workflow;

import java.math.BigDecimal;

public record ReviewDecision(Decision decision, BigDecimal approvedAmount, String reason) {
    public enum Decision { APPROVE, REJECT }

    public ReviewDecision {
        if (decision == Decision.APPROVE && (approvedAmount == null || approvedAmount.signum() <= 0)) {
            throw new IllegalArgumentException("Approval requires a positive approvedAmount");
        }
        if (decision == Decision.REJECT && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("Rejection requires a reason");
        }
    }
}
