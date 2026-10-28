package org.example.repository;

import org.example.domain.NoteImport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NoteImportRepository extends JpaRepository<NoteImport, String> {

    Optional<NoteImport> findBySourcePath(String sourcePath);
}
