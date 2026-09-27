from __future__ import annotations

import argparse
import ctypes
import json
import mimetypes
import os
import queue
import secrets
import shutil
import signal
import socket
import subprocess
import threading
import time
import uuid
from collections import deque
from datetime import datetime
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, unquote, urlparse

APP_NAME = "Claude Mobile Bridge"
ROOT = Path(__file__).resolve().parent
WEB_DIR = ROOT / "web"
DATA_DIR = ROOT / "data"
STATE_FILE = DATA_DIR / "bridge-state.json"
TOKEN_FILE = DATA_DIR / "auth-token.txt"
PID_FILE = DATA_DIR / "server.pid"
CLAUDE_HOME = Path.home() / ".claude"
PROFILES_DIR = CLAUDE_HOME / "profiles"
PROJECTS_DIR = CLAUDE_HOME / "projects"
DEFAULT_EFFORTS = ["low", "medium", "high", "xhigh", "max"]

CLAUDE_CANDIDATES = [
    Path(r"D:\npm-global\node_modules\@anthropic-ai\claude-code\bin\claude.exe"),
]

TASKS: dict[str, dict[str, Any]] = {}
TASK_PROCESSES: dict[str, subprocess.Popen[str]] = {}
TASK_LOCK = threading.RLock()
STATE_LOCK = threading.RLock()
CONSOLE_LOCK = threading.RLock()


def now_iso() -> str:
    return datetime.now().astimezone().isoformat(timespec="seconds")


def ensure_data_dir() -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)


def load_or_create_token() -> str:
    ensure_data_dir()
    if TOKEN_FILE.exists():
        token = TOKEN_FILE.read_text(encoding="utf-8").strip()
        if token:
            return token
    token = secrets.token_urlsafe(32)
    TOKEN_FILE.write_text(token, encoding="utf-8")
    return token


def load_state() -> dict[str, Any]:
    ensure_data_dir()
    if not STATE_FILE.exists():
        return {"sessions": {}}
    try:
        obj = json.loads(STATE_FILE.read_text(encoding="utf-8"))
        if not isinstance(obj, dict):
            raise ValueError("state is not an object")
        obj.setdefault("sessions", {})
        return obj
    except Exception:
        backup = DATA_DIR / f"bridge-state.bad-{int(time.time())}.json"
        try:
            shutil.copy2(STATE_FILE, backup)
        except Exception:
            pass
        return {"sessions": {}}


def save_state(state: dict[str, Any]) -> None:
    ensure_data_dir()
    tmp = STATE_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
    os.replace(tmp, STATE_FILE)


def update_session_state(session_id: str, patch: dict[str, Any]) -> dict[str, Any]:
    with STATE_LOCK:
        state = load_state()
        entry = state["sessions"].setdefault(session_id, {})
        entry.update(patch)
        entry["updatedAt"] = now_iso()
        save_state(state)
        return dict(entry)


def get_session_state(session_id: str) -> dict[str, Any]:
    with STATE_LOCK:
        return dict(load_state().get("sessions", {}).get(session_id, {}))


def find_claude_exe() -> str:
    for p in CLAUDE_CANDIDATES:
        if p.exists():
            return str(p)
    for name in ("claude.exe", "claude.cmd", "claude"):
        found = shutil.which(name)
        if found:
            return found
    raise FileNotFoundError("Claude Code executable was not found")


def run_claude_json(args: list[str], timeout: int = 10) -> Any:
    exe = find_claude_exe()
    cp = subprocess.run(
        [exe, *args],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
        creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
    )
    if cp.returncode != 0:
        raise RuntimeError(cp.stderr.strip() or cp.stdout.strip() or f"claude exited {cp.returncode}")
    return json.loads(cp.stdout)


def active_sessions() -> dict[str, dict[str, Any]]:
    try:
        items = run_claude_json(["agents", "--json"], timeout=8)
        if not isinstance(items, list):
            return {}
        result: dict[str, dict[str, Any]] = {}
        for item in items:
            sid = item.get("sessionId") if isinstance(item, dict) else None
            if sid:
                result[sid] = item
        return result
    except Exception:
        return {}


def current_profile_id() -> str | None:
    p = PROFILES_DIR / ".current"
    try:
        value = p.read_text(encoding="utf-8").strip()
        return value or None
    except Exception:
        return None


def safe_profile_info(path: Path) -> dict[str, Any] | None:
    try:
        obj = json.loads(path.read_text(encoding="utf-8"))
        env = obj.get("env", {}) if isinstance(obj, dict) else {}
        if not isinstance(env, dict):
            env = {}
        api_name = str(env.get("CLAUDE_API_NAME") or env.get("ANTHROPIC_BASE_URL") or "Default")
        model = str(env.get("ANTHROPIC_MODEL") or "default")
        model_name = str(env.get("CLAUDE_MODEL_NAME") or model)
        return {
            "id": path.stem,
            "apiName": api_name,
            "model": model,
            "modelName": model_name,
            "path": str(path),
        }
    except Exception:
        return None


def list_profiles() -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    if not PROFILES_DIR.exists():
        return result
    current = current_profile_id()
    for path in sorted(PROFILES_DIR.glob("*.json")):
        info = safe_profile_info(path)
        if info:
            info["current"] = info["id"] == current
            result.append(info)
    return result


def profile_path(profile_id: str | None) -> Path | None:
    if not profile_id:
        return None
    if not profile_id.replace("-", "").replace("_", "").isalnum():
        return None
    p = (PROFILES_DIR / f"{profile_id}.json").resolve()
    try:
        p.relative_to(PROFILES_DIR.resolve())
    except ValueError:
        return None
    return p if p.exists() else None


def extract_text(content: Any) -> str:
    if isinstance(content, str):
        return content
    if not isinstance(content, list):
        return ""
    parts: list[str] = []
    for block in content:
        if isinstance(block, dict) and block.get("type") == "text" and isinstance(block.get("text"), str):
            parts.append(block["text"])
    return "\n".join(parts).strip()


def extract_tool_uses(content: Any) -> list[dict[str, Any]]:
    if not isinstance(content, list):
        return []
    tools: list[dict[str, Any]] = []
    for block in content:
        if not isinstance(block, dict) or block.get("type") != "tool_use":
            continue
        inp = block.get("input")
        preview = ""
        if isinstance(inp, dict):
            for key in ("command", "path", "file_path", "query", "pattern", "url"):
                if key in inp:
                    preview = str(inp[key])
                    break
        tools.append({
            "id": block.get("id"),
            "name": block.get("name") or "tool",
            "preview": preview[:300],
        })
    return tools


def session_file(session_id: str) -> Path | None:
    if not PROJECTS_DIR.exists():
        return None
    for p in PROJECTS_DIR.glob(f"*/{session_id}.jsonl"):
        if p.is_file():
            return p
    return None


