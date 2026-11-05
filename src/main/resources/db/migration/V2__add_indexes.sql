-- Indexes for the two query shapes the API runs most often:
--   1. listing/filtering a user's tasks (owner + deleted + optional status/priority/due date/sort)
--   2. the scheduled overdue and reminder jobs, which scan open tasks by due date.

CREATE INDEX ix_tasks_owner_listing ON tasks (owner_id, deleted, status, due_at);
CREATE INDEX ix_tasks_owner_priority ON tasks (owner_id, deleted, priority);
CREATE INDEX ix_tasks_project ON tasks (project_id);
CREATE INDEX ix_tasks_due_open ON tasks (due_at) WHERE deleted = FALSE AND status <> 'DONE';

CREATE INDEX ix_projects_owner ON projects (owner_id, deleted);
CREATE INDEX ix_tags_owner_name ON tags (owner_id, deleted, name);
CREATE INDEX ix_users_deleted ON users (deleted);
