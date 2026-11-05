package com.todo.app.mapper;

import com.todo.app.dto.ProjectView;
import com.todo.app.dto.TagView;
import com.todo.app.dto.UserView;
import com.todo.app.entity.Project;
import com.todo.app.entity.Tag;
import com.todo.app.entity.User;
import org.springframework.stereotype.Component;

@Component
public class ReferenceMapper {

    public ProjectView toView(Project project, long openTaskCount) {
        return new ProjectView(project.getId(), project.getName(), project.getDescription(), project.getColor(),
                openTaskCount, project.getVersion());
    }

    public TagView toView(Tag tag) {
        return new TagView(tag.getId(), tag.getName(), tag.getVersion());
    }

    public UserView toView(User user) {
        return new UserView(user.getId(), user.getEmail(), user.getFullName(), user.getRole().name());
    }
}
