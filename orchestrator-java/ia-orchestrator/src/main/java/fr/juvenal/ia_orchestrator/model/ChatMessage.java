package fr.juvenal.ia_orchestrator.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "messages")
public class ChatMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(columnDefinition = "TEXT")
    private String content;

    private String role; // "USER" ou "ASSISTANT"

    @Column(name = "session_id")
    private String sessionId;

    private LocalDateTime timestamp;

    public ChatMessage() {}

    public ChatMessage(String content, String role, String sessionId) {
        this.content = content;
        this.role = role;
        this.sessionId = sessionId;
        this.timestamp = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
}
