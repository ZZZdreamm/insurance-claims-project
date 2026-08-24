package com.kmultan.claims.application;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaimServiceTest {

    @Mock ClaimRepository repository;
    @Mock ClaimPhotoRepository photos;
    @Mock ClaimNumberGenerator numbers;
    @Mock DomainEventPublisher events;
    ClaimService service;

    @BeforeEach
    void setUp() {
        service = new ClaimService(repository, photos, numbers, events, new ClaimMetrics(new SimpleMeterRegistry()), Duration.ofHours(48));
    }

    private ClaimEvent lastEvent() {
        ArgumentCaptor<ClaimEvent> captor = ArgumentCaptor.forClass(ClaimEvent.class);
        verify(events).publish(captor.capture());
        return captor.getValue();
    }

    @Test
    void submitStoresPhotosAndPublishesSubmittedWithPhotoIds() {
        when(numbers.next()).thenReturn("CLM-2026-000007");
        when(repository.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));
        ClaimPhoto stored = new ClaimPhoto(UUID.randomUUID(), "image/jpeg", new byte[]{1, 2, 3});
        when(photos.findByClaimIdOrderByCreatedAt(any())).thenReturn(List.of(stored));

        Claim c = service.submit("POL-9", "KR 1A234", LocalDate.now(), "Windscreen cracked by stone", null,
                List.of(new ClaimService.Photo("image/jpeg", new byte[]{1, 2, 3})));

        assertThat(c.getClaimNumber()).isEqualTo("CLM-2026-000007");
        assertThat(c.getStatus()).isEqualTo(ClaimStatus.SUBMITTED);
        verify(photos).save(any(ClaimPhoto.class));
        ClaimEvent e = lastEvent();
        assertThat(e.eventType()).isEqualTo(ClaimEventType.CLAIM_SUBMITTED);
        assertThat(e.claim().photoIds()).containsExactly(stored.getId());
    }

    @Test
    void getThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(id)).isInstanceOf(ClaimNotFoundException.class);
    }

    @Test
    void lateAssessmentForMovedOnClaimIsIgnored() {
        Claim c = Claim.submit("n", "p", "x", LocalDate.now(), "some description", null);
        c.withdraw();
        when(repository.findById(c.getId())).thenReturn(Optional.of(c));

        service.completeAssessment(c.getId(), new com.kmultan.claims.application.assessment.Assessment(Severity.MINOR, BigDecimal.TEN, "x"));

        assertThat(c.getStatus()).isEqualTo(ClaimStatus.WITHDRAWN);
        verify(events, never()).publish(any());
    }

    @Test
    void payoutIssuedForNonApprovedClaimRequestsReversal() {
        Claim c = Claim.submit("n", "p", "x", LocalDate.now(), "some description", null);
        c.completeAssessment(Severity.MINOR, null, "t", Instant.now());
        c.approve(BigDecimal.TEN);
        c.withdraw();
        when(repository.findById(c.getId())).thenReturn(Optional.of(c));

        service.acceptPayout(c.getId());

        assertThat(c.getStatus()).isEqualTo(ClaimStatus.WITHDRAWN);
        assertThat(lastEvent().eventType()).isEqualTo(ClaimEventType.PAYOUT_UNACCEPTED);
    }
}
