package com.kmultan.claims;

import org.junit.jupiter.api.Tag;
import com.kmultan.claims.infrastructure.payout.FakePayoutParticipant;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Shared container setup. Containers are static and started once per JVM, so all
 * ITs reuse one Postgres and one Kafka (Spring's context cache does the rest).
 * Requires Docker. Skip with {@code mvn verify -DskipITs}.
 */
@Tag("integration")
@SpringBootTest
@Import(FakePayoutParticipant.class)
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @ServiceConnection
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");

    static {
        POSTGRES.start();
        KAFKA.start();
    }
}
