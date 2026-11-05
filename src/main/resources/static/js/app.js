const API = '/api';
const TOKEN_KEY = 'todo.accessToken';
const USER_KEY = 'todo.user';

const state = {
    token: localStorage.getItem(TOKEN_KEY),
    user: JSON.parse(localStorage.getItem(USER_KEY) || 'null'),
    projects: [],
    tags: [],
    stats: null,
    page: 0,
    size: 20,
    total: 0,
    totalPages: 0,
    descending: false,
    editingId: null,
    selected: new Set()
};

const el = (id) => document.getElementById(id);
const tasks = el('tasks');

/* ---------- api ---------- */

async function call(path, options = {}) {
    const headers = Object.assign({'Accept': 'application/json'}, options.headers);
    if (options.body !== undefined) {
        headers['Content-Type'] = 'application/json';
    }
    if (state.token) {
        headers['Authorization'] = 'Bearer ' + state.token;
    }
    const response = await fetch(API + path, Object.assign({}, options, {
        headers,
        body: options.body === undefined ? undefined : JSON.stringify(options.body)
    }));
    if (response.status === 204) {
        return null;
    }
    const payload = await response.json().catch(() => ({message: 'The server returned an unreadable response'}));
    if (!response.ok) {
        if (response.status === 401) {
            signOut();
        }
        const error = new Error(payload.message || response.statusText);
        error.fields = payload.fields || {};
        error.code = payload.error;
        throw error;
    }
    return payload;
}

const query = () => {
    const params = new URLSearchParams();
    const put = (key, value) => {
        if (value !== '' && value !== null && value !== undefined) {
            params.set(key, value);
        }
    };
    put('status', el('status').value);
    put('priority', el('priority').value);
    put('projectId', el('project').value);
    put('tagId', el('tag').value);
    if (el('overdue').checked) {
        params.set('overdue', 'true');
    }
    put('q', el('search').value.trim());
    params.set('page', String(state.page));
    params.set('size', String(state.size));
    params.set('sortBy', el('sortBy').value);
    params.set('descending', String(state.descending));
    return params.toString();
};

/* ---------- session ---------- */

function signedIn() {
    return Boolean(state.token && state.user);
}

function paintSession() {
    const hasSession = signedIn();
    el('auth').classList.toggle('hidden', hasSession);
    el('workspace').classList.toggle('hidden', !hasSession);
    el('who').classList.toggle('hidden', !hasSession);
    el('signout').classList.toggle('hidden', !hasSession);
    if (hasSession) {
        el('who').textContent = state.user.email + (state.user.role === 'ADMIN' ? ' (admin)' : '');
    }
}

function signOut() {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    state.token = null;
    state.user = null;
    paintSession();
}

async function storeSession(auth) {
    state.token = auth.accessToken;
    state.user = auth.user;
    localStorage.setItem(TOKEN_KEY, auth.accessToken);
    localStorage.setItem(USER_KEY, JSON.stringify(auth.user));
    paintSession();
    await loadWorkspace();
}

/* ---------- workspace ---------- */

async function loadWorkspace() {
    if (!signedIn()) {
        return;
    }
    await Promise.all([loadReferences(), loadStats(), loadTasks()]);
}

async function loadReferences() {
    const [projects, tags] = await Promise.all([call('/projects?size=100'), call('/tags')]);
    state.projects = projects.content;
    state.tags = tags;
    fillSelect(el('project'), state.projects, 'Any project');
    fillSelect(document.querySelector('#task-form [name=projectId]'), state.projects, 'None');
    fillSelect(el('tag'), state.tags, 'Any tag');
}

function fillSelect(select, items, placeholder) {
    const current = select.value;
    select.innerHTML = '';
    if (placeholder) {
        const option = document.createElement('option');
        option.value = '';
        option.textContent = placeholder;
        select.appendChild(option);
    }
    items.forEach((item) => {
        const option = document.createElement('option');
        option.value = String(item.id);
        option.textContent = item.name;
        select.appendChild(option);
    });
    if (items.some((item) => String(item.id) === current)) {
        select.value = current;
    }
}

