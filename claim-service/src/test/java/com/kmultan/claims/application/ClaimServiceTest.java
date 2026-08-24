package com.kmultan.claims.application;

import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.application.workflow.ClaimWorkflow;
import com.kmultan.claims.domain.ClaimNotFoundException;
import com.kmultan.claims.domain.ClaimNumberGenerator;
import com.kmultan.claims.domain.ClaimRepository;
import com.kmultan.claims.domain.ClaimStatus;
import com.kmultan.claims.domain.event.ClaimEvent;
import com.kmultan.claims.domain.event.ClaimEventType;
import com.kmultan.claims.domain.event.DomainEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaimServiceTest {

    @Mock ClaimRepository repository;
    @Mock ClaimNumberGenerator numbers;
    @Mock DomainEventPublisher events;
    @Mock ClaimWorkflow workflow;
    @Spy ClaimMetrics metrics = new ClaimMetrics(new SimpleMeterRegistry());
    @InjectMocks ClaimService service;

    @Test
    void submitAssignsGeneratedNumberAndPersists() {
        when(numbers.next()).thenReturn("CLM-2026-000007");
        when(repository.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));

        Claim c = service.submit("POL-9", "KR 1A234", LocalDate.now(), "Windscreen cracked by stone", null);

        assertThat(c.getClaimNumber()).isEqualTo("CLM-2026-000007");
        assertThat(c.getStatus()).isEqualTo(ClaimStatus.SUBMITTED);

        ArgumentCaptor<ClaimEvent> captor = ArgumentCaptor.forClass(ClaimEvent.class);
        verify(events).publish(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(ClaimEventType.CLAIM_SUBMITTED);
        assertThat(captor.getValue().claimId()).isEqualTo(c.getId());
        assertThat(captor.getValue().claim().claimNumber()).isEqualTo("CLM-2026-000007");
        verify(workflow).start(c.getId());
    }

    @Test
    void getThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(id)).isInstanceOf(ClaimNotFoundException.class);
    }

    @Test
    void approveMutatesLoadedAggregate() {
        Claim c = Claim.submit("n", "p", "x", LocalDate.now(), "some description", null);
        c.startAssessment();
        c.completeAssessment(null);
        when(repository.findById(c.getId())).thenReturn(Optional.of(c));

        Claim result = service.approve(c.getId(), new BigDecimal("500"));

        assertThat(result.getStatus()).isEqualTo(ClaimStatus.APPROVED);
        assertThat(result.getApprovedAmount()).isEqualByComparingTo("500");
    }
}
