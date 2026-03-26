package fr.juvenal.ia_orchestrator.listener;

import fr.juvenal.ia_orchestrator.config.RabbitConfig;
import fr.juvenal.ia_orchestrator.model.Job;
import fr.juvenal.ia_orchestrator.model.ChatMessage;
import fr.juvenal.ia_orchestrator.repository.JobRepository;
import fr.juvenal.ia_orchestrator.repository.ChatMessageRepository;
import fr.juvenal.ia_orchestrator.service.SseService;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;

@Component
public class ResponsesListener {

    private final JobRepository jobRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final SseService sseService;

    public ResponsesListener(JobRepository jobRepository, ChatMessageRepository chatMessageRepository, SseService sseService) {
        this.jobRepository = jobRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.sseService = sseService;
    }

    @RabbitListener(queues = RabbitConfig.RESPONSES_QUEUE)
    @Transactional
    public void onMessage(Message message) {
        try {
            Map<String,Object> headers = message.getMessageProperties().getHeaders();
            String jobId = null;
            if (headers != null) {
                Object j = headers.get("job_id");
                if (j != null) jobId = j.toString();
            }
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            if (jobId != null) {
                Job job = jobRepository.findById(jobId).orElse(null);
                if (job != null) {
                    job.setResult(body);
                    job.setStatus("COMPLETED");
                    job.setUpdatedAt(LocalDateTime.now());
                    jobRepository.save(job);

                    // Also persist the assistant message into Postgres as the single source of truth
                    try {
                        String sessionId = job.getSessionId();
                        ChatMessage assistant = new ChatMessage(body, "ASSISTANT", sessionId);
                        ChatMessage saved = chatMessageRepository.save(assistant);
                        System.out.println("[DB] ResponsesListener saved assistant message id=" + (saved != null ? saved.getId() : "null") + " job=" + jobId);
                    } catch (Exception e) {
                        System.err.println("[DB] ResponsesListener failed to save assistant message: " + e.getMessage());
                    }

                    // Send SSE event to any listening clients for this session AFTER the DB transaction commits
                    try {
                        String sessionId = job.getSessionId();
                        var payload = java.util.Map.of("jobId", jobId, "result", body);
                        // Register synchronization so SSE is emitted only after successful commit
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                try {
                                    sseService.sendEvent(sessionId, "ai-response", payload);
                                } catch (Exception e) {
                                    System.err.println("[SSE] failed to emit event after commit: " + e.getMessage());
                                }
                            }
                        });
                    } catch (Exception e) {
                        System.err.println("[SSE] register synchronization failed: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[Listener] error processing response: " + e.getMessage());
        }
    }
}
