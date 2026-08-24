package com.kmultan.claims.api;

import com.kmultan.claims.api.ClaimDtos.ApproveRequest;
import com.kmultan.claims.api.ClaimDtos.AssessmentResult;
import com.kmultan.claims.api.ClaimDtos.ClaimResponse;
import com.kmultan.claims.api.ClaimDtos.RejectRequest;
import com.kmultan.claims.api.ClaimDtos.SubmitClaimRequest;
import com.kmultan.claims.application.ClaimService;
import com.kmultan.claims.domain.Claim;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/claims")
public class ClaimController {

    private final ClaimService service;

    public ClaimController(ClaimService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ClaimResponse> submit(@Valid @RequestBody SubmitClaimRequest req) {
        Claim claim = service.submit(req.policyNumber(), req.plateNumber(), req.incidentDate(),
                req.description(), req.estimatedAmount());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(claim.getId()).toUri();
        return ResponseEntity.created(location).body(ClaimResponse.from(claim));
    }

    @GetMapping("/{id}")
    public ClaimResponse get(@PathVariable UUID id) {
        return ClaimResponse.from(service.get(id));
    }

    @GetMapping
    public Page<ClaimResponse> list(@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                                    Pageable pageable) {
        return service.list(pageable).map(ClaimResponse::from);
    }

    @PostMapping("/{id}/start-assessment")
    public ClaimResponse startAssessment(@PathVariable UUID id) {
        return ClaimResponse.from(service.startAssessment(id));
    }

    @PostMapping("/{id}/complete-assessment")
    public ClaimResponse completeAssessment(@PathVariable UUID id, @Valid @RequestBody AssessmentResult body) {
        return ClaimResponse.from(service.completeAssessment(id, body.assessedAmount()));
    }

    @PostMapping("/{id}/approve")
    public ClaimResponse approve(@PathVariable UUID id, @Valid @RequestBody ApproveRequest body) {
        return ClaimResponse.from(service.approve(id, body.approvedAmount()));
    }

    @PostMapping("/{id}/reject")
    public ClaimResponse reject(@PathVariable UUID id, @Valid @RequestBody RejectRequest body) {
        return ClaimResponse.from(service.reject(id, body.reason()));
    }

    @PostMapping("/{id}/withdraw")
    public ClaimResponse withdraw(@PathVariable UUID id) {
        return ClaimResponse.from(service.withdraw(id));
    }
}