async function loadStats() {
    const stats = await call('/tasks/stats');
    state.stats = stats;
    el('stats').replaceChildren(
        statTile('Total', stats.total),
        statTile('Open', stats.todo + stats.inProgress),
        statTile('In progress', stats.inProgress),
        statTile('Overdue', stats.overdue, stats.overdue > 0 ? 'alert' : ''),
        statTile('Done', stats.done)
    );
}

function statTile(label, value, extraClass = '') {
    const tile = document.createElement('div');
    tile.className = 'stat ' + extraClass;
    const number = document.createElement('strong');
    number.textContent = String(value);
    const text = document.createElement('span');
    text.textContent = label;
    tile.append(number, text);
    return tile;
}

async function loadTasks() {
    const page = await call('/tasks?' + query());
    state.total = page.totalElements;
    state.totalPages = page.totalPages;
    state.page = page.page;
    state.selected.clear();
    tasks.replaceChildren(...page.content.map(taskRow));
    el('empty').classList.toggle('hidden', page.content.length > 0);
    paintPager();
    paintBulk();
}

function taskRow(task) {
    const row = document.createElement('li');
    row.className = 'task ' + task.status.toLowerCase() + (task.overdue ? ' overdue' : '');
    row.dataset.id = task.id;

    const checkbox = document.createElement('input');
    checkbox.type = 'checkbox';
    checkbox.className = 'pick';
    checkbox.setAttribute('aria-label', 'Select ' + task.title);
    checkbox.addEventListener('change', () => {
        if (checkbox.checked) {
            state.selected.add(task.id);
        } else {
            state.selected.delete(task.id);
        }
        paintBulk();
    });

    const main = document.createElement('div');
    main.className = 'main';

    const heading = document.createElement('div');
    heading.className = 'heading';
    const title = document.createElement('span');
    title.className = 'title';
    title.textContent = task.title;
    const status = document.createElement('select');
    status.className = 'status';
    [['TODO', 'To do'], ['IN_PROGRESS', 'In progress'], ['DONE', 'Done']].forEach(([value, label]) => {
        const option = document.createElement('option');
        option.value = value;
        option.textContent = label;
        status.appendChild(option);
    });
    status.value = task.status;
    status.addEventListener('change', () => patchTask(task, {status: status.value, version: task.version}));
    heading.append(title, status);

    const meta = document.createElement('div');
    meta.className = 'meta';
    if (task.projectName) {
        const project = document.createElement('span');
        project.className = 'chip project';
        project.textContent = task.projectName;
        meta.appendChild(project);
    }
    (task.tags || []).forEach((name) => {
        const chip = document.createElement('span');
        chip.className = 'chip tag';
        chip.textContent = '#' + name;
        meta.appendChild(chip);
    });
    if (task.dueAt) {
        const due = document.createElement('span');
        due.className = 'due' + (task.overdue ? ' late' : '');
        due.textContent = (task.overdue ? 'overdue ' : 'due ') + formatInstant(task.dueAt);
        meta.appendChild(due);
    }
    const priority = document.createElement('select');
    priority.className = 'priority';
    ['LOW', 'MEDIUM', 'HIGH', 'URGENT'].forEach((value) => {
        const option = document.createElement('option');
        option.value = value;
        option.textContent = value.charAt(0) + value.slice(1).toLowerCase();
        priority.appendChild(option);
    });
    priority.value = task.priority;
    priority.addEventListener('change', () => patchTask(task, {priority: priority.value, version: task.version}));
    meta.appendChild(priority);

    main.append(heading);
    if (task.description) {
        const description = document.createElement('p');
        description.className = 'description';
        description.textContent = task.description;
        main.appendChild(description);
    }
    main.appendChild(meta);

    const actions = document.createElement('div');
    actions.className = 'actions';
    actions.append(button('Edit', () => openEditor(task)), button('Delete', () => removeTask(task), 'danger'));

    row.append(checkbox, main, actions);
    return row;
}

function button(label, handler, extraClass = '') {
    const node = document.createElement('button');
    node.type = 'button';
    node.className = 'ghost ' + extraClass;
    node.textContent = label;
    node.addEventListener('click', handler);
    return node;
}

