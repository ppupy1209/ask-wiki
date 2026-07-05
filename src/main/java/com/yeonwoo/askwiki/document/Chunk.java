package com.yeonwoo.askwiki.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "chunk")
public class Chunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "embedding", nullable = false, columnDefinition = "json")
    private String embedding;

    @Column(name = "token_count", nullable = false)
    private int tokenCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Chunk() {
    }

    public Chunk(Long documentId, int seq, String content, String embedding, int tokenCount) {
        this.documentId = documentId;
        this.seq = seq;
        this.content = content;
        this.embedding = embedding;
        this.tokenCount = tokenCount;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public int getSeq() {
        return seq;
    }

    public String getContent() {
        return content;
    }

    public String getEmbedding() {
        return embedding;
    }

    public int getTokenCount() {
        return tokenCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