def read_session_summary(path: Path, active: dict[str, Any] | None = None) -> dict[str, Any]:
    sid = path.stem
    title = "新对话"
    cwd = ""
    model = ""
    first_user = ""
    try:
        with path.open("r", encoding="utf-8", errors="replace") as f:
            for idx, line in enumerate(f):
                if idx > 800 and first_user and cwd and model:
                    break
                try:
                    obj = json.loads(line)
                except Exception:
                    continue
                if not cwd and isinstance(obj.get("cwd"), str):
                    cwd = obj["cwd"]
                msg = obj.get("message")
                if isinstance(msg, dict):
                    if not model and isinstance(msg.get("model"), str):
                        model = msg["model"]
                    if not first_user and msg.get("role") == "user":
                        text = extract_text(msg.get("content"))
                        stripped = text.lstrip()
                        is_internal = stripped.startswith("<local-command-") or stripped.startswith("<command-")
                        if text and not is_internal:
                            first_user = " ".join(text.split())
                            title = first_user[:72]
                if first_user and cwd and model:
                    break
    except Exception:
        pass
    st = path.stat()
    bridge = get_session_state(sid)
    return {
        "id": sid,
        "title": bridge.get("title") or title,
        "cwd": bridge.get("cwd") or cwd,
        "model": bridge.get("model") or model,
        "profile": bridge.get("profile"),
        "effort": bridge.get("effort"),
        "bridgeOwned": bool(bridge.get("bridgeOwned")),
        "mtime": st.st_mtime,
        "updatedAt": datetime.fromtimestamp(st.st_mtime).astimezone().isoformat(timespec="seconds"),
        "active": active is not None,
        "activeKind": active.get("kind") if active else None,
        "activeStatus": active.get("status") if active else None,
        "activePid": active.get("pid") if active else None,
        "canTakeover": bool(active and str(active.get("status") or "").lower() == "idle"),
        "readOnly": bool(active),
    }


def list_sessions(limit: int = 60) -> list[dict[str, Any]]:
    active = active_sessions()
    candidates: list[Path] = []
    if PROJECTS_DIR.exists():
        candidates = [p for p in PROJECTS_DIR.glob("*/*.jsonl") if p.is_file()]
    candidates.sort(key=lambda p: p.stat().st_mtime, reverse=True)
    items = [read_session_summary(p, active.get(p.stem)) for p in candidates[: max(limit, 1)]]

    # A newly-created bridge session may not have a Claude JSONL yet.
    state = load_state().get("sessions", {})
    known = {x["id"] for x in items}
    for sid, cfg in state.items():
        if sid in known:
            continue
        items.append({
            "id": sid,
            "title": cfg.get("title") or "新对话",
            "cwd": cfg.get("cwd") or "",
            "model": cfg.get("model") or "",
            "profile": cfg.get("profile"),
            "effort": cfg.get("effort"),
            "bridgeOwned": bool(cfg.get("bridgeOwned")),
            "mtime": 0,
            "updatedAt": cfg.get("updatedAt"),
            "active": False,
            "activeKind": None,
            "activeStatus": None,
            "activePid": None,
            "canTakeover": False,
            "readOnly": False,
        })
    items.sort(key=lambda x: x.get("updatedAt") or "", reverse=True)
    return items[:limit]


def _content_text(value: Any) -> str:
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        parts: list[str] = []
        for item in value:
            if isinstance(item, str):
                parts.append(item)
            elif isinstance(item, dict):
                if isinstance(item.get("text"), str):
                    parts.append(item["text"])
                elif isinstance(item.get("content"), str):
                    parts.append(item["content"])
        return "\n".join(parts).strip()
    if isinstance(value, dict):
        if isinstance(value.get("text"), str):
            return value["text"]
        if isinstance(value.get("content"), str):
            return value["content"]
        try:
            return json.dumps(value, ensure_ascii=False, indent=2)
        except Exception:
            return str(value)
    if value is None:
        return ""
    return str(value)


def _trim_process_text(text: str, limit: int = 12000) -> str:
    if len(text) <= limit:
        return text
    return text[:limit].rstrip() + "\n\n…（执行输出过长，手机端已折叠截断）"


def _internal_transcript_text(text: str) -> bool:
    stripped = text.lstrip()
    return (
        stripped.startswith("<local-command-")
        or stripped.startswith("<command-")
        or stripped.startswith("<system-reminder>")
        or stripped.startswith("<total_tokens>")
    )


def read_messages(session_id: str, limit: int = 120) -> list[dict[str, Any]]:
    path = session_file(session_id)
    if not path:
        fork_from = get_session_state(session_id).get("forkFrom")
        if isinstance(fork_from, str) and fork_from:
            path = session_file(fork_from)
    if not path:
        return []

    records: deque[dict[str, Any]] = deque(maxlen=max(limit * 8, 500))
    tool_names: dict[str, str] = {}

    def add_record(
        role: str,
        text: str,
        obj: dict[str, Any],
        msg: dict[str, Any],
        *,
        kind: str = "message",
        title: str = "",
    ) -> None:
        text = text.strip()
        if not text:
            return
        records.append({
            "role": role,
            "text": text,
            "kind": kind,
            "title": title,
            "timestamp": obj.get("timestamp"),
            "uuid": obj.get("uuid"),
            "model": msg.get("model"),
        })

    try:
        with path.open("r", encoding="utf-8", errors="replace") as f:
            for line in f:
                try:
                    obj = json.loads(line)
                except Exception:
                    continue
                msg = obj.get("message")
                if not isinstance(msg, dict):
                    continue
                role = msg.get("role")
                if role not in ("user", "assistant"):
                    continue
                content = msg.get("content")

                if isinstance(content, str):
                    if content and not _internal_transcript_text(content):
                        add_record(role, content, obj, msg)
                    continue
                if not isinstance(content, list):
                    continue

                for block in content:
                    if not isinstance(block, dict):
                        continue
                    block_type = str(block.get("type") or "")

                    if block_type == "text":
                        text = str(block.get("text") or "")
                        if text and not _internal_transcript_text(text):
                            add_record(role, text, obj, msg)
                        continue

                    if role == "assistant" and block_type == "thinking":
                        thinking = str(block.get("thinking") or "")
                        add_record(
                            "assistant",
                            thinking,
                            obj,
                            msg,
                            kind="thinking",
                            title="思考过程",
                        )
                        continue

                    if role == "assistant" and block_type == "tool_use":
                        tool_id = str(block.get("id") or "")
                        tool_name = str(block.get("name") or "tool")
                        if tool_id:
                            tool_names[tool_id] = tool_name
                        inp = block.get("input")
                        if isinstance(inp, (dict, list)):
                            detail = json.dumps(inp, ensure_ascii=False, indent=2)
                            detail = f"```json\n{_trim_process_text(detail, 7000)}\n```"
                        else:
                            detail = _trim_process_text(_content_text(inp), 7000)
                        add_record(
                            "assistant",
                            detail or "（无参数）",
                            obj,
                            msg,
                            kind="tool",
                            title=f"执行 · {tool_name}",
                        )
                        continue

                    if role == "user" and block_type == "tool_result":
                        tool_id = str(block.get("tool_use_id") or "")
                        tool_name = tool_names.get(tool_id, "工具")
                        result = _content_text(block.get("content"))
                        if not result and isinstance(obj.get("toolUseResult"), (str, dict, list)):
                            result = _content_text(obj.get("toolUseResult"))
                        if block.get("is_error"):
                            prefix = "**执行失败**\n\n"
                        else:
                            prefix = ""
                        add_record(
                            "assistant",
                            prefix + _trim_process_text(result or "（无文本输出）"),
                            obj,
                            msg,
                            kind="tool_result",
                            title=f"结果 · {tool_name}",
                        )
                        continue
    except Exception:
        return []
    return list(records)[-limit:]


