package com.yeonwoo.askwiki.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChunkRepository extends JpaRepository<Chunk, Long> {

    List<Chunk> findByDocumentId(Long id);

    List<Chunk> findAllByOrderByIdAsc();

    long countByDocumentId(Long documentId);
}