function formatInstant(value) {
    const date = new Date(value);
    const today = new Date();
    const sameDay = date.toDateString() === today.toDateString();
    const time = date.toLocaleTimeString([], {hour: '2-digit', minute: '2-digit'});
    return sameDay ? 'today ' + time : date.toLocaleDateString([], {day: 'numeric', month: 'short'}) + ' ' + time;
}

function paintPager() {
    const start = state.total === 0 ? 0 : state.page * state.size + 1;
    const end = Math.min((state.page + 1) * state.size, state.total);
    el('pager').replaceChildren(
            pageButton('Previous', state.page > 0, () => {
                state.page -= 1;
                loadTasks();
            }),
            counter(start, end, state.total),
            pageButton('Next', state.page + 1 < state.totalPages, () => {
                state.page += 1;
                loadTasks();
            })
    );
}

function counter(start, end, total) {
    const node = document.createElement('span');
    node.className = 'quiet';
    node.textContent = total === 0 ? 'No tasks' : start + '-' + end + ' of ' + total;
    return node;
}

function pageButton(label, enabled, handler) {
    const node = document.createElement('button');
    node.type = 'button';
    node.className = 'ghost';
    node.textContent = label;
    node.disabled = !enabled;
    node.addEventListener('click', handler);
    return node;
}

function paintBulk() {
    el('bulk-done').classList.toggle('hidden', state.selected.size === 0);
}

/* ---------- writes ---------- */

async function patchTask(task, body) {
    try {
        await call('/tasks/' + task.id, {method: 'PATCH', body});
        await Promise.all([loadTasks(), loadStats()]);
    } catch (failure) {
        report(tasks, failure, task.title);
        await loadTasks();
    }
}

async function removeTask(task) {
    if (!confirm('Delete "' + task.title + '"? It disappears from every list.')) {
        return;
    }
    try {
        await call('/tasks/' + task.id, {method: 'DELETE'});
        await Promise.all([loadTasks(), loadStats()]);
    } catch (failure) {
        report(tasks, failure, task.title);
    }
}

async function bulkDone() {
    try {
        await call('/tasks/bulk-status', {method: 'POST', body: {ids: [...state.selected], status: 'DONE'}});
        await Promise.all([loadTasks(), loadStats()]);
    } catch (failure) {
        report(tasks, failure, 'bulk update');
    }
}

function report(host, failure, context) {
    const line = document.createElement('p');
    line.className = 'error banner';
    line.textContent = (failure.code ? failure.code + ': ' : '') + failure.message;
    host.parentElement.insertBefore(line, host);
    setTimeout(() => line.remove(), 5000);
    console.warn('Failed request while handling ' + context, failure);
}

/* ---------- task editor ---------- */

function openEditor(task) {
    state.editingId = task ? task.id : null;
    el('editor-title').textContent = task ? 'Edit task' : 'New task';
    const form = el('task-form');
    form.title.value = task ? task.title : '';
    form.description.value = task && task.description ? task.description : '';
    form.status.value = task ? task.status : 'TODO';
    form.priority.value = task ? task.priority : 'MEDIUM';
    form.dueAt.value = task && task.dueAt ? toLocalInput(task.dueAt) : '';
    form.projectId.value = task && task.projectId ? String(task.projectId) : '';
    form.tags.value = task && task.tags ? task.tags.join(', ') : '';
    form.version.value = task ? String(task.version) : '';
    clearErrors(el('editor-errors'));
    el('editor').showModal();
}

function toLocalInput(iso) {
    const date = new Date(iso);
    const offset = date.getTimezoneOffset() * 60000;
    return new Date(date.getTime() - offset).toISOString().slice(0, 16);
}

