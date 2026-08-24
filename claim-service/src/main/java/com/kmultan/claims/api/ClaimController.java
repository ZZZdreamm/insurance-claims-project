package com.kmultan.claims.api;

import com.kmultan.claims.api.ClaimDtos.ClaimResponse;
import com.kmultan.claims.api.ClaimDtos.RetryPayoutRequest;
import com.kmultan.claims.api.ClaimDtos.SubmitClaimRequest;
import com.kmultan.claims.application.ClaimService;
import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.ClaimPhoto;
import com.kmultan.claims.domain.ClaimStatus;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/claims")
public class ClaimController {

    private static final Set<String> IMAGE_TYPES = Set.of(MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, "image/webp");

    private final ClaimService service;

    public ClaimController(ClaimService service) {
        this.service = service;
    }

    private ClaimResponse response(Claim c) {
        return ClaimResponse.from(c, service.photosOf(c.getId()).stream().map(ClaimPhoto::getId).toList());
    }

    /** JSON submit: no photos, triage falls back to the text model. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ClaimResponse> submit(@Valid @RequestBody SubmitClaimRequest req) {
        return created(service.submit(req.policyNumber(), req.plateNumber(), req.incidentDate(), req.description(), req.estimatedAmount(), List.of()));
    }

    /** Multipart submit: {@code claim} JSON part + zero or more {@code photos} image parts. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ClaimResponse> submitWithPhotos(@Valid @RequestPart("claim") SubmitClaimRequest req,
                                                          @RequestPart(value = "photos", required = false) List<MultipartFile> photos) throws IOException {
        List<ClaimService.Photo> uploads = new java.util.ArrayList<>();
        for (MultipartFile f : photos == null ? List.<MultipartFile>of() : photos) {
            if (f.isEmpty()) continue;
            if (!IMAGE_TYPES.contains(f.getContentType())) {
                throw new IllegalArgumentException("Unsupported photo type: " + f.getContentType());
            }
            uploads.add(new ClaimService.Photo(f.getContentType(), f.getBytes()));
        }
        return created(service.submit(req.policyNumber(), req.plateNumber(), req.incidentDate(), req.description(), req.estimatedAmount(), uploads));
    }

    private ResponseEntity<ClaimResponse> created(Claim claim) {
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(claim.getId()).toUri();
        return ResponseEntity.created(location).body(response(claim));
    }

    @GetMapping("/{id}")
    public ClaimResponse get(@PathVariable UUID id) {
        return response(service.get(id));
    }

    @GetMapping
    public Page<ClaimResponse> list(@RequestParam(required = false) ClaimStatus status,
                                    @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return service.list(status, pageable).map(this::response);
    }

    @GetMapping("/{id}/photos/{photoId}")
    public ResponseEntity<byte[]> photo(@PathVariable UUID id, @PathVariable UUID photoId) {
        ClaimPhoto p = service.photo(id, photoId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(p.getContentType())).body(p.getData());
    }

    @PostMapping("/{id}/withdraw")
    public ClaimResponse withdraw(@PathVariable UUID id) {
        return response(service.withdraw(id));
    }

    /** PAYOUT_FAILED -> APPROVED, optionally with a corrected amount; payout-service reacts to the new CLAIM_APPROVED. */
    @PostMapping("/{id}/retry-payout")
    public ClaimResponse retryPayout(@PathVariable UUID id, @Valid @RequestBody(required = false) RetryPayoutRequest body) {
        return response(service.retryPayout(id, body == null ? null : body.approvedAmount()));
    }
}
