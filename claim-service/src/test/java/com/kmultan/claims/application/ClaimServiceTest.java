package com.kmultan.claims.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.kmultan.claims.application.assessment.Assessment;
import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.ClaimNotFoundException;
import com.kmultan.claims.domain.ClaimNumberGenerator;
import com.kmultan.claims.domain.ClaimPhoto;
import com.kmultan.claims.domain.ClaimPhotoRepository;
import com.kmultan.claims.domain.ClaimRepository;
import com.kmultan.claims.domain.ClaimStatus;
import com.kmultan.claims.domain.Severity;
import com.kmultan.claims.domain.event.ClaimEvent;
import com.kmultan.claims.domain.event.ClaimEventType;
import com.kmultan.claims.domain.event.DomainEventPublisher;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class ClaimServiceTest {

    @Mock
    ClaimRepository claimRepository;

    @Mock
    ClaimPhotoRepository claimPhotos;

    @Mock
    ClaimNumberGenerator claimNumbers;

    @Mock
    DomainEventPublisher eventPublisher;

    ClaimService claimService;

    @BeforeEach
    void setUp() {
        claimService = new ClaimService(
                claimRepository,
                claimPhotos,
                claimNumbers,
                eventPublisher,
                new ClaimMetrics(new SimpleMeterRegistry()),
                Duration.ofHours(48));
    }

    private ClaimEvent publishedEvent() {
        ArgumentCaptor<ClaimEvent> captor = ArgumentCaptor.forClass(ClaimEvent.class);
        verify(eventPublisher).publish(captor.capture());
        return captor.getValue();
    }

    @Test
    void submitStoresPhotosAndPublishesSubmittedWithPhotoIds() {
        when(claimNumbers.next()).thenReturn("CLM-2026-000007");
        when(claimRepository.save(any(Claim.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ClaimPhoto storedPhoto = new ClaimPhoto(UUID.randomUUID(), "image/jpeg", new byte[] {1, 2, 3});
        when(claimPhotos.findByClaimIdOrderByCreatedAt(any())).thenReturn(List.of(storedPhoto));

        Claim claim = claimService.submit(
                "POL-9",
                "KR 1A234",
                LocalDate.now(),
                "Windscreen cracked by stone",
                null,
                List.of(new ClaimService.Photo("image/jpeg", new byte[] {1, 2, 3})));

        assertThat(claim.getClaimNumber()).isEqualTo("CLM-2026-000007");
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.SUBMITTED);
        verify(claimPhotos).save(any(ClaimPhoto.class));
        ClaimEvent event = publishedEvent();
        assertThat(event.eventType()).isEqualTo(ClaimEventType.CLAIM_SUBMITTED);
        assertThat(event.claim().photoIds()).containsExactly(storedPhoto.getId());
    }

    @Test
    void getThrowsWhenMissing() {
        UUID claimId = UUID.randomUUID();
        when(claimRepository.findById(claimId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> claimService.get(claimId)).isInstanceOf(ClaimNotFoundException.class);
    }

    @Test
    void lateAssessmentForMovedOnClaimIsIgnored() {
        Claim claim = Claim.submit("n", "p", "x", LocalDate.now(), "some description", null);
        claim.withdraw();
        when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));

        claimService.completeAssessment(claim.getId(), new Assessment(Severity.MINOR, BigDecimal.TEN, "x"));

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.WITHDRAWN);
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void payoutIssuedForNonApprovedClaimRequestsReversal() {
        Claim claim = Claim.submit("n", "p", "x", LocalDate.now(), "some description", null);
        claim.completeAssessment(Severity.MINOR, null, "t", Instant.now());
        claim.approve(BigDecimal.TEN);
        claim.withdraw();
        when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));

        claimService.acceptPayout(claim.getId());

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.WITHDRAWN);
        assertThat(publishedEvent().eventType()).isEqualTo(ClaimEventType.PAYOUT_UNACCEPTED);
    }
}
