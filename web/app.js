const $ = (id) => document.getElementById(id);

const els = {
  sidebar: $("sidebar"), sessionList: $("sessionList"), newChatBtn: $("newChatBtn"),
  openSidebar: $("openSidebar"), closeSidebar: $("closeSidebar"),
  sessionTitle: $("sessionTitle"), sessionMeta: $("sessionMeta"), desktopBadge: $("desktopBadge"),
  readonlyBanner: $("readonlyBanner"), apiSelect: $("apiSelect"), modelSelect: $("modelSelect"),
  effortSelect: $("effortSelect"), messages: $("messages"), composer: $("composer"),
  sendBtn: $("sendBtn"), stopBtn: $("stopBtn"), liveTools: $("liveTools"),
  bridgeStatusDot: $("bridgeStatusDot"), bridgeStatusText: $("bridgeStatusText"), contextBar: $("contextBar"),
  newChatDialog: $("newChatDialog"), newChatForm: $("newChatForm"), newCwd: $("newCwd"),
  workspaceOptions: $("workspaceOptions"), newTitle: $("newTitle"), toast: $("toast")
};

const state = {
  token: null, profiles: [], efforts: [], sessions: [], workspaces: [], currentProfile: null,
  selectedId: null, selected: null, selectedConfig: null, selectedContext: null, activeTaskId: null,
  refreshTimer: null
};

