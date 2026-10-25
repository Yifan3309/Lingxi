package org.example.repository;

import org.example.domain.NoteAsset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NoteAssetRepository extends JpaRepository<NoteAsset, String> {

    List<NoteAsset> findByNoteId(String noteId);
}