async function saveTask(event) {
    event.preventDefault();
    const form = el('task-form');
    const body = {
        title: form.title.value.trim(),
        description: form.description.value.trim() || null,
        status: form.status.value,
        priority: form.priority.value,
        dueAt: form.dueAt.value ? new Date(form.dueAt.value).toISOString() : null,
        projectId: form.projectId.value ? Number(form.projectId.value) : null,
        tags: form.tags.value.split(',').map((tag) => tag.trim()).filter(Boolean)
    };
    if (form.version.value) {
        body.version = Number(form.version.value);
    }
    try {
        if (state.editingId) {
            await call('/tasks/' + state.editingId, {method: 'PUT', body});
        } else {
            await call('/tasks', {method: 'POST', body});
        }
        el('editor').close();
        await Promise.all([loadReferences(), loadTasks(), loadStats()]);
    } catch (failure) {
        showErrors(el('editor-errors'), failure);
    }
}

/* ---------- project editor ---------- */

function openProjectEditor() {
    const form = el('project-form');
    form.reset();
    form.version.value = '';
    clearErrors(el('project-errors'));
    el('projectEditor').showModal();
}

async function saveProject(event) {
    event.preventDefault();
    const form = el('project-form');
    try {
        await call('/projects', {method: 'POST', body: {
            name: form.name.value.trim(),
            description: form.description.value.trim() || null,
            color: form.color.value
        }});
        el('projectEditor').close();
        await Promise.all([loadReferences(), loadTasks()]);
    } catch (failure) {
        showErrors(el('project-errors'), failure);
    }
}

function showErrors(host, failure) {
    clearErrors(host);
    const lines = Object.entries(failure.fields || {});
    lines.forEach(([field, message]) => appendError(host, field + ': ' + message));
    if (lines.length === 0) {
        appendError(host, failure.message);
    }
}

function appendError(host, text) {
    const item = document.createElement('li');
    item.textContent = text;
    host.appendChild(item);
}

function clearErrors(host) {
    host.replaceChildren();
}

/* ---------- auth form ---------- */

let registerMode = false;

async function submitAuth(event) {
    event.preventDefault();
    const form = el('auth-form');
    const body = {email: form.email.value.trim(), password: form.password.value};
    clearErrors(el('auth-errors'));
    try {
        if (registerMode) {
            body.fullName = form.fullName.value.trim();
            await storeSession(await call('/auth/register', {method: 'POST', body}));
        } else {
            await storeSession(await call('/auth/login', {method: 'POST', body}));
        }
    } catch (failure) {
        showErrors(el('auth-errors'), failure);
    }
}

function toggleAuthMode() {
    registerMode = !registerMode;
    el('auth-title').textContent = registerMode ? 'Create an account' : 'Sign in';
    el('auth-submit').textContent = registerMode ? 'Register' : 'Sign in';
    el('auth-switch').textContent = registerMode ? 'Have an account?' : 'Need an account?';
    el('fullNameField').classList.toggle('hidden', !registerMode);
    clearErrors(el('auth-errors'));
}

/* ---------- wiring ---------- */

el('auth-form').addEventListener('submit', submitAuth);
el('auth-switch').addEventListener('click', toggleAuthMode);
el('signout').addEventListener('click', signOut);
el('task-form').addEventListener('submit', saveTask);
el('editor-cancel').addEventListener('click', () => el('editor').close());
el('project-form').addEventListener('submit', saveProject);
el('project-cancel').addEventListener('click', () => el('projectEditor').close());
el('bulk-done').addEventListener('click', bulkDone);
el('toggleOrder').addEventListener('click', () => {
    state.descending = !state.descending;
    el('toggleOrder').textContent = state.descending ? 'Descending' : 'Ascending';
    state.page = 0;
    loadTasks();
});
['status', 'priority', 'project', 'tag', 'sortBy', 'overdue'].forEach((id) =>
    el(id).addEventListener('change', () => {
        state.page = 0;
        loadTasks().catch((failure) => report(tasks, failure, id));
    }));
let searchTimer;
el('search').addEventListener('input', () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => {
        state.page = 0;
        loadTasks().catch((failure) => report(tasks, failure, 'search'));
    }, 300);
});
el('new-task').addEventListener('click', () => openEditor(null));

const addProject = document.createElement('button');
addProject.type = 'button';
addProject.className = 'ghost';
addProject.textContent = '+ Project';
addProject.addEventListener('click', openProjectEditor);
el('project').parentElement.appendChild(addProject);

if (signedIn()) {
    loadWorkspace().catch(() => signOut());
}
paintSession();
