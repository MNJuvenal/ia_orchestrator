package fr.juvenal.ia_orchestrator.repository;

import fr.juvenal.ia_orchestrator.model.ChatMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    Page<ChatMessage> findBySessionIdOrderByIdDesc(String sessionId, Pageable pageable);
}
