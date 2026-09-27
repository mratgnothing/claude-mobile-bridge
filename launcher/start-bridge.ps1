param(
  [switch]$NoPrompt,
  [switch]$RedactToken
)

$ErrorActionPreference = 'Stop'

$ProjectRoot = 'D:\GitHub\claude-mobile-bridge'
$Server = Join-Path $ProjectRoot 'server.py'
$DataDir = Join-Path $ProjectRoot 'data'
$PidFile = Join-Path $DataDir 'server.pid'
$TokenFile = Join-Path $DataDir 'auth-token.txt'
$TunnelIdFile = Join-Path $DataDir 'tunnel-id.txt'
$TunnelPidFile = Join-Path $DataDir 'tunnel.pid'
$PublicUrlFile = Join-Path $DataDir 'public-url.txt'
$OutLog = Join-Path $DataDir 'server.out.log'
$ErrLog = Join-Path $DataDir 'server.err.log'
$TunnelOutLog = Join-Path $DataDir 'devtunnel.out.log'
$TunnelErrLog = Join-Path $DataDir 'devtunnel.err.log'
$Port = 8787
$TunnelBaseName = 'atg-claude-mobile'
$TunnelDescription = 'ATG Claude Code Mobile Bridge'

New-Item -ItemType Directory -Force -Path $DataDir | Out-Null

function Test-BridgeHealth {
  try {
    $r = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/api/health" -Headers @{ Accept = 'application/json' } -UseBasicParsing -TimeoutSec 2
    return $r.StatusCode -eq 200
  } catch { return $false }
}

function Get-VerifiedBridgeProcess {
  if (-not (Test-Path $PidFile)) { return $null }
  $pidText = (Get-Content $PidFile -Raw).Trim()
  if ($pidText -notmatch '^\d+$') { return $null }
  $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$pidText" -ErrorAction SilentlyContinue
  if (-not $proc) { return $null }
  if ($proc.CommandLine -notlike "*$ProjectRoot*server.py*") { return $null }
  return $proc
}

function Test-BridgeLoopbackOnly {
  param([int]$ProcessId)
  $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
    Where-Object { $_.OwningProcess -eq $ProcessId })
  if ($listeners.Count -eq 0) { return $false }
  foreach ($listener in $listeners) {
    if ($listener.LocalAddress -notin @('127.0.0.1', '::1')) { return $false }
  }
  return $true
}

