<#
    Stop the whole local dev environment in one shot: frontend dev server,
    backend Java services and middleware containers.

    Stop order is the reverse of startup (UI -> backend -> middleware) so the
    apps do not log connection errors while their dependencies disappear.

    Usage:
      powershell -ExecutionPolicy Bypass -File .\bin\stop-all.ps1
      powershell -ExecutionPolicy Bypass -File .\bin\stop-all.ps1 -KeepMiddleware
      powershell -ExecutionPolicy Bypass -File .\bin\stop-all.ps1 -KeepUi -KeepBackend
#>
param(
    [switch]$KeepUi,
    [switch]$KeepBackend,
    [switch]$KeepMiddleware,
    [int]$UiPort = 8081
)

# 停止顺序：Nacos 先停，避免它对着已停的 MySQL 刷错误日志
$middlewareContainers = @('ruoyi-nacos', 'ruoyi-mysql', 'ruoyi-redis')

function Test-DockerEngine {
    & docker info *> $null
    return ($LASTEXITCODE -eq 0)
}

function Stop-Ui {
    $listeners = Get-NetTCPConnection -State Listen -LocalPort $UiPort -ErrorAction SilentlyContinue
    if (-not $listeners) { Write-Host "[DOWN] ui       :$UiPort not running"; return }

    $stopped = $false
    foreach ($procId in ($listeners.OwningProcess | Select-Object -Unique)) {
        $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
        if ($proc -and $proc.ProcessName -in @('node', 'nodejs')) {
            Stop-Process -Id $procId -Force
            Write-Host "[STOP] ui       (PID $procId, :$UiPort)"
            $stopped = $true
        }
        else {
            # 端口被 IDE / 其它服务占用时不误杀
            Write-Host "[WARN] :$UiPort 被非 node 进程 PID $procId 占用，已跳过"
        }
    }
    if (-not $stopped) { Write-Host "[WARN] ui       :$UiPort 未能停止" }
}

function Stop-Backend {
    $script = Join-Path $PSScriptRoot 'start-backend.ps1'
    if (-not (Test-Path $script)) { Write-Host "[ERR ] 未找到 $script"; return }
    & powershell -NoProfile -ExecutionPolicy Bypass -File $script -Stop
}

function Stop-Middleware {
    if (-not (Test-DockerEngine)) { Write-Host "[SKIP] middleware (Docker 不可用)"; return }

    $running = @()
    foreach ($name in $middlewareContainers) {
        $state = & docker inspect --format '{{.State.Status}}' $name 2>$null
        if ($LASTEXITCODE -eq 0 -and "$state".Trim() -eq 'running') { $running += $name }
    }
    if ($running.Count -eq 0) { Write-Host "[DOWN] middleware all containers stopped"; return }

    foreach ($name in $running) {
        & docker stop $name | Out-Null
        if ($LASTEXITCODE -eq 0) { Write-Host "[STOP] $name" }
        else { Write-Host "[WARN] 停止失败: $name" }
    }
}

Write-Host "Stopping local dev environment..."
Write-Host ""

if ($KeepUi) { Write-Host "[SKIP] ui       (KeepUi)" } else { Stop-Ui }
if ($KeepBackend) { Write-Host "[SKIP] backend  (KeepBackend)" } else { Stop-Backend }
if ($KeepMiddleware) { Write-Host "[SKIP] middleware (KeepMiddleware)" } else { Stop-Middleware }

Write-Host ""
Write-Host "Done."
