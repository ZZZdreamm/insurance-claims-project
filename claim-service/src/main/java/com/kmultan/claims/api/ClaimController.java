package com.kmultan.claims.api;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.kmultan.claims.api.dto.ClaimResponse;
import com.kmultan.claims.api.dto.RetryPayoutRequest;
import com.kmultan.claims.api.dto.SubmitClaimRequest;
import com.kmultan.claims.application.ClaimService;
import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.ClaimPhoto;
import com.kmultan.claims.domain.ClaimStatus;
import com.kmultan.claims.infrastructure.security.AuthenticatedUser;

@RestController
@RequestMapping("/api/v1/claims")
public class ClaimController {

    private static final Set<String> ACCEPTED_PHOTO_TYPES =
            Set.of(MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, "image/webp");

    private final ClaimService claimService;
    private final DecisionDocumentRenderer decisionDocuments;
    private final ClaimResponseAssembler responses;

    public ClaimController(
            ClaimService claimService, ClaimResponseAssembler responses, DecisionDocumentRenderer decisionDocuments) {
        this.claimService = claimService;
        this.responses = responses;
        this.decisionDocuments = decisionDocuments;
    }

    /** JSON submit: no photos, triage falls back to the text model. The claim belongs to the caller. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('POLICYHOLDER', 'ADMIN')")
    public ResponseEntity<ClaimResponse> submit(@Valid @RequestBody SubmitClaimRequest request) {
        return created(submit(request, List.of()));
    }

    /** Multipart submit: {@code claim} JSON part + zero or more {@code photos} image parts. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('POLICYHOLDER', 'ADMIN')")
    public ResponseEntity<ClaimResponse> submitWithPhotos(
            @Valid @RequestPart("claim") SubmitClaimRequest request,
            @RequestPart(value = "photos", required = false) List<MultipartFile> photoFiles)
            throws IOException {
        List<ClaimService.Photo> photos = new ArrayList<>();
        for (MultipartFile photoFile : photoFiles == null ? List.<MultipartFile>of() : photoFiles) {
            if (photoFile.isEmpty()) {
                continue;
            }
            if (!ACCEPTED_PHOTO_TYPES.contains(photoFile.getContentType())) {
                throw new IllegalArgumentException("Unsupported photo type: " + photoFile.getContentType());
            }
            photos.add(new ClaimService.Photo(photoFile.getContentType(), photoFile.getBytes()));
        }
        return created(submit(request, photos));
    }

    private Claim submit(SubmitClaimRequest request, List<ClaimService.Photo> photos) {
        return claimService.submit(
                request.policyNumber(),
                request.plateNumber(),
                request.incidentDate(),
                request.description(),
                request.estimatedAmount(),
                photos,
                AuthenticatedUser.current().id());
    }

    private ResponseEntity<ClaimResponse> created(Claim claim) {
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(claim.getId())
                .toUri();
        return ResponseEntity.created(location).body(responses.toResponse(claim));
    }

    /** Staff see any claim; a policyholder only their own. */
    @GetMapping("/{claimId}")
    public ClaimResponse get(@PathVariable UUID claimId) {
        Claim claim = claimService.get(claimId);
        ClaimAccessPolicy.assertCanRead(claim, AuthenticatedUser.current());
        return responses.toResponse(claim);
    }

    /** Staff list everything (optionally by status); a policyholder gets only their own claims. */
    @GetMapping
    public Page<ClaimResponse> list(
            @RequestParam(required = false) ClaimStatus status,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        AuthenticatedUser user = AuthenticatedUser.current();
        Page<Claim> page = user.isStaff()
                ? claimService.list(status, q, pageable)
                : claimService.listOwnedBy(user.id(), status, q, pageable);
        return page.map(responses::toResponse);
    }

