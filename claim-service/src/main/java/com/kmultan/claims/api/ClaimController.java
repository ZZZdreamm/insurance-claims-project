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
    private final ClaimResponseAssembler responses;

    public ClaimController(ClaimService claimService, ClaimResponseAssembler responses) {
        this.claimService = claimService;
        this.responses = responses;
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
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        AuthenticatedUser user = AuthenticatedUser.current();
        Page<Claim> page = user.isStaff()
                ? claimService.list(status, pageable)
                : claimService.listOwnedBy(user.id(), status, pageable);
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

    /** PAYOUT_FAILED -> APPROVED, optionally with a corrected amount; payout-service reacts to the new CLAIM_APPROVED. */
    @PostMapping("/{claimId}/retry-payout")
    @PreAuthorize("hasAnyRole('FINANCE', 'ADMIN')")
    public ClaimResponse retryPayout(
            @PathVariable UUID claimId, @Valid @RequestBody(required = false) RetryPayoutRequest request) {
        return responses.toResponse(
                claimService.retryPayout(claimId, request == null ? null : request.approvedAmount()));
    }
}
