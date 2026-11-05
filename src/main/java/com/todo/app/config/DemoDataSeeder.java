package com.todo.app.config;

import com.todo.app.entity.Project;
import com.todo.app.entity.Tag;
import com.todo.app.entity.Task;
import com.todo.app.entity.User;
import com.todo.app.enums.Role;
import com.todo.app.enums.TaskPriority;
import com.todo.app.enums.TaskStatus;
import com.todo.app.repository.ProjectRepository;
import com.todo.app.repository.TagRepository;
import com.todo.app.repository.TaskRepository;
import com.todo.app.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

/**
 * Puts a working dataset in front of a reviewer on first boot: one ADMIN, one demo account with a
 * password in the README, and tasks in every state so that filters, sorting, the overdue flag and the
 * stats endpoint have something to show. Guarded by app.seed-demo-data and by "already seeded", so it
 * never touches a database that has data.
 */
@Component
@ConditionalOnProperty(name = "app.seed-demo-data", havingValue = "true")
public class DemoDataSeeder implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final String DEMO_PASSWORD = "Summer2026!";

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final TagRepository tagRepository;
    private final TaskRepository taskRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties properties;

    public DemoDataSeeder(UserRepository userRepository, ProjectRepository projectRepository,
                          TagRepository tagRepository, TaskRepository taskRepository,
                          PasswordEncoder passwordEncoder, AppProperties properties) {
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.tagRepository = tagRepository;
        this.taskRepository = taskRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (userRepository.count() > 0) {
            return;
        }
        User admin = userRepository.save(new User(properties.adminEmail(), "Site Administrator",
                passwordEncoder.encode(DEMO_PASSWORD), Role.ADMIN));
        User demo = userRepository.save(new User(properties.demoEmail(), "Demo User",
                passwordEncoder.encode(DEMO_PASSWORD), Role.USER));
        log.info("Seeded ADMIN {} and demo account {}", admin.getEmail(), demo.getEmail());

        Project university = projectRepository.save(new Project(demo.getId(), "University",
                "Coursework and submissions", "#2F6F53"));
        Project home = projectRepository.save(new Project(demo.getId(), "Home", null, "#B0562F"));
        Tag homework = tagRepository.save(new Tag(demo.getId(), "homework"));
        Tag reading = tagRepository.save(new Tag(demo.getId(), "reading"));
        Tag errand = tagRepository.save(new Tag(demo.getId(), "errand"));

        Instant now = Instant.now();
        List<Task> seeded = taskRepository.saveAll(List.of(
                task(demo.getId(), "Submit databases assignment 3", "Normalise the schema and upload the PDF.",
                        TaskStatus.IN_PROGRESS, TaskPriority.HIGH, now.plus(2, ChronoUnit.DAYS), university, Set.of(homework)),
                task(demo.getId(), "Read chapter 7 of Operating Systems", "Concurrency and deadlocks.",
                        TaskStatus.TODO, TaskPriority.MEDIUM, now.plus(5, ChronoUnit.DAYS), university, Set.of(reading)),
                task(demo.getId(), "Pay the electricity bill", null,
                        TaskStatus.TODO, TaskPriority.URGENT, now.minus(1, ChronoUnit.DAYS), home, Set.of(errand)),
                task(demo.getId(), "Reply to the internship email", "They asked for a second interview slot.",
                        TaskStatus.TODO, TaskPriority.HIGH, now.minus(3, ChronoUnit.HOURS), null, Set.of()),
                task(demo.getId(), "Book the return train", "Cheaper if booked a week ahead.",
                        TaskStatus.TODO, TaskPriority.LOW, now.plus(9, ChronoUnit.DAYS), home, Set.of(errand)),
                task(demo.getId(), "Finish the Spring Security notes", "Filter chain, provider manager, JWT layout.",
                        TaskStatus.DONE, TaskPriority.MEDIUM, now.minus(2, ChronoUnit.DAYS), university, Set.of(reading))));
        log.info("Seeded {} demo tasks for {}", seeded.size(), demo.getEmail());
    }

    private Task task(long ownerId, String title, String description, TaskStatus status, TaskPriority priority,
                      Instant dueAt, Project project, Set<Tag> tags) {
        Task task = new Task(ownerId, title);
        task.setDescription(description);
        task.setStatus(status);
        task.setPriority(priority);
        task.setDueAt(dueAt);
        task.setProject(project);
        task.setTags(tags);
        if (status == TaskStatus.DONE) {
            task.setCompletedAt(Instant.now().minus(1, ChronoUnit.DAYS));
        }
        return task;
    }
}
