package com.yeonwoo.askwiki.search;

import com.yeonwoo.askwiki.document.ChunkRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class IndexOutboxRelay {

    private final IndexOutboxRepository outboxRepository;
    private final ChunkRepository chunkRepository;
    private final InMemoryVectorIndex vectorIndex;

    public IndexOutboxRelay(
            IndexOutboxRepository outboxRepository,
            ChunkRepository chunkRepository,
            InMemoryVectorIndex vectorIndex
    ) {
        this.outboxRepository = outboxRepository;
        this.chunkRepository = chunkRepository;
        this.vectorIndex = vectorIndex;
    }

    @Scheduled(fixedDelayString = "${askwiki.outbox.poll-interval-ms:1000}")
    @Transactional
    public int processPendingEvents() {
        var events = outboxRepository.findByStatusOrderByIdAsc(IndexOutboxEvent.Status.PENDING);
        for (IndexOutboxEvent event : events) {
            switch (event.getEventType()) {
                case CHUNK_ADDED -> chunkRepository.findById(event.getChunkId()).ifPresent(vectorIndex::add);
                case DOCUMENT_DELETED -> vectorIndex.removeDocument(event.getDocumentId());
            }
            event.markProcessed();
        }
        return events.size();
    }
}
