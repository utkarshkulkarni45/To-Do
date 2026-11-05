package com.todo.app.scheduler;

import com.todo.app.config.JobProperties;
import com.todo.app.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Soft-deleted rows are kept so that a delete can be undone, but they are not free: they hold index
 * space forever. When the retention job is switched on, rows older than the retention period are
 * removed in one statement each day.
 */
@Component
@ConditionalOnProperty(name = "app.jobs.purge.enabled", havingValue = "true")
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final TaskRepository taskRepository;
    private final JobProperties.Purge settings;

    public RetentionJob(TaskRepository taskRepository, JobProperties jobProperties) {
        this.taskRepository = taskRepository;
        this.settings = jobProperties.purge();
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void purgeDeletedTasks() {
        Instant before = Instant.now().minus(settings.retention());
        int removed = taskRepository.purgeSoftDeleted(before);
        if (removed > 0) {
            log.info("Purged {} soft-deleted tasks older than {}", removed, before);
        }
    }
}
