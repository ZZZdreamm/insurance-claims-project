package com.kmultan.claims.api;

import com.kmultan.claims.application.ClaimService;
import com.kmultan.claims.application.workflow.ClaimWorkflow;
import com.kmultan.claims.application.workflow.ReviewDecision;
import com.kmultan.claims.application.workflow.ReviewTask;
import com.kmultan.claims.domain.Claim;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Adjuster work queue: the human-task step of the claim-handling process. */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final ClaimWorkflow workflow;
    private final ClaimService claims;

    public TaskController(ClaimWorkflow workflow, ClaimService claims) {
        this.workflow = workflow;
        this.claims = claims;
    }

    public record ReviewTaskResponse(String taskId, UUID claimId, String claimNumber, String plateNumber,
                                     String description, BigDecimal estimatedAmount, String severity,
                                     String assignee, Instant createdAt, Instant dueAt, boolean escalated) {
        static ReviewTaskResponse of(ReviewTask t, Claim c) {
            return new ReviewTaskResponse(t.taskId(), t.claimId(), c.getClaimNumber(), c.getPlateNumber(),
                    c.getDescription(), c.getEstimatedAmount(), t.severity(), t.assignee(),
                    t.createdAt(), t.dueAt(), t.escalated());
        }
    }

    public record ClaimTaskRequest(@NotBlank String assignee) {}

    public record CompleteTaskRequest(@NotNull ReviewDecision.Decision decision,
                                      @Positive BigDecimal approvedAmount,
                                      String reason) {}

    @GetMapping
    public List<ReviewTaskResponse> open() {
        return workflow.openReviewTasks().stream()
                .map(t -> ReviewTaskResponse.of(t, claims.get(t.claimId())))
                .toList();
    }

    @PostMapping("/{taskId}/claim")
    public ResponseEntity<Void> claim(@PathVariable String taskId, @Valid @RequestBody ClaimTaskRequest body) {
        workflow.claimTask(taskId, body.assignee());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{taskId}/unclaim")
    public ResponseEntity<Void> unclaim(@PathVariable String taskId) {
        workflow.unclaimTask(taskId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{taskId}/complete")
    public ResponseEntity<Void> complete(@PathVariable String taskId, @Valid @RequestBody CompleteTaskRequest body) {
        workflow.completeReview(taskId, new ReviewDecision(body.decision(), body.approvedAmount(), body.reason()));
        return ResponseEntity.noContent().build();
    }
}