function Stop-VerifiedBridgeGracefully {
  $proc = Get-VerifiedBridgeProcess
  if (-not $proc) { return }
  if (-not (Test-Path $TokenFile)) { throw '发现 Bridge 进程，但缺少认证令牌；为避免误杀，未停止。' }
  $token = (Get-Content $TokenFile -Raw).Trim()
  try {
    Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$Port/api/shutdown" `
      -Headers @{ Authorization = "Bearer $token" } `
      -ContentType 'application/json' -Body '{}' -TimeoutSec 3 | Out-Null
  } catch {
    throw "现有 Bridge 无法通过认证接口安全停止；为避免误杀，未继续。$($_.Exception.Message)"
  }
  for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Milliseconds 200
    if (-not (Get-CimInstance Win32_Process -Filter "ProcessId=$($proc.ProcessId)" -ErrorAction SilentlyContinue)) { return }
  }
  throw 'Bridge 收到停止请求后仍未退出；为避免强杀未知进程，已中止启动。'
}

function Start-LoopbackBridge {
  $existing = Get-VerifiedBridgeProcess
  if ($existing -and (Test-BridgeHealth)) {
    if (Test-BridgeLoopbackOnly -ProcessId $existing.ProcessId) {
      Write-Host "[OK] Claude Mobile Bridge 已安全运行在回环地址，PID $($existing.ProcessId)。" -ForegroundColor Green
      return
    }
    Write-Host '[INFO] 检测到旧 Bridge 监听非回环地址，正在通过认证接口安全重启为 127.0.0.1-only。' -ForegroundColor Yellow
    Stop-VerifiedBridgeGracefully
  } elseif ($existing) {
    throw '发现已登记的 Bridge 进程但健康检查失败；为避免误操作，未覆盖该进程。'
  }

  if (-not (Test-Path $Server)) { throw "找不到服务端：$Server" }
  $python = (Get-Command python.exe -ErrorAction Stop).Source
  $proc = Start-Process -FilePath $python `
    -ArgumentList @($Server, '--host', '127.0.0.1', '--port', "$Port") `
    -WorkingDirectory $ProjectRoot `
    -WindowStyle Hidden `
    -RedirectStandardOutput $OutLog `
    -RedirectStandardError $ErrLog `
    -PassThru

  $ready = $false
  for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Milliseconds 250
    if (Test-BridgeHealth) { $ready = $true; break }
    if ($proc.HasExited) { break }
  }
  if (-not $ready) {
    if (Test-Path $ErrLog) { Get-Content $ErrLog -Tail 40 }
    throw 'Claude Mobile Bridge 启动失败。'
  }
  if (-not (Test-BridgeLoopbackOnly -ProcessId $proc.Id)) {
    throw 'Bridge 启动后没有保持 loopback-only；已拒绝继续建立公网隧道。'
  }
  Write-Host "[OK] Claude Mobile Bridge 已启动，PID $($proc.Id)，仅监听 127.0.0.1:$Port。" -ForegroundColor Green
}

function Find-DevTunnelExe {
  $cmd = Get-Command devtunnel.exe -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }

  $packages = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Packages'
  if (Test-Path $packages) {
    $found = Get-ChildItem $packages -Directory -Filter 'Microsoft.devtunnel_*' -ErrorAction SilentlyContinue |
      ForEach-Object { Join-Path $_.FullName 'devtunnel.exe' } |
      Where-Object { Test-Path $_ } |
      Select-Object -First 1
    if ($found) { return $found }
  }

  Write-Host '[SETUP] 首次使用：正在安装 Microsoft Dev Tunnels CLI…' -ForegroundColor Cyan
  $winget = (Get-Command winget.exe -ErrorAction Stop).Source
  & $winget install -e --id Microsoft.devtunnel --accept-source-agreements --accept-package-agreements --silent
  if ($LASTEXITCODE -ne 0) { throw 'Microsoft Dev Tunnels CLI 安装失败。' }

  $found = Get-ChildItem $packages -Directory -Filter 'Microsoft.devtunnel_*' -ErrorAction SilentlyContinue |
    ForEach-Object { Join-Path $_.FullName 'devtunnel.exe' } |
    Where-Object { Test-Path $_ } |
    Select-Object -First 1
  if (-not $found) { throw 'Dev Tunnels CLI 已安装，但无法定位 devtunnel.exe。' }
  return $found
}

function Ensure-DevTunnelLogin {
  param([string]$Exe)
  $show = (& $Exe user show 2>&1 | Out-String)
  if ($show -notmatch 'Not logged in') { return }

  Write-Host '[SETUP] Dev Tunnels 尚未登录，优先尝试 Windows 集成认证…' -ForegroundColor Cyan
  & $Exe user login --use-integrated-windows-auth --entra
  $show = (& $Exe user show 2>&1 | Out-String)
  if ($show -notmatch 'Not logged in') { return }

  Write-Host '[SETUP] Windows 集成认证不可用，将打开 Microsoft 登录。只需首次完成一次。' -ForegroundColor Yellow
  & $Exe user login --use-browser-auth --entra
  $show = (& $Exe user show 2>&1 | Out-String)
  if ($show -match 'Not logged in') { throw 'Dev Tunnels 登录未完成。' }
}

function Invoke-DevTunnelJson {
  param(
    [string]$Exe,
    [string[]]$Arguments
  )
  $text = (& $Exe @Arguments 2>&1 | Out-String)
  if ($LASTEXITCODE -ne 0) { throw "devtunnel 命令失败：$text" }
  try { return ($text | ConvertFrom-Json) }
  catch { throw "devtunnel 返回了无法解析的 JSON：$text" }
}

function Ensure-PersistentTunnel {
  param([string]$Exe)

  $tunnelId = $null
  if (Test-Path $TunnelIdFile) {
    $candidate = (Get-Content $TunnelIdFile -Raw).Trim()
    if ($candidate) {
      try {
        $shown = Invoke-DevTunnelJson -Exe $Exe -Arguments @('show', $candidate, '--json')
        if ($shown.tunnel.tunnelId) { $tunnelId = [string]$shown.tunnel.tunnelId }
      } catch {}
    }
  }

  if (-not $tunnelId) {
    try {
      $list = Invoke-DevTunnelJson -Exe $Exe -Arguments @('list', '--json')
      $items = @()
      if ($list.tunnels) { $items = @($list.tunnels) }
      elseif ($list -is [System.Array]) { $items = @($list) }
      $match = $items | Where-Object { $_.description -eq $TunnelDescription -or $_.tunnelId -like "$TunnelBaseName.*" } | Select-Object -First 1
      if ($match) { $tunnelId = [string]$match.tunnelId }
    } catch {}
  }

  if (-not $tunnelId) {
    Write-Host '[SETUP] 正在创建持久 Claude Mobile Bridge Dev Tunnel…' -ForegroundColor Cyan
    $created = Invoke-DevTunnelJson -Exe $Exe -Arguments @('create', $TunnelBaseName, '--allow-anonymous', '--description', $TunnelDescription, '--expiration', '30d', '--json')
    $tunnelId = [string]$created.tunnel.tunnelId
    if (-not $tunnelId) { throw 'Dev Tunnel 创建成功但没有返回 tunnelId。' }
  }

  Set-Content -Path $TunnelIdFile -Value $tunnelId -Encoding ascii

  # Dev Tunnels currently cap resource expiration at 30 days. Refreshing it on
  # every launcher run keeps the same tunnel/URL alive indefinitely in normal use.
  $null = Invoke-DevTunnelJson -Exe $Exe -Arguments @('update', $tunnelId, '--expiration', '30d', '--json')

  $shown = Invoke-DevTunnelJson -Exe $Exe -Arguments @('show', $tunnelId, '--json')
  $port = @($shown.tunnel.ports) | Where-Object { [int]$_.portNumber -eq $Port } | Select-Object -First 1
  if (-not $port) {
    $null = Invoke-DevTunnelJson -Exe $Exe -Arguments @('port', 'create', $tunnelId, '--port-number', "$Port", '--protocol', 'http', '--description', 'Claude Mobile Bridge HTTP', '--json')
    $shown = Invoke-DevTunnelJson -Exe $Exe -Arguments @('show', $tunnelId, '--json')
    $port = @($shown.tunnel.ports) | Where-Object { [int]$_.portNumber -eq $Port } | Select-Object -First 1
  }
  if (-not $port -or -not $port.portUri) { throw 'Dev Tunnel 8787 端口没有可用的公网 URL。' }

  return @{
    TunnelId = $tunnelId
    PublicBase = ([string]$port.portUri).TrimEnd('/')
  }
}

function Get-VerifiedTunnelProcess {
  param([string]$Exe, [string]$TunnelId)
  if (-not (Test-Path $TunnelPidFile)) { return $null }
  $pidText = (Get-Content $TunnelPidFile -Raw).Trim()
  if ($pidText -notmatch '^\d+$') { return $null }
  $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$pidText" -ErrorAction SilentlyContinue
  if (-not $proc) { return $null }
  if ($proc.ExecutablePath -and ([IO.Path]::GetFullPath($proc.ExecutablePath) -ne [IO.Path]::GetFullPath($Exe))) { return $null }
  if ($proc.CommandLine -notlike '* host *' -or $proc.CommandLine -notlike "*$TunnelId*") { return $null }
  return $proc
}

function Stop-VerifiedTunnel {
  param([string]$Exe, [string]$TunnelId)
  $proc = Get-VerifiedTunnelProcess -Exe $Exe -TunnelId $TunnelId
  if ($proc) {
    Stop-Process -Id $proc.ProcessId -Force -ErrorAction Stop
    for ($i = 0; $i -lt 20; $i++) {
      Start-Sleep -Milliseconds 150
      if (-not (Get-CimInstance Win32_Process -Filter "ProcessId=$($proc.ProcessId)" -ErrorAction SilentlyContinue)) { break }
    }
  }
  Remove-Item $TunnelPidFile -Force -ErrorAction SilentlyContinue
}

function Test-PublicHealth {
  param([string]$BaseUrl)
  try {
    $headers = @{
      Accept = 'application/json'
      'X-Tunnel-Skip-AntiPhishing-Page' = '1'
    }
    $r = Invoke-WebRequest -Uri "$BaseUrl/api/health" -Headers $headers -UseBasicParsing -TimeoutSec 10
    return $r.StatusCode -eq 200
  } catch { return $false }
}

Start-LoopbackBridge

$devtunnel = Find-DevTunnelExe
Ensure-DevTunnelLogin -Exe $devtunnel
$tunnelInfo = Ensure-PersistentTunnel -Exe $devtunnel
$tunnelId = $tunnelInfo.TunnelId
$baseUrl = $tunnelInfo.PublicBase

if (-not (Test-Path $TokenFile)) { throw '服务已启动，但访问令牌尚未生成。' }
$token = (Get-Content $TokenFile -Raw).Trim()
if ([string]::IsNullOrWhiteSpace($token)) { throw 'Bridge 访问令牌为空。' }

Stop-VerifiedTunnel -Exe $devtunnel -TunnelId $tunnelId
Remove-Item $TunnelOutLog, $TunnelErrLog -Force -ErrorAction SilentlyContinue

Write-Host '[INFO] 正在建立固定公网 HTTPS Dev Tunnel…' -ForegroundColor Cyan
$tunnel = Start-Process -FilePath $devtunnel `
  -ArgumentList @('host', $tunnelId) `
  -WorkingDirectory $ProjectRoot `
  -WindowStyle Hidden `
  -RedirectStandardOutput $TunnelOutLog `
  -RedirectStandardError $TunnelErrLog `
  -PassThru
Set-Content -Path $TunnelPidFile -Value $tunnel.Id -Encoding ascii

$publicReady = $false
for ($i = 0; $i -lt 80; $i++) {
  Start-Sleep -Milliseconds 500
  if ($tunnel.HasExited) { break }
  if (Test-PublicHealth -BaseUrl $baseUrl) { $publicReady = $true; break }
}
if (-not $publicReady) {
  Stop-VerifiedTunnel -Exe $devtunnel -TunnelId $tunnelId
  Write-Host '[ERROR] Dev Tunnel 未通过公网健康检查。' -ForegroundColor Red
  if (Test-Path $TunnelOutLog) { Get-Content $TunnelOutLog -Tail 50 }
  if (Test-Path $TunnelErrLog) { Get-Content $TunnelErrLog -Tail 50 }
  throw '公网 HTTPS 隧道启动失败。'
}

# The secret lives in the URL fragment, which browsers do not include in the
# initial HTTP request. app.js consumes it locally and then strips it from URL.
$publicUrl = "$baseUrl/#token=$token"
$localUrl = "http://127.0.0.1:$Port/#token=$token"
Set-Content -Path $PublicUrlFile -Value $publicUrl -Encoding utf8
try { Set-Clipboard -Value $publicUrl } catch {}

Write-Host ''
Write-Host '============================================================' -ForegroundColor DarkGray
Write-Host ' Claude Mobile Bridge 已可从公网访问' -ForegroundColor Green
Write-Host '============================================================' -ForegroundColor DarkGray
Write-Host ''
Write-Host '固定公网 HTTPS 地址（已复制到剪贴板）：' -ForegroundColor Cyan
if ($RedactToken) {
  Write-Host "  $baseUrl/#token=<redacted>"
} else {
  Write-Host "  $publicUrl"
}
Write-Host ''
Write-Host "Tunnel ID: $tunnelId" -ForegroundColor DarkGray
if ($RedactToken) {
  Write-Host "电脑本机：http://127.0.0.1:$Port/#token=<redacted>" -ForegroundColor DarkGray
} else {
  Write-Host "电脑本机：$localUrl" -ForegroundColor DarkGray
}
Write-Host ''
Write-Host '安全状态：' -ForegroundColor Cyan
Write-Host '  - 本机 8787 只监听 127.0.0.1，不开放路由器/Windows 公网入站端口。'
Write-Host '  - 公网通过 Microsoft Dev Tunnels 的 HTTPS/WSS 443 主动出站连接。'
Write-Host '  - 公网 Tunnel 虽允许浏览器连接，但 Bridge API 仍强制独立随机 Bearer token。'
Write-Host '  - token 位于 URL fragment，不会随首次 HTTP 请求发送；网页读取后会从地址栏移除。'
Write-Host '  - 每次启动自动把同一 Tunnel 的有效期续到 30 天，因此公网域名保持不变。'
Write-Host '  - 已在桌面运行的 Claude Code 会话不会被 attach/kill/重启；活跃会话手机端只读。'
Write-Host ''
Write-Host '提示：微软可能在陌生浏览器首次访问 Dev Tunnel 时显示一次反钓鱼确认页；确认后即可进入 Bridge。' -ForegroundColor Yellow
Write-Host '直接关闭此窗口不会停止服务；请使用“02-停止Claude手机桥.cmd”。' -ForegroundColor DarkGray

if ($NoPrompt) { exit 0 }

Write-Host ''
Write-Host '按任意键打开电脑端本地页面。'
$null = $Host.UI.RawUI.ReadKey('NoEcho,IncludeKeyDown')
Start-Process $localUrl
