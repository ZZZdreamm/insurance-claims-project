package com.kmultan.claims.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.ClaimPhoto;
import com.kmultan.claims.domain.ClaimPhotoRepository;
import com.kmultan.claims.domain.ClaimRepository;
import com.kmultan.claims.domain.Policy;

/**
 * Rule-based screen run at intake. Flags never block a claim — they route it
 * to the special-investigation view of the review queue, where a human decides.
 */
@Service
public class FraudScreeningService {

    public static final String DUPLICATE_CLAIM = "DUPLICATE_CLAIM";
    public static final String EARLY_POLICY_CLAIM = "EARLY_POLICY_CLAIM";
    public static final String REUSED_PHOTO = "REUSED_PHOTO";
    public static final String HIGH_CLAIM_FREQUENCY = "HIGH_CLAIM_FREQUENCY";

    private static final int DUPLICATE_WINDOW_DAYS = 14;
    private static final int EARLY_POLICY_DAYS = 30;
    private static final int FREQUENCY_THRESHOLD = 3;

    private final ClaimRepository claims;
    private final ClaimPhotoRepository claimPhotos;

    public FraudScreeningService(ClaimRepository claims, ClaimPhotoRepository claimPhotos) {
        this.claims = claims;
        this.claimPhotos = claimPhotos;
    }

    public List<String> screen(Claim claim, Policy policy, List<ClaimPhoto> photos) {
        List<String> flags = new ArrayList<>();
        if (claims.existsByPlateNumberAndIncidentDateBetweenAndIdNot(
                claim.getPlateNumber(),
                claim.getIncidentDate().minusDays(DUPLICATE_WINDOW_DAYS),
                claim.getIncidentDate().plusDays(DUPLICATE_WINDOW_DAYS),
                claim.getId())) {
            flags.add(DUPLICATE_CLAIM);
        }
        if (policy != null
                && claim.getIncidentDate().isBefore(policy.getValidFrom().plusDays(EARLY_POLICY_DAYS))) {
            flags.add(EARLY_POLICY_CLAIM);
        }
        for (ClaimPhoto photo : photos) {
            if (claimPhotos.existsByContentHashAndClaimIdNot(photo.getContentHash(), claim.getId())) {
                flags.add(REUSED_PHOTO);
                break;
            }
        }
        if (claim.getOwnerId() != null
                && claims.countByOwnerIdAndCreatedAtAfter(
                                claim.getOwnerId(), Instant.now().minus(365, ChronoUnit.DAYS))
                        >= FREQUENCY_THRESHOLD) {
            flags.add(HIGH_CLAIM_FREQUENCY);
        }
        return flags;
    }

    /**
     * The evidence behind the flags: what an investigator compares side by side. Lists are capped at
     * 20 rows with the true totals alongside — a load test's policyholder can own thousands of claims,
     * and returning them all once froze the claim view.
     */
    public record InvestigationContext(
            List<Claim> duplicateCandidates, long duplicateTotal, List<Claim> ownerClaims, long ownerClaimTotal) {}

    public InvestigationContext investigationContext(Claim claim) {
        var from = claim.getIncidentDate().minusDays(DUPLICATE_WINDOW_DAYS);
        var to = claim.getIncidentDate().plusDays(DUPLICATE_WINDOW_DAYS);
        List<Claim> duplicates = claims.findTop20ByPlateNumberAndIncidentDateBetweenAndIdNotOrderByIncidentDate(
                claim.getPlateNumber(), from, to, claim.getId());
        long duplicateTotal = claims.countByPlateNumberAndIncidentDateBetweenAndIdNot(
                claim.getPlateNumber(), from, to, claim.getId());
        List<Claim> ownerClaims = claim.getOwnerId() == null
                ? List.of()
                : claims.findTop20ByOwnerIdAndIdNotOrderByCreatedAtDesc(claim.getOwnerId(), claim.getId());
        long ownerClaimTotal =
                claim.getOwnerId() == null ? 0 : claims.countByOwnerIdAndIdNot(claim.getOwnerId(), claim.getId());
        return new InvestigationContext(duplicates, duplicateTotal, ownerClaims, ownerClaimTotal);
    }
}
