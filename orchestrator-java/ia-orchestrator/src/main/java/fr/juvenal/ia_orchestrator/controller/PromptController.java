package fr.juvenal.ia_orchestrator.controller;

import fr.juvenal.ia_orchestrator.config.RabbitConfig;
import fr.juvenal.ia_orchestrator.model.ChatMessage;
import fr.juvenal.ia_orchestrator.repository.ChatMessageRepository;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import fr.juvenal.ia_orchestrator.repository.JobRepository;
import fr.juvenal.ia_orchestrator.model.Job;
import fr.juvenal.ia_orchestrator.service.SseService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.HashMap;
import java.util.List;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/ai")
public class PromptController {

    private final RabbitTemplate rabbitTemplate;
    private final ChatMessageRepository repository;
    private final JobRepository jobRepository;
    private final SseService sseService;

    public PromptController(RabbitTemplate rabbitTemplate, ChatMessageRepository repository, JobRepository jobRepository, SseService sseService) {
        this.rabbitTemplate = rabbitTemplate;
        this.repository = repository;
        this.jobRepository = jobRepository;
        this.sseService = sseService;
    }

    @PostMapping("/ask")
    public org.springframework.http.ResponseEntity<String> ask(
            @RequestBody String prompt,
            @RequestHeader(value = "X-History-Size", required = false) String historySize,
            @RequestHeader(value = "X-Session-Id", required = false) String incomingSessionId
    ) {
        // Use incoming session id if present, otherwise generate one and return it
        String sessionId = (incomingSessionId != null && !incomingSessionId.isBlank()) ? incomingSessionId : UUID.randomUUID().toString();

        // Persist user message
        try {
            ChatMessage saved = repository.save(new ChatMessage(prompt, "USER", sessionId));
            System.out.println("[DB] saved user message id=" + (saved != null ? saved.getId() : "null") + " session=" + sessionId);
        } catch (Exception e) {
            // Log and continue
            System.err.println("[DB] failed to save user message: " + e.getMessage());
        }

        // Create async job and persist
        String jobId = UUID.randomUUID().toString();
        Job job = new Job(jobId, sessionId, prompt);
        try {
            Job savedJob = jobRepository.save(job);
            System.out.println("[DB] created job id=" + (savedJob != null ? savedJob.getId() : "null") + " session=" + sessionId);
        } catch (Exception e) {
            System.err.println("[DB] failed to create job: " + e.getMessage());
        }

        // Prepare RabbitMQ message with headers (async)
        MessageProperties props = new MessageProperties();
        if (historySize != null) props.setHeader("history_size", historySize);
        props.setHeader("session_id", sessionId);
        props.setHeader("job_id", jobId);

        Message message = MessageBuilder
                .withBody(prompt.getBytes(StandardCharsets.UTF_8))
                .andProperties(props)
                .build();

        // Send (fire-and-forget) to tasks queue
        rabbitTemplate.send(RabbitConfig.QUEUE_NAME, message);

        // Return 202 with job id
        return org.springframework.http.ResponseEntity.accepted()
                .header("X-Job-Id", jobId)
                .header("X-Session-Id", sessionId)
                .body(jobId);
    }

    @GetMapping("/status/{jobId}")
    public org.springframework.http.ResponseEntity<?> getStatus(@PathVariable String jobId) {
        return jobRepository.findById(jobId)
                .map(job -> org.springframework.http.ResponseEntity.ok().body(job))
                .orElseGet(() -> org.springframework.http.ResponseEntity.notFound().build());
    }

    @GetMapping("/stream/{sessionId}")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stream(@PathVariable String sessionId) {
        return sseService.createEmitter(sessionId);
    }

    @GetMapping("/history/{sessionId}")
    public org.springframework.http.ResponseEntity<?> getHistory(
            @PathVariable String sessionId,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        try {
            Pageable p = PageRequest.of(0, Math.max(1, Math.min(200, limit)));
            var page = repository.findBySessionIdOrderByIdDesc(sessionId, p);
            // convert to chronological order and simple DTOs
            List<Map<String, String>> items = page.getContent().stream()
                    .sorted((a,b) -> a.getId().compareTo(b.getId()))
                    .map(m -> {
                        Map<String,String> mm = new HashMap<>();
                        mm.put("role", m.getRole());
                        mm.put("content", m.getContent());
                        return mm;
                    }).collect(Collectors.toList());
            return org.springframework.http.ResponseEntity.ok().body(items);
        } catch (Exception e) {
            System.err.println("[API] failed to fetch history: " + e.getMessage());
            return org.springframework.http.ResponseEntity.status(500).body("error");
        }
    }
}