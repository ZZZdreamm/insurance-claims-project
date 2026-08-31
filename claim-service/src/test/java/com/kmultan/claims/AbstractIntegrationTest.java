package com.kmultan.claims;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import com.kmultan.claims.domain.Policy;
import com.kmultan.claims.domain.PolicyRepository;
import com.kmultan.claims.infrastructure.consumers.FakeDownstreamServices;

/**
 * Shared container setup (one Postgres, one Kafka per JVM) plus in-JVM fakes of
 * assessment-service and payout-service that react to our events over the
 * real broker. Requires Docker. Skip with {@code mvn verify -DskipITs}.
 */
@Tag("integration")
@SpringBootTest
@Import(FakeDownstreamServices.class)
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @ServiceConnection
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");

    @ServiceConnection(name = "redis")
    protected static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        KAFKA.start();
        REDIS.start();
    }

    /** Policy numbers the integration tests submit against: open policies, no deductible, generous cap. */
    private static final List<String> TEST_POLICY_NUMBERS = List.of(
            "POL-1",
            "POL-123",
            "POL-CH",
            "POL-CT",
            "POL-DOWN",
            "POL-LC",
            "POL-OB",
            "POL-ORD",
            "POL-PERF",
            "POL-Q",
            "POL-RB",
            "POL-RD",
            "POL-SEC",
            "POL-ST",
            "POL-TR",
            "POL-TX");

    @Autowired
    protected PolicyRepository policyRepository;

    @BeforeEach
    void seedStandardTestPolicies() {
        for (String policyNumber : TEST_POLICY_NUMBERS) {
            if (!policyRepository.existsById(policyNumber)) {
                policyRepository.save(openTestPolicy(policyNumber));
            }
        }
    }

    protected static Policy openTestPolicy(String policyNumber) {
        return new Policy(
                policyNumber,
                null,
                Policy.CoverageType.OC,
                LocalDate.of(2020, 1, 1),
                LocalDate.of(2035, 12, 31),
                new BigDecimal("1000000.00"),
                BigDecimal.ZERO);
    }
}
