package org.example.repository;

import org.example.domain.NoteChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NoteChunkRepository extends JpaRepository<NoteChunk, String> {

    void deleteByNoteId(String noteId);

    List<NoteChunk> findByNoteIdOrderByChunkIndex(String noteId);
}