    @GetMapping("/{claimId}/photos/{photoId}")
    public ResponseEntity<byte[]> photo(@PathVariable UUID claimId, @PathVariable UUID photoId) {
        ClaimAccessPolicy.assertCanRead(claimService.get(claimId), AuthenticatedUser.current());
        ClaimPhoto photo = claimService.photo(claimId, photoId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.getContentType()))
                .body(photo.getData());
    }

    /** The owner (or an adjuster/admin) can withdraw. */
    @PostMapping("/{claimId}/withdraw")
    public ClaimResponse withdraw(@PathVariable UUID claimId) {
        ClaimAccessPolicy.assertCanWithdraw(claimService.get(claimId), AuthenticatedUser.current());
        return responses.toResponse(claimService.withdraw(claimId));
    }

    /**
     * Evidence behind the fraud flags: other claims for the same vehicle in the duplicate window,
     * and the full claim history of the same policyholder — what an investigator compares.
     */
    @GetMapping("/{claimId}/fraud-context")
    @PreAuthorize("hasAnyRole('ADJUSTER', 'FINANCE', 'ADMIN')")
    public FraudContextResponse fraudContext(@PathVariable UUID claimId) {
        var context = claimService.fraudContextOf(claimId);
        return new FraudContextResponse(
                context.duplicateCandidates().stream()
                        .map(responses::toResponse)
                        .toList(),
                context.duplicateTotal(),
                context.ownerClaims().stream().map(responses::toResponse).toList(),
                context.ownerClaimTotal());
    }

    public record FraudContextResponse(
            List<ClaimResponse> duplicateCandidates,
            long duplicateTotal,
            List<ClaimResponse> ownerClaims,
            long ownerClaimTotal) {}

    /** Every message sent to the policyholder about this claim. */
    @GetMapping("/{claimId}/communications")
    public List<CommunicationResponse> communications(@PathVariable UUID claimId) {
        ClaimAccessPolicy.assertCanRead(claimService.get(claimId), AuthenticatedUser.current());
        return claimService.communicationsOf(claimId).stream()
                .map(message -> new CommunicationResponse(
                        message.getId(),
                        message.getType().name(),
                        message.getSubject(),
                        message.getBody(),
                        message.getSentAt()))
                .toList();
    }

    public record CommunicationResponse(UUID id, String type, String subject, String body, java.time.Instant sentAt) {}

    /** The formal decision letter as PDF; available once a decision exists. */
    @GetMapping("/{claimId}/decision-document")
    public org.springframework.http.ResponseEntity<byte[]> decisionDocument(@PathVariable UUID claimId) {
        var claim = claimService.get(claimId);
        ClaimAccessPolicy.assertCanRead(claim, AuthenticatedUser.current());
        if (!decisionDocuments.hasDecision(claim)) {
            throw new IllegalStateException("No decision has been made on claim " + claim.getClaimNumber() + " yet");
        }
        byte[] pdf = decisionDocuments.render(claim, claimService.policyOf(claimId));
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=decision-" + claim.getClaimNumber() + ".pdf")
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    /** After an advance: finance releases the remaining payable amount. */
    @PostMapping("/{claimId}/pay-remainder")
    @PreAuthorize("hasAnyRole('FINANCE', 'ADMIN')")
    public ClaimResponse payRemainder(@PathVariable UUID claimId) {
        return responses.toResponse(claimService.payRemainder(claimId));
    }

    /** Every money movement on the claim: advances, the final settlement, retried payouts. */
    @GetMapping("/{claimId}/payments")
    public List<PaymentResponse> payments(@PathVariable UUID claimId) {
        ClaimAccessPolicy.assertCanRead(claimService.get(claimId), AuthenticatedUser.current());
        return claimService.paymentsOf(claimId).stream()
                .map(payment -> new PaymentResponse(
                        payment.getId(),
                        payment.getAmount(),
                        payment.getPaymentType().name(),
                        payment.getReference(),
                        payment.getIssuedAt()))
                .toList();
    }

    public record PaymentResponse(
            UUID id, java.math.BigDecimal amount, String paymentType, String reference, java.time.Instant issuedAt) {}

    @PostMapping("/{claimId}/retry-payout")
    @PreAuthorize("hasAnyRole('FINANCE', 'ADMIN')")
    public ClaimResponse retryPayout(
            @PathVariable UUID claimId, @Valid @RequestBody(required = false) RetryPayoutRequest request) {
        return responses.toResponse(
                claimService.retryPayout(claimId, request == null ? null : request.approvedAmount()));
    }
}
