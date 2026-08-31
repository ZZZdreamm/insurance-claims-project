package com.kmultan.claims.domain;

/** The claim contradicts the policy it names: unknown policy, outside the coverage period, wrong holder, or nothing payable. */
public class PolicyValidationException extends RuntimeException {
    public PolicyValidationException(String message) {
        super(message);
    }
}
