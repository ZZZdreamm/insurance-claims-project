package com.kmultan.search.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/search")
@Validated
public class SearchController {

    private final ClaimSearchService claimSearchService;

    public SearchController(ClaimSearchService claimSearchService) {
        this.claimSearchService = claimSearchService;
    }

    /** e.g. {@code /api/v1/search?q=wa1234&status=PENDING_REVIEW} — fuzzy on plate, policy, claim number, description. */
    @GetMapping
    public ClaimSearchService.SearchResult search(@RequestParam(name = "q", required = false) String queryText,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(defaultValue = "0") @Min(0) int page,
                                                  @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size)
            throws IOException {
        return claimSearchService.search(queryText, status, page, size);
    }
}