def validate_cwd(value: str | None) -> str:
    if not value:
        return str(Path.home())
    p = Path(value).expanduser()
    if not p.exists() or not p.is_dir():
        raise ValueError(f"目录不存在：{value}")
    return str(p.resolve())


def infer_session_defaults(session_id: str) -> dict[str, Any]:
    """Recover launch-critical values from an existing Claude transcript.

    This matters for desktop-created sessions: resuming them from the phone must
    keep the original working directory so Claude Code discovers the same
    project CLAUDE.md, settings, skills, MCP config, and auto-memory store.
    """
    path = session_file(session_id)
    cwd = ""
    model = ""
    effort = ""
    if path:
        try:
            with path.open("r", encoding="utf-8", errors="replace") as f:
                for line in f:
                    try:
                        obj = json.loads(line)
                    except Exception:
                        continue
                    if isinstance(obj.get("cwd"), str) and obj["cwd"]:
                        cwd = obj["cwd"]
                    msg = obj.get("message")
                    if isinstance(msg, dict):
                        if isinstance(msg.get("model"), str) and msg["model"]:
                            model = msg["model"]
                        content = msg.get("content")
                        text = extract_text(content)
                        if "Set effort level to " in text:
                            tail = text.split("Set effort level to ", 1)[1].split(None, 1)[0].strip().lower()
                            if tail in DEFAULT_EFFORTS:
                                effort = tail
        except Exception:
            pass

    chosen_profile = current_profile_id()
    if model:
        profiles = list_profiles()
        current_match = next((p for p in profiles if p.get("current") and p.get("model") == model), None)
        model_match = current_match or next((p for p in profiles if p.get("model") == model), None)
        if model_match:
            chosen_profile = str(model_match["id"])

    return {
        "profile": chosen_profile,
        "model": model or None,
        "effort": effort or "high",
        "cwd": cwd or str(Path.home()),
        "bridgeOwned": False,
    }


def resolve_config(session_id: str) -> dict[str, Any]:
    inferred = infer_session_defaults(session_id)
    cfg = get_session_state(session_id)
    if not cfg:
        return inferred
    merged = dict(inferred)
    merged.update({k: v for k, v in cfg.items() if v is not None})
    return merged


def _same_path(a: str | Path, b: str | Path) -> bool:
    try:
        return os.path.normcase(os.path.normpath(str(a))) == os.path.normcase(os.path.normpath(str(b)))
    except Exception:
        return False


def _memory_for_cwd(cwd: str) -> list[str]:
    matches: list[str] = []
    if not PROJECTS_DIR.exists():
        return matches
    target = os.path.normcase(os.path.normpath(cwd))
    for project_dir in PROJECTS_DIR.iterdir():
        memory = project_dir / "memory" / "MEMORY.md"
        if not memory.exists():
            continue
        matched = False
        for transcript in project_dir.glob("*.jsonl"):
            try:
                with transcript.open("r", encoding="utf-8", errors="replace") as f:
                    for idx, line in enumerate(f):
                        if idx > 80:
                            break
                        try:
                            obj = json.loads(line)
                        except Exception:
                            continue
                        item_cwd = obj.get("cwd")
                        if isinstance(item_cwd, str) and os.path.normcase(os.path.normpath(item_cwd)) == target:
                            matched = True
                            break
                if matched:
                    break
            except Exception:
                continue
        if matched:
            matches.append(str(memory))
    return matches


def context_audit(cwd: str) -> dict[str, Any]:
    workdir = Path(validate_cwd(cwd))
    claude_md: list[str] = []
    settings: list[str] = []

    global_claude = CLAUDE_HOME / "CLAUDE.md"
    global_settings = CLAUDE_HOME / "settings.json"
    if global_claude.exists():
        claude_md.append(str(global_claude))
    if global_settings.exists():
        settings.append(str(global_settings))

    # Claude Code walks project/ancestor context when normal auto-discovery is
    # enabled. Surface the files that can participate so the mobile UI can
    # verify that it is launching from the intended project root.
    lineage = [workdir, *workdir.parents]
    for base in reversed(lineage):
        for candidate in (base / "CLAUDE.md", base / ".claude" / "CLAUDE.md"):
            if candidate.exists() and str(candidate) not in claude_md:
                claude_md.append(str(candidate))
        for candidate in (base / ".claude" / "settings.json", base / ".claude" / "settings.local.json"):
            if candidate.exists() and str(candidate) not in settings:
                settings.append(str(candidate))

    memories = _memory_for_cwd(str(workdir))
    return {
        "cwd": str(workdir),
        "settingSources": ["user", "project", "local"],
        "claudeMd": claude_md,
        "settings": settings,
        "memory": memories,
        "autoDiscovery": True,
        "safeMode": False,
        "bareMode": False,
        "skillsEnabled": True,
        "pluginsEnabled": True,
        "mcpEnabled": True,
    }


def local_command_response(session_id: str, text: str) -> str | None:
    """Handle bridge-only commands without shadowing native Claude Code commands."""
    stripped = text.strip()
    if not stripped.startswith("/bridge-"):
        return None
    parts = stripped.split(None, 1)
    cmd = parts[0].lower()
    arg = parts[1].strip() if len(parts) > 1 else ""
    cfg = resolve_config(session_id)

    if cmd in ("/bridge-status", "/bridge-config"):
        audit = context_audit(cfg.get("cwd") or str(Path.home()))
        return (
            f"Bridge session: {session_id}\n"
            f"API/Profile: {cfg.get('profile') or '(current)'}\n"
            f"Model: {cfg.get('model') or '(profile default)'}\n"
            f"Effort: {cfg.get('effort') or 'high'}\n"
            f"CWD: {cfg.get('cwd') or str(Path.home())}\n"
            f"CLAUDE.md: {len(audit['claudeMd'])} 个\n"
            f"Project memory: {'已发现' if audit['memory'] else '当前未发现'}"
        )
    if cmd == "/bridge-effort":
        if arg not in DEFAULT_EFFORTS:
            return "用法：/bridge-effort low|medium|high|xhigh|max"
        update_session_state(session_id, {"effort": arg})
        return f"已将 Bridge 后续启动 effort 设置为 {arg}。"
    if cmd == "/bridge-model":
        if not arg:
            return "用法：/bridge-model <model-id>"
        update_session_state(session_id, {"model": arg})
        return f"已将 Bridge 后续启动 model 设置为 {arg}。"
    if cmd in ("/bridge-api", "/bridge-profile"):
        if not arg or not profile_path(arg):
            return "用法：/bridge-profile <profile-id>。可在顶部 API/模型选择器中查看可用项。"
        info = safe_profile_info(profile_path(arg))
        patch = {"profile": arg}
        if info:
            patch["model"] = info.get("model")
        update_session_state(session_id, patch)
        return f"已切换 Bridge 后续启动 profile：{arg}。不会修改全局 .current。"
    if cmd == "/bridge-cwd":
        if not arg:
            return "用法：/bridge-cwd <目录>"
        try:
            cwd = validate_cwd(arg)
        except ValueError as exc:
            return str(exc)
        update_session_state(session_id, {"cwd": cwd})
        return f"已将 Bridge 后续启动工作目录设置为：{cwd}"
    if cmd == "/bridge-help":
        return (
            "Bridge 指令：/bridge-status、/bridge-profile、/bridge-model、/bridge-effort、/bridge-cwd。\n"
            "Claude Code 原生 /compact、/config、/context、/effort、/model、/mcp、/init 等不会被 Bridge 拦截。"
        )
    return None


