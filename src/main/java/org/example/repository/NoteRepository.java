package org.example.repository;

import org.example.domain.Note;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NoteRepository extends JpaRepository<Note, String> {

    List<Note> findByTitleContainingIgnoreCaseOrContentContainingIgnoreCase(String title, String content, Sort sort);

    List<Note> findByTagsContainingIgnoreCase(String tag, Sort sort);
}
