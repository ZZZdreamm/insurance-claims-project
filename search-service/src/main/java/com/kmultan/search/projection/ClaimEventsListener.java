package com.kmultan.search.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class ClaimEventsListener {

    private static final Logger log = LoggerFactory.getLogger(ClaimEventsListener.class);

    private final ClaimIndexer indexer;
    private final ObjectMapper json;

    public ClaimEventsListener(ClaimIndexer indexer, ObjectMapper json) {
        this.indexer = indexer;
        this.json = json;
    }

    @KafkaListener(topics = "${search.topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void on(ConsumerRecord<String, String> record) throws IOException {
        try {
            ClaimEventEnvelope event = json.readValue(record.value(), ClaimEventEnvelope.class);
            long sequence = sequenceOf(record);
            boolean written = indexer.index(ClaimDocument.from(event), sequence);
            log.debug("{} seq={} claim={} written={}", event.eventType(), sequence, event.claimId(), written);
        } catch (RuntimeException | IOException e) {
            // the error handler retries and eventually dead-letters; make sure the cause is visible
            log.error("Failed to project {}-{}@{} key={}: {}", record.topic(), record.partition(), record.offset(), record.key(), e.toString(), e);
            throw e;
        }
    }

    private static long sequenceOf(ConsumerRecord<String, String> record) {
        Header h = record.headers().lastHeader("sequence");
        if (h == null) {
            throw new IllegalArgumentException("Record at offset " + record.offset() + " has no 'sequence' header");
        }
        return Long.parseLong(new String(h.value(), StandardCharsets.UTF_8));
    }
}