def sync_native_command_config(session_id: str, text: str) -> None:
    """Mirror simple native command changes into the next headless resume.

    Claude Code still receives the command itself. We only remember explicit
    values so a later process restart does not overwrite the user's choice.
    """
    stripped = text.strip()
    if not stripped.startswith("/"):
        return
    parts = stripped.split(None, 1)
    cmd = parts[0].lower()
    arg = parts[1].strip() if len(parts) > 1 else ""
    if cmd == "/effort" and arg in DEFAULT_EFFORTS:
        update_session_state(session_id, {"effort": arg})
    elif cmd == "/model" and arg:
        update_session_state(session_id, {"model": arg})
    elif cmd in ("/rename", "/name") and arg:
        update_session_state(session_id, {"title": arg[:120]})


def create_mobile_fork(source_id: str, suffix: str = "手机") -> tuple[str, dict[str, Any]]:
    source = next((x for x in list_sessions(200) if x["id"] == source_id), None)
    if not source:
        raise RuntimeError("找不到要继续的 Claude Code 会话。")
    base_cfg = resolve_config(source_id)
    sid = str(uuid.uuid4())
    title = source.get("title") or "Claude Code"
    cfg = update_session_state(sid, {
        "profile": base_cfg.get("profile"),
        "model": base_cfg.get("model"),
        "effort": base_cfg.get("effort") or "high",
        "cwd": base_cfg.get("cwd") or str(Path.home()),
        "title": f"{title} · {suffix}",
        "bridgeOwned": True,
        "forkFrom": source_id,
        "createdAt": now_iso(),
    })
    return sid, cfg


def prepare_mobile_target(task_id: str, source_id: str) -> str:
    """Wait out a running desktop turn, then continue without concurrent writes.

    An idle interactive Claude process still owns the original transcript, so the
    mobile bridge transparently forks it. If the desktop process exits entirely,
    resuming the original session is safe and keeps the same session id.
    """
    while True:
        with TASK_LOCK:
            task = TASKS.get(task_id)
            if not task or task.get("status") == "stopping":
                raise RuntimeError("任务已停止")
        active = active_sessions().get(source_id)
        if not active:
            return source_id
        status = str(active.get("status") or "").lower()
        if status == "idle":
            sid, _ = create_mobile_fork(source_id, "手机")
            with TASK_LOCK:
                if task_id in TASKS:
                    TASKS[task_id]["effectiveSessionId"] = sid
                    TASKS[task_id]["forkedFrom"] = source_id
            return sid
        with TASK_LOCK:
            if task_id in TASKS:
                TASKS[task_id]["status"] = "waiting_external"
                TASKS[task_id]["externalStatus"] = status or "running"
                TASKS[task_id]["updatedAt"] = now_iso()
        time.sleep(1.0)


def _write_windows_console_keys(pid: int, text: str | None = None, *, ctrl_c: bool = False) -> None:
    if os.name != "nt":
        raise RuntimeError("直接控制桌面 Claude 目前只支持 Windows。")
    if pid <= 0:
        raise RuntimeError("Claude Code 没有提供有效的进程 PID。")

    from ctypes import wintypes

    KEY_EVENT = 0x0001
    VK_RETURN = 0x0D
    VK_C = 0x43
    LEFT_CTRL_PRESSED = 0x0008
    STD_INPUT_HANDLE = -10
    INVALID_HANDLE_VALUE = ctypes.c_void_p(-1).value

    class CharUnion(ctypes.Union):
        _fields_ = [("UnicodeChar", wintypes.WCHAR), ("AsciiChar", ctypes.c_char)]

    class KeyEventRecord(ctypes.Structure):
        _fields_ = [
            ("bKeyDown", wintypes.BOOL),
            ("wRepeatCount", wintypes.WORD),
            ("wVirtualKeyCode", wintypes.WORD),
            ("wVirtualScanCode", wintypes.WORD),
            ("uChar", CharUnion),
            ("dwControlKeyState", wintypes.DWORD),
        ]

    class EventUnion(ctypes.Union):
        _fields_ = [("KeyEvent", KeyEventRecord), ("_padding", ctypes.c_byte * 20)]

    class InputRecord(ctypes.Structure):
        _fields_ = [("EventType", wintypes.WORD), ("Event", EventUnion)]

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.FreeConsole.restype = wintypes.BOOL
    kernel32.AttachConsole.argtypes = [wintypes.DWORD]
    kernel32.AttachConsole.restype = wintypes.BOOL
    kernel32.GetStdHandle.argtypes = [wintypes.DWORD]
    kernel32.GetStdHandle.restype = wintypes.HANDLE
    kernel32.WriteConsoleInputW.argtypes = [
        wintypes.HANDLE,
        ctypes.POINTER(InputRecord),
        wintypes.DWORD,
        ctypes.POINTER(wintypes.DWORD),
    ]
    kernel32.WriteConsoleInputW.restype = wintypes.BOOL

    def key_record(char: str, down: bool, vk: int = 0, control: int = 0) -> InputRecord:
        rec = InputRecord()
        rec.EventType = KEY_EVENT
        rec.Event.KeyEvent.bKeyDown = bool(down)
        rec.Event.KeyEvent.wRepeatCount = 1
        rec.Event.KeyEvent.wVirtualKeyCode = vk
        rec.Event.KeyEvent.wVirtualScanCode = 0
        rec.Event.KeyEvent.uChar.UnicodeChar = char
        rec.Event.KeyEvent.dwControlKeyState = control
        return rec

    with CONSOLE_LOCK:
        # The bridge normally has no useful console of its own. Detach first so
        # AttachConsole can bind to the exact interactive Claude process.
        kernel32.FreeConsole()
        ctypes.set_last_error(0)
        if not kernel32.AttachConsole(pid):
            err = ctypes.get_last_error()
            raise RuntimeError(f"无法连接桌面 Claude 控制台（Win32 {err}）。")
        try:
            handle = kernel32.GetStdHandle(STD_INPUT_HANDLE)
            if not handle or handle == INVALID_HANDLE_VALUE:
                raise RuntimeError("无法取得桌面 Claude 的控制台输入句柄。")

            records: list[InputRecord] = []
            if ctrl_c:
                records.append(key_record("\x03", True, VK_C, LEFT_CTRL_PRESSED))
                records.append(key_record("\x03", False, VK_C, LEFT_CTRL_PRESSED))
            else:
                normalized = (text or "").replace("\r\n", "\n").replace("\r", "\n")
                for ch in normalized:
                    if ch == "\n":
                        records.append(key_record("\r", True, VK_RETURN, 0))
                        records.append(key_record("\r", False, VK_RETURN, 0))
                    else:
                        records.append(key_record(ch, True, 0, 0))
                        records.append(key_record(ch, False, 0, 0))
                # A phone send action corresponds to pressing Enter once after
                # the composed text. Multi-line content already contains its own
                # newlines; this final Return submits it in Claude Code.
                records.append(key_record("\r", True, VK_RETURN, 0))
                records.append(key_record("\r", False, VK_RETURN, 0))

            if not records:
                return
            array_type = InputRecord * len(records)
            buffer = array_type(*records)
            written = wintypes.DWORD(0)
            if not kernel32.WriteConsoleInputW(handle, buffer, len(records), ctypes.byref(written)):
                err = ctypes.get_last_error()
                raise RuntimeError(f"写入桌面 Claude 控制台失败（Win32 {err}）。")
            if written.value != len(records):
                raise RuntimeError(f"桌面 Claude 只接收了 {written.value}/{len(records)} 个输入事件。")
        finally:
            kernel32.FreeConsole()


