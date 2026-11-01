package org.example.repository;

import org.example.domain.Concept;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConceptRepository extends JpaRepository<Concept, String> {

    Optional<Concept> findByNormKey(String normKey);
}
