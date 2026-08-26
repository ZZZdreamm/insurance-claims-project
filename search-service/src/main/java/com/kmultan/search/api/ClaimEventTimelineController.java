package com.kmultan.search.api;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/claims")
public class ClaimEventTimelineController {

    private final ClaimEventTimelineService timelineService;

    public ClaimEventTimelineController(ClaimEventTimelineService timelineService) {
        this.timelineService = timelineService;
    }

    /** Every fact recorded about the claim, oldest first; staff only (the policyholder view lives in claim-service). */
    @GetMapping("/{claimId}/events")
    public List<Map<String, Object>> timeline(@PathVariable UUID claimId) throws IOException {
        return timelineService.timeline(claimId);
    }
}