def inject_interactive_message(session_id: str, text: str) -> dict[str, Any]:
    active = active_sessions().get(session_id)
    if not active:
        raise RuntimeError("桌面 Claude 会话已不再活动。")
    if str(active.get("kind") or "").lower() != "interactive":
        raise RuntimeError("该 Claude 会话不是交互式终端，无法直接注入输入。")
    pid = active.get("pid")
    if not isinstance(pid, int) or pid <= 0:
        raise RuntimeError("Claude Code 没有提供可控制的进程 PID。")
    _write_windows_console_keys(pid, text)
    return {"ok": True, "pid": pid, "status": active.get("status"), "kind": "interactive"}


def stop_external_session(session_id: str) -> dict[str, Any]:
    active = active_sessions().get(session_id)
    if not active:
        return {"ok": True, "alreadyStopped": True}
    pid = active.get("pid")
    if not isinstance(pid, int) or pid <= 0:
        raise RuntimeError("Claude Code 没有提供可中止的进程 PID。")

    kind = str(active.get("kind") or "").lower()

    # Interactive Claude Code already understands Ctrl+C as "stop the current
    # turn". Inject that key instead of killing the TUI, so the same desktop
    # session stays alive and can immediately receive the next phone message.
    if kind == "interactive":
        _write_windows_console_keys(pid, ctrl_c=True)
        for _ in range(20):
            current = active_sessions().get(session_id)
            if not current:
                return {"ok": True, "pid": pid, "interrupted": True, "processExited": True}
            if str(current.get("status") or "").lower() == "idle":
                return {"ok": True, "pid": pid, "interrupted": True, "processExited": False}
            time.sleep(0.25)
        # The key was delivered even if the activity tracker has not refreshed
        # yet. Do not escalate to taskkill automatically; killing the terminal
        # would be much more destructive than the user's requested interrupt.
        return {"ok": True, "pid": pid, "interrupted": True, "pendingStatusRefresh": True}

    # For background sessions, prefer Claude Code's own lifecycle command.
    if kind == "background":
        for candidate in (session_id, str(active.get("name") or "")):
            if not candidate:
                continue
            try:
                cp = subprocess.run(
                    [find_claude_exe(), "stop", candidate],
                    capture_output=True,
                    text=True,
                    encoding="utf-8",
                    errors="replace",
                    timeout=8,
                    creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
                )
                if cp.returncode == 0:
                    break
            except Exception:
                pass

    # Non-interactive background sessions have no console input queue. If the
    # native lifecycle command did not stop them, terminate only the exact
    # process reported by `claude agents --json`; the transcript remains resumable.
    if active_sessions().get(session_id):
        if os.name == "nt":
            cp = subprocess.run(
                ["taskkill", "/PID", str(pid), "/T", "/F"],
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=8,
                creationflags=subprocess.CREATE_NO_WINDOW,
            )
            if cp.returncode != 0 and active_sessions().get(session_id):
                raise RuntimeError(cp.stderr.strip() or cp.stdout.strip() or "无法中止桌面 Claude Code")
        else:
            os.kill(pid, signal.SIGTERM)

    for _ in range(24):
        if not active_sessions().get(session_id):
            return {"ok": True, "pid": pid}
        time.sleep(0.25)
    raise RuntimeError("已发送中止命令，但 Claude Code 进程仍处于活动状态。")


def build_claude_command(
    session_id: str,
    prompt: str,
    *,
    allow_active_fork_source: bool = False,
) -> tuple[list[str], str]:
    cfg = resolve_config(session_id)
    cwd = validate_cwd(cfg.get("cwd"))
    path = session_file(session_id)
    active = active_sessions().get(session_id)
    if active:
        raise RuntimeError("目标会话仍被另一个 Claude Code 进程占用。")

    cmd = [
        find_claude_exe(),
        "-p",
        prompt,
        "--output-format",
        "stream-json",
        "--include-partial-messages",
        "--verbose",
        "--setting-sources",
        "user,project,local",
    ]

    prof = profile_path(cfg.get("profile"))
    if prof:
        cmd.extend(["--settings", str(prof)])
    if cfg.get("model"):
        cmd.extend(["--model", str(cfg["model"])])
    effort = cfg.get("effort") or "high"
    if effort in DEFAULT_EFFORTS:
        cmd.extend(["--effort", effort])

    if path:
        cmd.extend(["--resume", session_id])
    else:
        fork_from = cfg.get("forkFrom")
        if isinstance(fork_from, str) and fork_from:
            source_active = active_sessions().get(fork_from)
            if (
                source_active
                and str(source_active.get("status") or "").lower() != "idle"
                and not allow_active_fork_source
            ):
                raise RuntimeError("桌面原会话又开始执行任务了；请等它空闲后再从手机继续。")
            if not session_file(fork_from):
                raise RuntimeError("找不到要继续的桌面会话记录。")
            cmd.extend(["--resume", fork_from, "--fork-session", "--session-id", session_id])
        else:
            cmd.extend(["--session-id", session_id])
    return cmd, cwd


def append_task_event(task_id: str, event: dict[str, Any]) -> None:
    with TASK_LOCK:
        task = TASKS.get(task_id)
        if not task:
            return
        task.setdefault("events", []).append(event)
        if len(task["events"]) > 400:
            task["events"] = task["events"][-400:]
        task["updatedAt"] = now_iso()


