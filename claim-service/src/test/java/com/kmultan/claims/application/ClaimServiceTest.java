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
import com.kmultan.claims.domain.ClaimPaymentRepository;
import com.kmultan.claims.domain.ClaimPhoto;
import com.kmultan.claims.domain.ClaimPhotoRepository;
import com.kmultan.claims.domain.ClaimRepository;
import com.kmultan.claims.domain.ClaimReserveRepository;
import com.kmultan.claims.domain.ClaimStatus;
import com.kmultan.claims.domain.CustomerCommunicationRepository;
import com.kmultan.claims.domain.Policy;
import com.kmultan.claims.domain.PolicyRepository;
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

    @Mock
    PolicyRepository policyRepository;

    @Mock
    ClaimReserveRepository reserveRepository;

    @Mock
    ClaimPaymentRepository paymentRepository;

    @Mock
    CustomerCommunicationRepository communicationRepository;

    ClaimService claimService;

    @BeforeEach
    void setUp() {
        claimService = new ClaimService(
                claimRepository,
                claimPhotos,
                claimNumbers,
                policyRepository,
                reserveRepository,
                paymentRepository,
                new FraudScreeningService(claimRepository, claimPhotos),
                new CustomerCommunicationService(communicationRepository),
                eventPublisher,
                new ClaimMetrics(new SimpleMeterRegistry()),
                Duration.ofHours(48),
                new BigDecimal("10000"));
    }

    private ClaimEvent publishedEvent() {
        ArgumentCaptor<ClaimEvent> captor = ArgumentCaptor.forClass(ClaimEvent.class);
        verify(eventPublisher).publish(captor.capture());
        return captor.getValue();
    }

    @Test
    void submitStoresPhotosAndPublishesSubmittedWithPhotoIds() {
        when(policyRepository.findById("POL-9"))
                .thenReturn(Optional.of(new Policy(
                        "POL-9",
                        null,
                        Policy.CoverageType.OC,
                        LocalDate.now().minusYears(1),
                        LocalDate.now().plusYears(1),
                        new BigDecimal("100000"),
                        BigDecimal.ZERO)));
        when(claimNumbers.next()).thenReturn("CLM-2026-000007");
        when(claimRepository.save(any(Claim.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ClaimPhoto storedPhoto = new ClaimPhoto(UUID.randomUUID(), "image/jpeg", new byte[] {1, 2, 3});
        when(claimPhotos.save(any(ClaimPhoto.class))).thenReturn(storedPhoto);
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
        verify(reserveRepository).save(any());
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
        claim.approve(new Claim.Settlement(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO), BigDecimal.TEN);
        claim.withdraw();
        when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));

        claimService.acceptPayout(claim.getId());

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.WITHDRAWN);
        assertThat(publishedEvent().eventType()).isEqualTo(ClaimEventType.PAYOUT_UNACCEPTED);
    }
}
