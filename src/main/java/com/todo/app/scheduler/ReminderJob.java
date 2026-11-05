package com.todo.app.scheduler;

import com.todo.app.config.JobProperties;
import com.todo.app.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Emails each user the tasks that are about to come due (or are overdue but unanswered), at most once
 * per task. Runs only when SMTP is configured and the job is enabled, because a scheduled job that
 * fires on every instance of a deployment would send every reminder twice.
 */
@Component
@ConditionalOnProperty(name = {"app.jobs.reminder.enabled", "spring.mail.host"})
public class ReminderJob {

    private static final Logger log = LoggerFactory.getLogger(ReminderJob.class);
    private static final int BATCH_SIZE = 500;
    private static final DateTimeFormatter DUE_FORMAT =
            DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault());

    private final TaskRepository taskRepository;
    private final MailSender mailSender;
    private final JobProperties.Reminder settings;

    public ReminderJob(TaskRepository taskRepository, MailSender mailSender,
                       JobProperties jobProperties) {
        this.taskRepository = taskRepository;
        this.mailSender = mailSender;
        this.settings = jobProperties.reminder();
    }

    @Scheduled(cron = "0 */10 * * * *")
    public void sendReminders() {
        Instant now = Instant.now();
        Instant from = now.minus(settings.overdueLookback());
        Instant to = now.plus(settings.window());
        List<TaskRepository.ReminderRow> due = taskRepository.findReminderRows(from, to, PageRequest.of(0, BATCH_SIZE));
        if (due.isEmpty()) {
            log.debug("Reminder scan found nothing between {} and {}", from, to);
            return;
        }
        int sent = 0;
        for (TaskRepository.ReminderRow row : due) {
            try {
                mailSender.send(message(row));
                sent++;
            } catch (RuntimeException deliveryFailure) {
                // Left unmarked, so the next scan retries this task; one bad address must not stop the batch.
                log.warn("Reminder for task {} could not be mailed: {}", row.getId(), deliveryFailure.getMessage());
            }
        }
        int marked = taskRepository.markRemindersSent(due.stream().map(TaskRepository.ReminderRow::getId).toList(), now);
        log.info("Reminder scan mailed {} of {} tasks and flagged {} as reminded", sent, due.size(), marked);
    }

    private SimpleMailMessage message(TaskRepository.ReminderRow row) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(settings.from());
        message.setTo(row.getOwnerEmail());
        message.setSubject("To-Do reminder: " + row.getTitle());
        message.setText("""
                Hi %s,

                This task is scheduled for %s:

                    %s

                Open your list to finish it or move the date: the reminder is sent once per task.

                -- To-Do
                """.formatted(row.getOwnerName(), DUE_FORMAT.format(row.getDueAt()), row.getTitle()));
        return message;
    }
}
