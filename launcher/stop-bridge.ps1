$ErrorActionPreference = 'Stop'

$ProjectRoot = 'D:\GitHub\claude-mobile-bridge'
$DataDir = Join-Path $ProjectRoot 'data'
$TokenFile = Join-Path $DataDir 'auth-token.txt'
$TunnelIdFile = Join-Path $DataDir 'tunnel-id.txt'
$TunnelPidFile = Join-Path $DataDir 'tunnel.pid'
$Port = 8787

function Find-DevTunnelExe {
  $cmd = Get-Command devtunnel.exe -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }
  $packages = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Packages'
  if (Test-Path $packages) {
    return (Get-ChildItem $packages -Directory -Filter 'Microsoft.devtunnel_*' -ErrorAction SilentlyContinue |
      ForEach-Object { Join-Path $_.FullName 'devtunnel.exe' } |
      Where-Object { Test-Path $_ } |
      Select-Object -First 1)
  }
  return $null
}

function Get-VerifiedTunnelProcess {
  param([string]$Exe, [string]$TunnelId)
  if (-not $Exe -or -not $TunnelId -or -not (Test-Path $TunnelPidFile)) { return $null }
  $pidText = (Get-Content $TunnelPidFile -Raw).Trim()
  if ($pidText -notmatch '^\d+$') { return $null }
  $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$pidText" -ErrorAction SilentlyContinue
  if (-not $proc) { return $null }
  if ($proc.ExecutablePath -and ([IO.Path]::GetFullPath($proc.ExecutablePath) -ne [IO.Path]::GetFullPath($Exe))) { return $null }
  if ($proc.CommandLine -notlike '* host *' -or $proc.CommandLine -notlike "*$TunnelId*") { return $null }
  return $proc
}

$devtunnel = Find-DevTunnelExe
$tunnelId = if (Test-Path $TunnelIdFile) { (Get-Content $TunnelIdFile -Raw).Trim() } else { $null }
$tunnel = Get-VerifiedTunnelProcess -Exe $devtunnel -TunnelId $tunnelId

if ($tunnel) {
  Stop-Process -Id $tunnel.ProcessId -Force -ErrorAction Stop
  Write-Host "[OK] 已停止 Claude Mobile Bridge Dev Tunnel，PID $($tunnel.ProcessId)。" -ForegroundColor Green
} elseif (Test-Path $TunnelPidFile) {
  Write-Host '[WARN] tunnel.pid 存在，但进程身份无法核验；为避免误杀，没有停止任何未知进程。' -ForegroundColor Yellow
}
Remove-Item $TunnelPidFile -Force -ErrorAction SilentlyContinue

if (-not (Test-Path $TokenFile)) {
  Write-Host '[OK] 未发现 Bridge 访问令牌，未执行进程级停止。'
  exit 0
}

$token = (Get-Content $TokenFile -Raw).Trim()
try {
  Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$Port/api/shutdown" `
    -Headers @{ Authorization = "Bearer $token" } `
    -ContentType 'application/json' -Body '{}' -TimeoutSec 3 | Out-Null
  Write-Host '[OK] 已向 Claude Mobile Bridge 发送安全停止请求。' -ForegroundColor Green
} catch {
  Write-Host '[OK] Bridge 当前未响应；没有停止任何其他进程。' -ForegroundColor Yellow
}
