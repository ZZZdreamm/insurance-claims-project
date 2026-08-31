package com.kmultan.claims.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The coverage a claim is validated against. A policy with no holder is an
 * open (fleet/demo) policy any authenticated policyholder may claim on; a held
 * policy accepts claims from its holder only.
 */
@Entity
@Table(name = "policy")
public class Policy {

    public enum CoverageType {
        OC,
        AC
    }

    @Id
    @Column(name = "policy_number")
    private String policyNumber;

    @Column(name = "holder_account_id")
    private UUID holderAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "coverage_type", nullable = false)
    private CoverageType coverageType;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to", nullable = false)
    private LocalDate validTo;

    @Column(name = "sum_insured", nullable = false)
    private BigDecimal sumInsured;

    @Column(nullable = false)
    private BigDecimal deductible;

    protected Policy() {}

    public Policy(
            String policyNumber,
            UUID holderAccountId,
            CoverageType coverageType,
            LocalDate validFrom,
            LocalDate validTo,
            BigDecimal sumInsured,
            BigDecimal deductible) {
        this.policyNumber = policyNumber;
        this.holderAccountId = holderAccountId;
        this.coverageType = coverageType;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.sumInsured = sumInsured;
        this.deductible = deductible;
    }

    public boolean coversIncidentOn(LocalDate incidentDate) {
        return !incidentDate.isBefore(validFrom) && !incidentDate.isAfter(validTo);
    }

    public boolean acceptsClaimsFrom(UUID accountId) {
        return holderAccountId == null || holderAccountId.equals(accountId);
    }

    public String getPolicyNumber() {
        return policyNumber;
    }

    public UUID getHolderAccountId() {
        return holderAccountId;
    }

    public CoverageType getCoverageType() {
        return coverageType;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    public BigDecimal getSumInsured() {
        return sumInsured;
    }

    public BigDecimal getDeductible() {
        return deductible;
    }
}
