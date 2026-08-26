package com.kmultan.search.projection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class ClaimEventListener {

    private static final Logger log = LoggerFactory.getLogger(ClaimEventListener.class);

    private static final String SEQUENCE_HEADER = "sequence";

    private final ClaimDocumentIndexer documentIndexer;
    private final ClaimEventLogIndexer eventLogIndexer;
    private final ObjectMapper objectMapper;

    public ClaimEventListener(
            ClaimDocumentIndexer documentIndexer, ClaimEventLogIndexer eventLogIndexer, ObjectMapper objectMapper) {
        this.documentIndexer = documentIndexer;
        this.eventLogIndexer = eventLogIndexer;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${search.topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void onClaimEvent(ConsumerRecord<String, String> consumerRecord) throws IOException {
        try {
            ClaimEventEnvelope event = objectMapper.readValue(consumerRecord.value(), ClaimEventEnvelope.class);
            long sequence = sequenceOf(consumerRecord);
            eventLogIndexer.append(
                    event, sequence, objectMapper.readTree(consumerRecord.value())); // idempotent by event id
            boolean written = documentIndexer.index(ClaimDocument.from(event), sequence);
            log.debug("{} seq={} claim={} written={}", event.eventType(), sequence, event.claimId(), written);
        } catch (RuntimeException | IOException exception) {
            // the error handler retries and eventually dead-letters; make sure the cause is visible
            log.error(
                    "Failed to project {}-{}@{} key={}: {}",
                    consumerRecord.topic(),
                    consumerRecord.partition(),
                    consumerRecord.offset(),
                    consumerRecord.key(),
                    exception.toString(),
                    exception);
            throw exception;
        }
    }

    private static long sequenceOf(ConsumerRecord<String, String> consumerRecord) {
        Header sequenceHeader = consumerRecord.headers().lastHeader(SEQUENCE_HEADER);
        if (sequenceHeader == null) {
            throw new IllegalArgumentException(
                    "Record at offset " + consumerRecord.offset() + " has no '" + SEQUENCE_HEADER + "' header");
        }
        return Long.parseLong(new String(sequenceHeader.value(), StandardCharsets.UTF_8));
    }
}