def parse_stream_line(task_id: str, line: str) -> None:
    try:
        obj = json.loads(line)
    except Exception:
        append_task_event(task_id, {"type": "raw", "text": line[:2000]})
        return

    typ = obj.get("type")
    if typ == "stream_event":
        event = obj.get("event", {})
        if isinstance(event, dict):
            delta = event.get("delta", {})
            if isinstance(delta, dict) and isinstance(delta.get("text"), str):
                text = delta["text"]
                with TASK_LOCK:
                    task = TASKS.get(task_id)
                    if task:
                        task["partialText"] = task.get("partialText", "") + text
                        task["sawPartial"] = True
                append_task_event(task_id, {"type": "text_delta", "text": text})
            if isinstance(delta, dict) and isinstance(delta.get("thinking"), str):
                thinking = delta["thinking"]
                with TASK_LOCK:
                    task = TASKS.get(task_id)
                    if task:
                        task["partialThinking"] = task.get("partialThinking", "") + thinking
                append_task_event(task_id, {"type": "thinking_delta", "text": thinking})
            if event.get("type") == "content_block_start":
                block = event.get("content_block", {})
                if isinstance(block, dict) and block.get("type") == "tool_use":
                    append_task_event(task_id, {
                        "type": "tool_start",
                        "name": block.get("name") or "tool",
                        "id": block.get("id"),
                    })
        return

    if typ == "assistant":
        msg = obj.get("message", {})
        if isinstance(msg, dict):
            text = extract_text(msg.get("content"))
            tools = extract_tool_uses(msg.get("content"))
            content = msg.get("content")
            if isinstance(content, list):
                for block in content:
                    if isinstance(block, dict) and block.get("type") == "thinking" and isinstance(block.get("thinking"), str):
                        with TASK_LOCK:
                            task = TASKS.get(task_id)
                            if task and not task.get("partialThinking"):
                                task["partialThinking"] = block["thinking"]
                        append_task_event(task_id, {"type": "thinking", "text": block["thinking"][:4000]})
            with TASK_LOCK:
                task = TASKS.get(task_id)
                saw_partial = bool(task and task.get("sawPartial"))
                if task and text and not saw_partial:
                    task["partialText"] = task.get("partialText", "") + text
            if text and not saw_partial:
                append_task_event(task_id, {"type": "assistant_text", "text": text})
            for tool in tools:
                append_task_event(task_id, {"type": "tool", **tool})
        return

    if typ == "result":
        with TASK_LOCK:
            task = TASKS.get(task_id)
            if task:
                task["result"] = {
                    "subtype": obj.get("subtype"),
                    "durationMs": obj.get("duration_ms"),
                    "durationApiMs": obj.get("duration_api_ms"),
                    "numTurns": obj.get("num_turns"),
                    "totalCostUsd": obj.get("total_cost_usd"),
                    "sessionId": obj.get("session_id"),
                    "isError": obj.get("is_error"),
                }
        append_task_event(task_id, {"type": "result"})
        return

    append_task_event(task_id, {"type": typ or "event"})


def run_turn(
    task_id: str,
    session_id: str,
    prompt: str,
    *,
    prepare_external: bool = False,
    allow_active_fork_source: bool = False,
) -> None:
    with TASK_LOCK:
        task = TASKS[task_id]
        task["startedAt"] = now_iso()
    effective_session_id = session_id
    try:
        local = local_command_response(session_id, prompt)
        if local is not None:
            with TASK_LOCK:
                task = TASKS[task_id]
                task["partialText"] = local
                task["status"] = "completed"
                task["completedAt"] = now_iso()
                task["localCommand"] = True
            return

        if prepare_external:
            effective_session_id = prepare_mobile_target(task_id, session_id)
            with TASK_LOCK:
                if task_id in TASKS:
                    TASKS[task_id]["effectiveSessionId"] = effective_session_id
        with TASK_LOCK:
            if task_id in TASKS and TASKS[task_id].get("status") != "stopping":
                TASKS[task_id]["status"] = "running"
                TASKS[task_id]["updatedAt"] = now_iso()

        sync_native_command_config(effective_session_id, prompt)
        cmd, cwd = build_claude_command(
            effective_session_id,
            prompt,
            allow_active_fork_source=allow_active_fork_source,
        )
        creationflags = 0
        if os.name == "nt":
            creationflags = subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP
        proc = subprocess.Popen(
            cmd,
            cwd=cwd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
            creationflags=creationflags,
        )
        with TASK_LOCK:
            TASK_PROCESSES[task_id] = proc
            TASKS[task_id]["pid"] = proc.pid

        assert proc.stdout is not None
        for line in proc.stdout:
            line = line.rstrip("\r\n")
            if line:
                parse_stream_line(task_id, line)

        stderr = ""
        if proc.stderr is not None:
            stderr = proc.stderr.read().strip()
        rc = proc.wait()
        with TASK_LOCK:
            task = TASKS[task_id]
            task["returnCode"] = rc
            task["stderr"] = stderr[-8000:]
            if task.get("status") == "stopping":
                task["status"] = "stopped"
            elif rc == 0:
                task["status"] = "completed"
            else:
                task["status"] = "error"
                task["error"] = stderr or f"Claude Code exited with code {rc}"
            task["completedAt"] = now_iso()
        update_session_state(effective_session_id, {"bridgeOwned": True})
    except Exception as exc:
        with TASK_LOCK:
            task = TASKS[task_id]
            if task.get("status") == "stopping":
                task["status"] = "stopped"
            else:
                task["status"] = "error"
                task["error"] = str(exc)
            task["completedAt"] = now_iso()
    finally:
        with TASK_LOCK:
            TASK_PROCESSES.pop(task_id, None)


def task_public(task: dict[str, Any]) -> dict[str, Any]:
    return {k: v for k, v in task.items() if k not in {"command"}}


