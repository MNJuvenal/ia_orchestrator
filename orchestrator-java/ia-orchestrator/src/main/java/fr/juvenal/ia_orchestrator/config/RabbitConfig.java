package fr.juvenal.ia_orchestrator.config;

import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    
    public static final String QUEUE_NAME = "ai_tasks_queue";
    public static final String RESPONSES_QUEUE = "ai_responses";

    @Bean
    public Queue aiQueue() {
        return new Queue(QUEUE_NAME, true);
    }

    @Bean
    public Queue responsesQueue() {
        return new Queue(RESPONSES_QUEUE, true);
    }
}