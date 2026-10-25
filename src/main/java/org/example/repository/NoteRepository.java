package org.example.repository;

import org.example.domain.Note;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface NoteRepository extends JpaRepository<Note, String> {

    List<Note> findByTitleContainingIgnoreCaseOrContentContainingIgnoreCase(String title, String content, Sort sort);

    List<Note> findByTagsContainingIgnoreCase(String tag, Sort sort);

    /** 每日/每周回顾（M3）：取时间窗内更新的笔记，最新在前 */
    List<Note> findByUpdatedAtAfter(Instant after, Sort sort);
}