class BridgeHandler(BaseHTTPRequestHandler):
    server_version = "ClaudeMobileBridge/0.2"

    def log_message(self, fmt: str, *args: Any) -> None:
        # Never persist query strings: old-style ?token= links or unrelated
        # secrets must not end up in access logs. Keep only method, path, status.
        safe_path = urlparse(self.path).path or "/"
        status = str(args[1]) if len(args) > 1 else "-"
        print(
            f"[{self.log_date_time_string()}] {self.address_string()} {self.command} {safe_path} {status}",
            flush=True,
        )

    @property
    def auth_token(self) -> str:
        return self.server.auth_token  # type: ignore[attr-defined]

    def _parsed(self):
        return urlparse(self.path)

    def _auth_ok(self) -> bool:
        supplied = ""
        auth = self.headers.get("Authorization", "")
        if auth.startswith("Bearer "):
            supplied = auth[7:].strip()
        return bool(supplied and secrets.compare_digest(supplied, self.auth_token))

    def _security_headers(self) -> None:
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Cross-Origin-Opener-Policy", "same-origin")
        self.send_header("Cross-Origin-Resource-Policy", "same-origin")
        self.send_header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
        self.send_header(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
        )

    def _json(self, status: int, obj: Any) -> None:
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self._security_headers()
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0") or 0)
        if length > 2_000_000:
            raise ValueError("request too large")
        data = self.rfile.read(length) if length else b"{}"
        obj = json.loads(data.decode("utf-8"))
        if not isinstance(obj, dict):
            raise ValueError("JSON object required")
        return obj

    def _require_auth(self) -> bool:
        if self._auth_ok():
            return True
        self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
        return False

    def do_GET(self) -> None:
        parsed = self._parsed()
        path = unquote(parsed.path)
        if path == "/api/health":
            self._json(200, {"ok": True, "app": APP_NAME, "time": now_iso()})
            return
        if path.startswith("/api/"):
            if not self._require_auth():
                return
            try:
                self._handle_api_get(path, parse_qs(parsed.query))
            except Exception as exc:
                self._json(500, {"error": str(exc)})
            return
        self._serve_static(path)

    def do_POST(self) -> None:
        parsed = self._parsed()
        path = unquote(parsed.path)
        if not path.startswith("/api/") or not self._require_auth():
            if not path.startswith("/api/"):
                self._json(404, {"error": "not found"})
            return
        try:
            body = self._read_json()
            self._handle_api_post(path, body)
        except ValueError as exc:
            self._json(400, {"error": str(exc)})
        except Exception as exc:
            self._json(500, {"error": str(exc)})

    def do_PATCH(self) -> None:
        parsed = self._parsed()
        path = unquote(parsed.path)
        if not path.startswith("/api/") or not self._require_auth():
            if not path.startswith("/api/"):
                self._json(404, {"error": "not found"})
            return
        try:
            body = self._read_json()
            self._handle_api_patch(path, body)
        except ValueError as exc:
            self._json(400, {"error": str(exc)})
        except Exception as exc:
            self._json(500, {"error": str(exc)})

    def do_DELETE(self) -> None:
        parsed = self._parsed()
        path = unquote(parsed.path)
        if not path.startswith("/api/") or not self._require_auth():
            if not path.startswith("/api/"):
                self._json(404, {"error": "not found"})
            return
        try:
            self._handle_api_delete(path)
        except ValueError as exc:
            self._json(400, {"error": str(exc)})
        except Exception as exc:
            self._json(500, {"error": str(exc)})

    def _handle_api_get(self, path: str, qs: dict[str, list[str]]) -> None:
        if path == "/api/bootstrap":
            profiles = list_profiles()
            sessions = list_sessions(80)
            workspaces: list[str] = []
            for item in sessions:
                cwd = item.get("cwd")
                if isinstance(cwd, str) and cwd and cwd not in workspaces:
                    workspaces.append(cwd)
            for candidate in (r"D:\GitHub", str(Path.home()), r"C:\Users\ASUS\Downloads"):
                if Path(candidate).is_dir() and candidate not in workspaces:
                    workspaces.append(candidate)
            self._json(200, {
                "app": APP_NAME,
                "version": "0.3.0",
                "currentProfile": current_profile_id(),
                "profiles": profiles,
                "efforts": DEFAULT_EFFORTS,
                "sessions": sessions,
                "workspaces": workspaces[:80],
                "claude": {"exe": find_claude_exe()},
            })
            return
        if path == "/api/sessions":
            limit = int(qs.get("limit", ["80"])[0])
            self._json(200, {"sessions": list_sessions(max(1, min(limit, 200)))})
            return
        if path.startswith("/api/session/") and path.endswith("/messages"):
            sid = path.split("/")[3]
            limit = int(qs.get("limit", ["120"])[0])
            self._json(200, {"sessionId": sid, "messages": read_messages(sid, max(1, min(limit, 300)))})
            return
        if path.startswith("/api/session/"):
            sid = path.split("/")[3]
            found = next((x for x in list_sessions(200) if x["id"] == sid), None)
            cfg = resolve_config(sid)
            self._json(200, {
                "session": found,
                "config": cfg,
                "context": context_audit(cfg.get("cwd") or str(Path.home())),
            })
            return
        if path.startswith("/api/task/"):
            tid = path.split("/")[3]
            with TASK_LOCK:
                task = TASKS.get(tid)
                if not task:
                    self._json(404, {"error": "task not found"})
                    return
                self._json(200, {"task": task_public(dict(task))})
            return
        self._json(404, {"error": "not found"})

    def _handle_api_post(self, path: str, body: dict[str, Any]) -> None:
        if path == "/api/session":
            sid = str(uuid.uuid4())
            profile = body.get("profile") or current_profile_id()
            if profile and not profile_path(str(profile)):
                raise ValueError("unknown profile")
            cwd = validate_cwd(body.get("cwd"))
            model = body.get("model")
            if not model and profile:
                info = safe_profile_info(profile_path(str(profile)))
                model = info.get("model") if info else None
            effort = body.get("effort") or "high"
            if effort not in DEFAULT_EFFORTS:
                raise ValueError("invalid effort")
            cfg = update_session_state(sid, {
                "profile": profile,
                "model": model,
                "effort": effort,
                "cwd": cwd,
                "title": body.get("title") or "新对话",
                "bridgeOwned": True,
                "createdAt": now_iso(),
            })
            self._json(201, {"sessionId": sid, "config": cfg})
            return

        if path.startswith("/api/session/") and path.endswith("/fork"):
            source_id = path.split("/")[3]
            source = next((x for x in list_sessions(200) if x["id"] == source_id), None)
            if not source:
                self._json(404, {"error": "session not found"})
                return
            if source.get("active") and str(source.get("activeStatus") or "").lower() != "idle":
                self._json(409, {"error": "桌面 Claude 当前仍在执行任务；可直接发送消息排队，或用 /btw 旁问。"})
                return
            sid, cfg = create_mobile_fork(source_id, "手机")
            self._json(201, {"sessionId": sid, "config": cfg, "forkedFrom": source_id})
            return

        if path.startswith("/api/session/") and path.endswith("/stop"):
            sid = path.split("/")[3]
            result = stop_external_session(sid)
            self._json(200, result)
            return

        if path.startswith("/api/session/") and path.endswith("/message"):
            sid = path.split("/")[3]
            prompt = str(body.get("text") or "").strip()
            if not prompt:
                raise ValueError("message text is empty")
            cfg_now = resolve_config(sid)
            if (cfg_now.get("title") or "") == "新对话" and not prompt.startswith("/"):
                update_session_state(sid, {"title": " ".join(prompt.split())[:72]})
            # Refuse a second bridge-owned turn for the same visible source.
            with TASK_LOCK:
                for t in TASKS.values():
                    if t.get("sessionId") == sid and t.get("status") in ("queued", "waiting_external", "running", "stopping"):
                        self._json(409, {"error": "该会话已有手机端任务正在运行或排队"})
                        return

            # Mobile alias: explicitly stop the external desktop Claude process
            # while preserving its transcript for a later resume.
            if prompt.lower() in ("/stop", "/interrupt"):
                result = stop_external_session(sid)
                task_id = uuid.uuid4().hex
                task = {
                    "id": task_id,
                    "sessionId": sid,
                    "effectiveSessionId": sid,
                    "status": "completed",
                    "prompt": prompt,
                    "partialText": "已向桌面 Claude Code 发送中止指令。交互式会话会保留，可直接继续输入。",
                    "events": [],
                    "localCommand": True,
                    "stopResult": result,
                    "createdAt": now_iso(),
                    "completedAt": now_iso(),
                }
                with TASK_LOCK:
                    TASKS[task_id] = task
                self._json(202, {"taskId": task_id, "sessionId": sid})
                return

            # Interactive desktop Claude Code can receive the exact same keyboard
            # input a person would type in its TUI. This is the preferred path:
            # ordinary text can steer/queue the live turn, and Claude's native
            # /btw semantics remain intact because we inject the command verbatim.
            active_now = active_sessions().get(sid)
            if active_now and str(active_now.get("kind") or "").lower() == "interactive":
                result = inject_interactive_message(sid, prompt)
                task_id = uuid.uuid4().hex
                task = {
                    "id": task_id,
                    "sessionId": sid,
                    "effectiveSessionId": sid,
                    "status": "completed",
                    "prompt": prompt,
                    "partialText": "",
                    "events": [],
                    "injectedInteractive": True,
                    "injectionResult": result,
                    "createdAt": now_iso(),
                    "completedAt": now_iso(),
                }
                with TASK_LOCK:
                    TASKS[task_id] = task
                self._json(202, {
                    "taskId": task_id,
                    "sessionId": sid,
                    "injectedInteractive": True,
                })
                return

            task_id = uuid.uuid4().hex

            # For a non-interactive/background source there is no terminal input
            # queue. /btw therefore falls back to an isolated fork so it still
            # cannot change or interrupt the main work.
            if prompt.lower().startswith("/btw"):
                question = prompt[4:].strip()
                if not question:
                    raise ValueError("用法：/btw <旁问内容>")
                side_id, _ = create_mobile_fork(sid, "BTW")
                side_prompt = (
                    "这是从手机端发起的 /btw 旁问。请只回答下面这个旁问；"
                    "不要继续、改变或中止原主任务。\n\n" + question
                )
                task = {
                    "id": task_id,
                    "sessionId": sid,
                    "effectiveSessionId": side_id,
                    "forkedFrom": sid,
                    "sideQuestion": True,
                    "status": "queued",
                    "prompt": prompt,
                    "partialText": "",
                    "partialThinking": "",
                    "events": [],
                    "createdAt": now_iso(),
                }
                with TASK_LOCK:
                    TASKS[task_id] = task
                thread = threading.Thread(
                    target=run_turn,
                    args=(task_id, side_id, side_prompt),
                    kwargs={"allow_active_fork_source": True},
                    daemon=True,
                )
                thread.start()
                self._json(202, {"taskId": task_id, "sessionId": side_id, "sideQuestion": True})
                return

            task = {
                "id": task_id,
                "sessionId": sid,
                "effectiveSessionId": sid,
                "status": "queued",
                "prompt": prompt,
                "partialText": "",
                "partialThinking": "",
                "events": [],
                "createdAt": now_iso(),
            }
            with TASK_LOCK:
                TASKS[task_id] = task
            thread = threading.Thread(
                target=run_turn,
                args=(task_id, sid, prompt),
                kwargs={"prepare_external": True},
                daemon=True,
            )
            thread.start()
            self._json(202, {"taskId": task_id, "sessionId": sid})
            return

        if path == "/api/shutdown":
            self._json(200, {"ok": True})
            threading.Thread(target=self.server.shutdown, daemon=True).start()
            return

        if path.startswith("/api/task/") and path.endswith("/stop"):
            tid = path.split("/")[3]
            with TASK_LOCK:
                task = TASKS.get(tid)
                proc = TASK_PROCESSES.get(tid)
                if not task:
                    self._json(404, {"error": "task not found"})
                    return
                task["status"] = "stopping"
            if proc and proc.poll() is None:
                proc.terminate()
            self._json(200, {"ok": True})
            return

        self._json(404, {"error": "not found"})

    def _handle_api_patch(self, path: str, body: dict[str, Any]) -> None:
        if path.startswith("/api/session/"):
            sid = path.split("/")[3]
            patch: dict[str, Any] = {}
            if "profile" in body:
                prof = str(body["profile"] or "")
                if prof and not profile_path(prof):
                    raise ValueError("unknown profile")
                patch["profile"] = prof or None
            if "model" in body:
                patch["model"] = str(body["model"] or "") or None
            if "effort" in body:
                effort = str(body["effort"])
                if effort not in DEFAULT_EFFORTS:
                    raise ValueError("invalid effort")
                patch["effort"] = effort
            if "cwd" in body:
                patch["cwd"] = validate_cwd(str(body["cwd"]))
            if "title" in body:
                patch["title"] = str(body["title"] or "新对话")[:120]
            cfg = update_session_state(sid, patch)
            self._json(200, {"config": cfg})
            return
        self._json(404, {"error": "not found"})

    def _handle_api_delete(self, path: str) -> None:
        parts = path.strip("/").split("/")
        if len(parts) != 3 or parts[0] != "api" or parts[1] != "session" or not parts[2]:
            self._json(404, {"error": "not found"})
            return
        sid = parts[2]

        active = active_sessions().get(sid)
        if active is not None:
            self._json(409, {"error": "这个桌面 Claude Code 对话仍在运行，不能删除。请先结束桌面会话。"})
            return
        with TASK_LOCK:
            busy = any(
                (task.get("sessionId") == sid or task.get("effectiveSessionId") == sid)
                and task.get("status") in ("queued", "waiting_external", "running", "stopping")
                for task in TASKS.values()
            )
        if busy:
            self._json(409, {"error": "这个对话仍有手机端任务在运行或排队，不能删除。"})
            return

        transcript = session_file(sid)
        with STATE_LOCK:
            state = load_state()
            sessions_state = state.setdefault("sessions", {})
            had_state = sid in sessions_state
            if transcript is None and not had_state:
                self._json(404, {"error": "session not found"})
                return
            if transcript is not None:
                transcript.unlink()
            sessions_state.pop(sid, None)
            save_state(state)

        self._json(200, {"ok": True, "sessionId": sid})

    def _serve_static(self, path: str) -> None:
        if path in ("", "/"):
            path = "/index.html"
        elif path == "/pair":
            path = "/pair.html"
        rel = path.lstrip("/")
        target = (WEB_DIR / rel).resolve()
        try:
            target.relative_to(WEB_DIR.resolve())
        except ValueError:
            self.send_error(404)
            return
        if not target.exists() or not target.is_file():
            # SPA fallback
            target = WEB_DIR / "index.html"
        data = target.read_bytes()
        ctype = mimetypes.guess_type(str(target))[0] or "application/octet-stream"
        if target.suffix == ".js":
            ctype = "text/javascript"
        self.send_response(200)
        self.send_header("Content-Type", f"{ctype}; charset=utf-8" if ctype.startswith("text/") or ctype in ("application/javascript", "application/json") else ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-cache")
        self._security_headers()
        self.end_headers()
        self.wfile.write(data)


class BridgeServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address: tuple[str, int], handler, auth_token: str):
        super().__init__(address, handler)
        self.auth_token = auth_token


def local_ipv4s() -> list[str]:
    ips: set[str] = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip and not ip.startswith("127.") and not ip.startswith("169.254."):
                ips.add(ip)
    except Exception:
        pass
    return sorted(ips)


def main() -> int:
    parser = argparse.ArgumentParser(description=APP_NAME)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8787)
    args = parser.parse_args()

    token = load_or_create_token()
    ensure_data_dir()
    PID_FILE.write_text(str(os.getpid()), encoding="utf-8")

    server = BridgeServer((args.host, args.port), BridgeHandler, token)
    print(f"{APP_NAME} listening on http://{args.host}:{args.port}", flush=True)
    print("Authentication: Bearer token loaded (value intentionally not logged)", flush=True)
    try:
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        try:
            if PID_FILE.exists() and PID_FILE.read_text(encoding="utf-8").strip() == str(os.getpid()):
                PID_FILE.unlink()
        except Exception:
            pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
