package com.yeonwoo.askwiki.document;

import com.yeonwoo.askwiki.common.CreateDocumentRequest;
import com.yeonwoo.askwiki.common.CreateDocumentResponse;
import com.yeonwoo.askwiki.common.DocumentSummary;
import com.yeonwoo.askwiki.embedding.EmbeddingClient;
import com.yeonwoo.askwiki.embedding.EmbeddingCodec;
import com.yeonwoo.askwiki.search.IndexOutboxEvent;
import com.yeonwoo.askwiki.search.IndexOutboxRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final Chunker chunker;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingCodec embeddingCodec;
    private final IndexOutboxRepository outboxRepository;

    public DocumentService(
            DocumentRepository documentRepository,
            ChunkRepository chunkRepository,
            Chunker chunker,
            EmbeddingClient embeddingClient,
            EmbeddingCodec embeddingCodec,
            IndexOutboxRepository outboxRepository
    ) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.chunker = chunker;
        this.embeddingClient = embeddingClient;
        this.embeddingCodec = embeddingCodec;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public CreateDocumentResponse create(CreateDocumentRequest request) {
        Document document = documentRepository.save(new Document(request.title(), null));
        List<String> chunks = chunker.split(request.content());

        for (int i = 0; i < chunks.size(); i++) {
            String content = chunks.get(i);
            float[] embedding = embeddingClient.embed(content);
            Chunk saved = chunkRepository.save(new Chunk(
                    document.getId(),
                    i + 1,
                    content,
                    embeddingCodec.serialize(embedding),
                    approximateTokenCount(content)
            ));
            outboxRepository.save(new IndexOutboxEvent(
                    IndexOutboxEvent.EventType.CHUNK_ADDED,
                    saved.getId(),
                    document.getId()
            ));
        }

        return new CreateDocumentResponse(document.getId(), document.getTitle(), chunks.size());
    }

    @Transactional(readOnly = true)
    public List<DocumentSummary> list() {
        return documentRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
                .stream()
                .map(document -> new DocumentSummary(
                        document.getId(),
                        document.getTitle(),
                        Math.toIntExact(chunkRepository.countByDocumentId(document.getId())),
                        document.getCreatedAt().toString()
                ))
                .toList();
    }

    @Transactional
    public void delete(Long id) {
        documentRepository.deleteById(id);
        outboxRepository.save(new IndexOutboxEvent(
                IndexOutboxEvent.EventType.DOCUMENT_DELETED,
                null,
                id
        ));
    }

    private int approximateTokenCount(String text) {
        return Math.max(1, (int) Math.ceil(text.length() / 4.0));
    }
}
