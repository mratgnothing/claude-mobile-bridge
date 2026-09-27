# Claude Mobile Bridge

Claude Mobile Bridge 让 Android 手机通过固定公网 HTTPS 地址操作这台 Windows 电脑上的 Claude Code。手机端采用聊天式界面，可以新建/恢复会话、选择现有 API Profile、模型和 effort，同时保留 Claude Code 自己的项目上下文发现机制。

## 一键启动

桌面快捷入口：

- `C:\Users\ASUS\Desktop\快捷指令\06-Claude手机桥\01-启动Claude手机桥.cmd`
- `C:\Users\ASUS\Desktop\快捷指令\06-Claude手机桥\02-停止Claude手机桥.cmd`

启动脚本会：

1. 在 `127.0.0.1:8787` 启动 Bridge；本机端口不会直接暴露到局域网或公网。
2. 自动定位 Microsoft Dev Tunnels CLI；缺失时通过官方 winget 包安装。
3. 检查 Dev Tunnels 登录；优先尝试 Windows 集成认证，必要时才打开一次浏览器登录。
4. 复用持久 Tunnel `atg-claude-mobile.asse`，并把有效期重新延长到 30 天。
5. 仅转发本机 8787 HTTP 服务，通过 HTTPS/WSS 443 建立公网连接。
6. 做公网健康检查。
7. 把完整的 `https://.../#token=...` 手机访问地址写入剪贴板和 `data\public-url.txt`。

当前固定公网基础地址为：

`https://5w9x2l1v-8787.asse.devtunnels.ms/`

完整地址还包含本机生成的随机 `#token=...`。不要把完整带 token 的地址发送给他人。

Microsoft Dev Tunnels 对持久资源当前采用最长 30 天有效期；启动脚本每次运行都会续到 30 天，所以正常使用时复用同一 Tunnel 和同一公网域名。

## Claude Code 上下文一致性

Bridge 启动 Claude Code 时明确使用：

- 原会话/所选项目的真实工作目录 `cwd`。
- `--setting-sources user,project,local`。
- 正常模式，而不是 `--bare` 或 `--safe-mode`。
- 正常的 CLAUDE.md 自动发现。
- 正常的 user/project/local settings。
- 正常的 skills、plugins 和 MCP 加载。
- Claude Code 自己的项目 auto-memory / `MEMORY.md`。
- 历史对话使用原 session ID 和 `--resume`；新对话使用新的 UUID 和 `--session-id`。

手机页面会显示“上下文”状态条，核对当前 CWD、setting sources、发现的 CLAUDE.md 数量、Memory 状态以及 Skills/Plugins/MCP 状态。

对于从桌面创建的历史会话，Bridge 会从原始 transcript 恢复 CWD、模型和可恢复的 effort 信息，避免把历史会话错误地从用户主目录启动。

## API / 模型 / effort

顶部选择器直接读取 `C:\Users\ASUS\.claude\profiles\*.json`。

切换 API/模型不会修改：

`C:\Users\ASUS\.claude\profiles\.current`

Bridge 对自己的下一轮 Claude Code 进程显式传入对应 `--settings <profile>`、`--model` 和 `--effort`。因此手机会话之间可以分别使用 4Router、MicuAPI/NewAPI、Messages 等已有 Profile，而不会把桌面另一个 Claude 会话的全局 Profile 切走。

支持 effort：`low / medium / high / xhigh / max`。

## Claude Code 指令

Claude Code 原生 `/xxx` 指令默认不由 Bridge 冒充，而是原样交给 Claude Code。手机界面的 API/模型/effort 控件用于替代最常用的交互式配置选择。

Bridge 自己只占用以下命名空间：

- `/bridge-status`
- `/bridge-profile <profile-id>`
- `/bridge-model <model-id>`
- `/bridge-effort <level>`
- `/bridge-cwd <path>`
- `/bridge-help`

`/compact`、`/config`、`/context`、`/model`、`/effort`、`/mcp`、`/init`、`/rename` 等仍属于 Claude Code。

## 活跃桌面对话控制

Bridge 会识别正在运行的桌面 Claude Code 会话，并优先复用同一个终端而不是另起一个互相竞争的进程。

当桌面 session 仍在运行时：

- 手机可以持续读取 transcript 和当前状态；
- 桌面 Claude 空闲时，手机输入会直接送入同一个 Claude Code 终端；
- 桌面 Claude 正在执行时，手机输入可以直接注入当前终端继续引导；
- `/btw <内容>` 使用 Claude Code 的旁问语义，不打断主任务；
- 手机端停止按钮会以 Ctrl+C 语义中止当前轮次，而不是粗暴结束整个桌面会话；
- Bridge 会避免并发启动第二个 Claude Code 进程写入同一个 session。

浏览器/PWA 前端仍保留较保守的只读保护逻辑；完整的运行中控制能力以 Android 客户端为准。

## 公网安全模型

公网入口与 Windows 本机之间不是路由器端口映射：

`Android -> HTTPS Dev Tunnel -> WSS/443 outbound -> 127.0.0.1:8787 -> Claude Mobile Bridge -> Claude Code`

安全措施：

- 8787 默认只绑定 `127.0.0.1`。
- Dev Tunnel 仅转发 8787。
- Bridge API 强制随机 Bearer token。
- token 放在 URL fragment (`#token=`) 中，浏览器首次 HTTP 请求不会把 fragment 发给服务器；前端读取后立即从地址栏移除并保存在该浏览器本地。
- 服务端不再接受 `?token=` 作为 API 认证方式。
- 服务端启动日志不会打印 token；访问日志只记录 URL path，不记录 query string。
- API 响应使用 `Cache-Control: no-store`。
- 前端和 API 带 CSP、`X-Frame-Options: DENY`、`nosniff` 和限制性 `Referrer-Policy` 等安全响应头；公网边缘可能进一步覆盖为自己的更严格/兼容策略。
- 未携带 Bearer token 的 `/api/*` 请求返回 401；`/api/health` 仅暴露最小健康状态。

陌生浏览器首次访问 Microsoft Dev Tunnel 时，微软可能显示一次反钓鱼确认页。确认后进入 Bridge；Android 浏览器可随后“添加到主屏幕”，以 standalone PWA 形式使用。

## 停止

运行 `02-停止Claude手机桥.cmd`。

停止脚本只会：

1. 读取 `data\tunnel.pid`。
2. 同时核验 PID、`devtunnel.exe` 路径、`host` 命令和 Tunnel ID 后停止该 Dev Tunnel host。
3. 使用 Bridge 当前 token 调用本机 `/api/shutdown`。

它不会按模糊进程名停止 Claude、Node、Python 或其他会话。