function bootToken() {
  const url = new URL(location.href);
  const hashParams = new URLSearchParams(url.hash.replace(/^#/, ""));
  const fromHash = hashParams.get("token");
  const fromQuery = url.searchParams.get("token");
  const supplied = fromHash || fromQuery;
  if (supplied) {
    localStorage.setItem("claudeBridgeToken", supplied);
    url.searchParams.delete("token");
    url.hash = "";
    history.replaceState({}, "", url.pathname + url.search);
  }
  state.token = supplied || localStorage.getItem("claudeBridgeToken");
}

async function api(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  if (state.token) headers.Authorization = `Bearer ${state.token}`;
  if (options.body && typeof options.body !== "string") {
    headers["Content-Type"] = "application/json";
    options.body = JSON.stringify(options.body);
  }
  const res = await fetch(path, { ...options, headers, cache: "no-store" });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
  return data;
}

function toast(text) {
  els.toast.textContent = text;
  els.toast.classList.remove("hidden");
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => els.toast.classList.add("hidden"), 2800);
}

function formatTime(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  return new Intl.DateTimeFormat("zh-CN", { month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(d);
}

function shortCwd(cwd) {
  if (!cwd) return "";
  const parts = cwd.replaceAll("/", "\\").split("\\").filter(Boolean);
  return parts.slice(-2).join("\\") || cwd;
}

function renderSessions() {
  els.sessionList.innerHTML = "";
  for (const s of state.sessions) {
    const btn = document.createElement("button");
    btn.className = `session-item ${s.id === state.selectedId ? "active" : ""}`;
    btn.dataset.id = s.id;
    const title = document.createElement("div");
    title.className = "session-item-title";
    title.textContent = s.title || "新对话";
    const meta = document.createElement("div");
    meta.className = "session-item-meta";
    if (s.active) {
      const dot = document.createElement("span");
      dot.className = "mini-dot";
      meta.appendChild(dot);
    }
    const text = document.createElement("span");
    text.textContent = [shortCwd(s.cwd), formatTime(s.updatedAt)].filter(Boolean).join(" · ");
    meta.appendChild(text);
    btn.append(title, meta);
    btn.addEventListener("click", () => selectSession(s.id));
    els.sessionList.appendChild(btn);
  }
}

function providers() {
  const names = [];
  for (const p of state.profiles) if (!names.includes(p.apiName)) names.push(p.apiName);
  return names;
}

function profileForConfig() {
  const id = state.selectedConfig?.profile;
  return state.profiles.find((p) => p.id === id) || state.profiles.find((p) => p.current) || state.profiles[0];
}

function fillControls() {
  const profile = profileForConfig();
  const providerNames = providers();
  els.apiSelect.innerHTML = "";
  for (const name of providerNames) {
    const o = document.createElement("option"); o.value = name; o.textContent = name; els.apiSelect.appendChild(o);
  }
  if (profile) els.apiSelect.value = profile.apiName;
  fillModels(profile?.apiName);

  els.effortSelect.innerHTML = "";
  for (const e of state.efforts) {
    const o = document.createElement("option"); o.value = e; o.textContent = e; els.effortSelect.appendChild(o);
  }
  els.effortSelect.value = state.selectedConfig?.effort || "high";

  const disabled = !state.selectedId || !!state.selected?.readOnly || !!state.activeTaskId;
  els.apiSelect.disabled = disabled;
  els.modelSelect.disabled = disabled;
  els.effortSelect.disabled = disabled;
}

function fillModels(apiName) {
  els.modelSelect.innerHTML = "";
  const list = state.profiles.filter((p) => p.apiName === apiName);
  for (const p of list) {
    const o = document.createElement("option");
    o.value = p.id;
    o.textContent = p.model;
    o.title = p.id;
    els.modelSelect.appendChild(o);
  }
  const currentId = state.selectedConfig?.profile;
  if (list.some((p) => p.id === currentId)) els.modelSelect.value = currentId;
  else if (list[0]) els.modelSelect.value = list[0].id;
}

function setReadOnlyUI() {
  const readOnly = !!state.selected?.readOnly;
  els.desktopBadge.classList.toggle("hidden", !readOnly);
  els.readonlyBanner.classList.toggle("hidden", !readOnly);
  els.composer.disabled = readOnly || !state.selectedId || !!state.activeTaskId;
  els.sendBtn.disabled = readOnly || !state.selectedId || !!state.activeTaskId;
  fillControls();
}

function toolCard(tool) {
  const card = document.createElement("div");
  card.className = "tool-card";
  const name = document.createElement("div"); name.className = "tool-name"; name.textContent = `工具 · ${tool.name || "tool"}`;
  card.appendChild(name);
  if (tool.preview) {
    const p = document.createElement("div"); p.className = "tool-preview"; p.textContent = tool.preview; card.appendChild(p);
  }
  return card;
}

function messageNode(msg, live = false) {
  const row = document.createElement("div");
  row.className = `message-row ${msg.role}`;
  const box = document.createElement("div");
  box.className = `message ${msg.role}${live ? " live caret" : ""}`;
  if (msg.model && msg.role === "assistant") {
    const tag = document.createElement("span"); tag.className = "model-tag"; tag.textContent = msg.model; box.appendChild(tag);
  }
  const text = document.createElement("span"); text.className = "message-text"; text.textContent = msg.text || ""; box.appendChild(text);
  for (const tool of msg.tools || []) box.appendChild(toolCard(tool));
  row.appendChild(box);
  return row;
}

function renderMessages(messages) {
  els.messages.innerHTML = "";
  if (!messages.length) {
    const div = document.createElement("div"); div.className = "empty-state";
    div.innerHTML = "<h2>新对话</h2><p>输入消息后，会在桌面上启动一个独立的 Claude Code 轮次。</p>";
    els.messages.appendChild(div);
  } else {
    for (const m of messages) els.messages.appendChild(messageNode(m));
  }
  scrollBottom();
}

function scrollBottom() { requestAnimationFrame(() => { els.messages.scrollTop = els.messages.scrollHeight; }); }

async function loadMessages() {
  if (!state.selectedId) return;
  const data = await api(`/api/session/${state.selectedId}/messages?limit=160`);
  renderMessages(data.messages || []);
}

async function refreshSessions(keepSelection = true) {
  const data = await api("/api/sessions?limit=80");
  state.sessions = data.sessions || [];
  if (keepSelection && state.selectedId) state.selected = state.sessions.find((s) => s.id === state.selectedId) || state.selected;
  renderSessions();
  setReadOnlyUI();
}

function renderContext() {
  const ctx = state.selectedContext;
  if (!ctx) {
    els.contextBar.textContent = "上下文尚未核对";
    els.contextBar.title = "";
    return;
  }
  const memory = (ctx.memory || []).length ? "Memory ✓" : "Memory —";
  els.contextBar.textContent = `上下文 ✓ ${ctx.settingSources.join("/")} · CLAUDE.md ${ctx.claudeMd.length} · ${memory} · Skills/Plugins/MCP ✓`;
  els.contextBar.title = [
    `CWD: ${ctx.cwd}`,
    ...ctx.claudeMd.map((x) => `CLAUDE.md: ${x}`),
    ...ctx.settings.map((x) => `Settings: ${x}`),
    ...ctx.memory.map((x) => `Memory: ${x}`)
  ].join("\n");
}

function fillWorkspaceOptions() {
  els.workspaceOptions.innerHTML = "";
  for (const cwd of state.workspaces) {
    const o = document.createElement("option");
    o.value = cwd;
    els.workspaceOptions.appendChild(o);
  }
}

async function selectSession(id) {
  state.selectedId = id;
  state.selected = state.sessions.find((s) => s.id === id) || null;
  renderSessions();
  els.sidebar.classList.remove("open");
  const detail = await api(`/api/session/${id}`);
  state.selected = detail.session || state.selected;
  state.selectedConfig = detail.config || {};
  state.selectedContext = detail.context || null;
  els.sessionTitle.textContent = state.selected?.title || "Claude Code";
  els.sessionMeta.textContent = [shortCwd(state.selected?.cwd || state.selectedConfig.cwd), id].filter(Boolean).join(" · ");
  fillControls();
  renderContext();
  setReadOnlyUI();
  await loadMessages();
}

async function createSession() {
  const profile = state.profiles.find((p) => p.current) || state.profiles[0];
  const data = await api("/api/session", {
    method: "POST",
    body: {
      cwd: els.newCwd.value.trim() || "C:\\",
      title: els.newTitle.value.trim() || "新对话",
      profile: profile?.id,
      model: profile?.model,
      effort: "high"
    }
  });
  els.newChatDialog.close();
  els.newTitle.value = "";
  await refreshSessions(false);
  await selectSession(data.sessionId);
}

async function patchConfig(patch) {
  if (!state.selectedId) return;
  const data = await api(`/api/session/${state.selectedId}`, { method: "PATCH", body: patch });
  state.selectedConfig = data.config;
  await refreshSessions(true);
  fillControls();
}

function appendOptimisticUser(text) {
  const empty = els.messages.querySelector(".empty-state"); if (empty) empty.remove();
  els.messages.appendChild(messageNode({ role: "user", text, tools: [] }));
  scrollBottom();
}

function ensureLiveAssistant() {
  let row = $("liveAssistantRow");
  if (row) return row;
  row = messageNode({ role: "assistant", text: "", tools: [] }, true);
  row.id = "liveAssistantRow";
  els.messages.appendChild(row);
  scrollBottom();
  return row;
}

function updateLiveTask(task) {
  const row = ensureLiveAssistant();
  const textEl = row.querySelector(".message-text");
  textEl.textContent = task.partialText || "";
  const toolEvents = (task.events || []).filter((e) => e.type === "tool_start" || e.type === "tool");
  els.liveTools.classList.toggle("hidden", !toolEvents.length);
  els.liveTools.textContent = toolEvents.length ? `正在使用：${toolEvents.slice(-3).map((x) => x.name || "tool").join(" · ")}` : "";
  scrollBottom();
}

async function pollTask(taskId) {
  while (state.activeTaskId === taskId) {
    const data = await api(`/api/task/${taskId}`);
    const task = data.task;
    updateLiveTask(task);
    if (!["queued", "running", "stopping"].includes(task.status)) {
      state.activeTaskId = null;
      els.stopBtn.classList.add("hidden");
      els.liveTools.classList.add("hidden");
      setReadOnlyUI();
      if (task.status === "error") toast(task.error || "Claude Code 执行失败");
      else if (task.status === "stopped") toast("已停止本轮 Claude Code");
      await new Promise((r) => setTimeout(r, 350));
      if (task.localCommand) {
        const row = $("liveAssistantRow"); if (row) row.classList.remove("caret");
      } else {
        await loadMessages();
      }
      const live = $("liveAssistantRow"); if (live && !task.localCommand) live.remove();
      await refreshSessions(true);
      return;
    }
    await new Promise((r) => setTimeout(r, 550));
  }
}

async function sendMessage() {
  const text = els.composer.value.trim();
  if (!text || !state.selectedId || state.activeTaskId || state.selected?.readOnly) return;
  els.composer.value = "";
  autoGrow();
  appendOptimisticUser(text);
  try {
    const data = await api(`/api/session/${state.selectedId}/message`, { method: "POST", body: { text } });
    state.activeTaskId = data.taskId;
    els.stopBtn.classList.remove("hidden");
    setReadOnlyUI();
    await pollTask(data.taskId);
  } catch (e) {
    toast(e.message);
    state.activeTaskId = null;
    setReadOnlyUI();
    await loadMessages();
  }
}

async function stopTask() {
  if (!state.activeTaskId) return;
  try { await api(`/api/task/${state.activeTaskId}/stop`, { method: "POST", body: {} }); }
  catch (e) { toast(e.message); }
}

function autoGrow() {
  els.composer.style.height = "auto";
  els.composer.style.height = `${Math.min(180, els.composer.scrollHeight)}px`;
}

async function bootstrap() {
  bootToken();
  if (!state.token) {
    els.bridgeStatusText.textContent = "缺少访问令牌";
    toast("请使用启动脚本显示的带 token 地址首次访问");
    return;
  }
  try {
    const data = await api("/api/bootstrap");
    state.profiles = data.profiles || [];
    state.efforts = data.efforts || ["low", "medium", "high", "xhigh", "max"];
    state.sessions = data.sessions || [];
    state.workspaces = data.workspaces || [];
    state.currentProfile = data.currentProfile;
    fillWorkspaceOptions();
    els.bridgeStatusDot.classList.add("online");
    els.bridgeStatusText.textContent = "桌面桥已连接";
    renderSessions();
    if (state.sessions.length) await selectSession(state.sessions[0].id);
    else fillControls();
    state.refreshTimer = setInterval(async () => {
      try {
        await refreshSessions(true);
        if (state.selectedId && !state.activeTaskId) await loadMessages();
      } catch (_) {}
    }, 2500);
  } catch (e) {
    els.bridgeStatusText.textContent = "连接失败";
    toast(e.message);
  }
}

els.newChatBtn.addEventListener("click", () => { els.newChatDialog.showModal(); els.sidebar.classList.remove("open"); });
els.openSidebar.addEventListener("click", () => els.sidebar.classList.add("open"));
els.closeSidebar.addEventListener("click", () => els.sidebar.classList.remove("open"));
els.newChatForm.addEventListener("submit", async (e) => {
  if (e.submitter?.value === "cancel") return;
  e.preventDefault();
  try { await createSession(); } catch (err) { toast(err.message); }
});
els.apiSelect.addEventListener("change", async () => {
  fillModels(els.apiSelect.value);
  const p = state.profiles.find((x) => x.id === els.modelSelect.value);
  if (p) await patchConfig({ profile: p.id, model: p.model });
});
els.modelSelect.addEventListener("change", async () => {
  const p = state.profiles.find((x) => x.id === els.modelSelect.value);
  if (p) await patchConfig({ profile: p.id, model: p.model });
});
els.effortSelect.addEventListener("change", () => patchConfig({ effort: els.effortSelect.value }).catch((e) => toast(e.message)));
els.sendBtn.addEventListener("click", sendMessage);
els.stopBtn.addEventListener("click", stopTask);
els.composer.addEventListener("input", autoGrow);
els.composer.addEventListener("keydown", (e) => {
  if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); sendMessage(); }
});

bootstrap();
