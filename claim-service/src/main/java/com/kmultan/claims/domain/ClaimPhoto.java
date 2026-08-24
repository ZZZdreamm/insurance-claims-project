package com.kmultan.claims.domain;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Photo bytes live in Postgres for the demo (a few hundred KB each, one DB to
 * run). Behind a StorageAdapter this would be S3/MinIO; the read endpoint and
 * the event shape (photo ids, not bytes) would not change.
 */
@Entity
@Table(name = "claim_photo")
public class ClaimPhoto {

    @Id
    private UUID id;

    @Column(name = "claim_id", nullable = false, updatable = false)
    private UUID claimId;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Basic(fetch = FetchType.LAZY)
    @Column(nullable = false)
    private byte[] data;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ClaimPhoto() {}

    public ClaimPhoto(UUID claimId, String contentType, byte[] data) {
        this.id = UUID.randomUUID();
        this.claimId = claimId;
        this.contentType = contentType;
        this.data = data;
        this.sizeBytes = data.length;
    }

    public UUID getId() { return id; }
    public UUID getClaimId() { return claimId; }
    public String getContentType() { return contentType; }
    public int getSizeBytes() { return sizeBytes; }
    public byte[] getData() { return data; }
}
