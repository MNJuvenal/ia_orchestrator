package fr.juvenal.ia_orchestrator.repository;

import fr.juvenal.ia_orchestrator.model.Job;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobRepository extends JpaRepository<Job, String> {
}
