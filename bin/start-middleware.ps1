<#
    Start the middleware containers (MySQL / Redis / Nacos) of the dev
    environment and wait until each service actually answers.

    "Port open" is not enough: MySQL accepts TCP before it finishes
    initializing, and Nacos opens 8848 before the console is ready, so
    readiness is probed inside the container / over HTTP.

    Containers come from docker\docker-compose.yml (compose project "docker").
    Missing containers are created via compose; existing ones are just started.

    Usage:
      powershell -ExecutionPolicy Bypass -File .\bin\start-middleware.ps1
      powershell -ExecutionPolicy Bypass -File .\bin\start-middleware.ps1 -Status
      powershell -ExecutionPolicy Bypass -File .\bin\start-middleware.ps1 -TimeoutSeconds 180
#>
param(
    [switch]$Status,
    [int]$TimeoutSeconds = 120
)

$root = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $root 'docker\docker-compose.yml'
$nacosReadiness = 'http://127.0.0.1:8848/nacos/v1/console/health/readiness'

# Start order matters only for the first creation: Nacos reads config from MySQL.
$services = [ordered]@{
    mysql = @{ Container = 'ruoyi-mysql'; Port = 3306 }
    redis = @{ Container = 'ruoyi-redis'; Port = 6379 }
    nacos = @{ Container = 'ruoyi-nacos'; Port = 8848 }
}

function Test-DockerEngine {
    & docker info *> $null
    return ($LASTEXITCODE -eq 0)
}

function Test-Port([int]$Port) {
    return [bool](Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
}

function Get-ContainerState([string]$Name) {
    $state = & docker inspect --format '{{.State.Status}}' $Name 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    return ("$state".Trim())
}

function Get-MySqlRootPassword {
    # 密码取自容器环境变量，不在脚本里重复维护凭据
    $lines = & docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' 'ruoyi-mysql' 2>$null
    $line = @($lines | Where-Object { $_ -like 'MYSQL_ROOT_PASSWORD=*' })[0]
    if ($line) { return $line.Substring('MYSQL_ROOT_PASSWORD='.Length) }
    return ''
}

function Test-MySqlReady([string]$Password) {
    if (-not (Test-Port 3306)) { return $false }
    if ($Password) {
        & docker exec ruoyi-mysql mysqladmin ping -uroot "-p$Password" --silent *> $null
    } else {
        & docker exec ruoyi-mysql mysqladmin ping -uroot --silent *> $null
    }
    return ($LASTEXITCODE -eq 0)
}

function Test-RedisReady {
    if (-not (Test-Port 6379)) { return $false }
    $reply = & docker exec ruoyi-redis redis-cli ping 2>$null
    return ("$reply".Trim() -eq 'PONG')
}

function Test-NacosReady {
    if (-not (Test-Port 8848)) { return $false }
    try {
        $response = Invoke-WebRequest -Uri $nacosReadiness -UseBasicParsing -TimeoutSec 3
        # Nacos 2.0.x 返回 OK，部分版本返回 UP
        return ($response.Content.Trim() -match '^(OK|UP)$')
    } catch { return $false }
}

function Test-Ready([string]$Key, [string]$MySqlPassword) {
    switch ($Key) {
        'mysql' { return (Test-MySqlReady -Password $MySqlPassword) }
        'redis' { return (Test-RedisReady) }
        'nacos' { return (Test-NacosReady) }
    }
    return $false
}

if (-not (Test-DockerEngine)) {
    Write-Host '[ERR ] Docker 未运行或不可用，请先启动 Docker Desktop'
    exit 1
}

# ---------- status ----------
if ($Status) {
    $mysqlPwd = Get-MySqlRootPassword
    Write-Host '中间件状态：'
    foreach ($key in $services.Keys) {
        $svc = $services[$key]
        $state = Get-ContainerState $svc.Container
        if ($null -eq $state) { $state = 'not-created' }
        $ready = $false
        if ($state -eq 'running') { $ready = Test-Ready $key $mysqlPwd }
        $flag = if ($ready) { '[UP  ]' } elseif ($state -eq 'running') { '[BOOT]' } else { '[DOWN]' }
        Write-Host ("  {0} {1,-6} :{2}  container={3}  state={4}" -f $flag, $key, $svc.Port, $svc.Container, $state)
    }
    return
}

# ---------- start ----------
Write-Host "Compose : $composeFile"
Write-Host ""

foreach ($key in $services.Keys) {
    $svc = $services[$key]
    $state = Get-ContainerState $svc.Container

    if ($null -eq $state) {
        Write-Host "[CREATE] $($svc.Container) 容器不存在，按 compose 创建"
        & docker compose -f $composeFile up -d $svc.Container | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Host "[ERR ] compose 创建失败: $($svc.Container)" }
        continue
    }
    if ($state -eq 'running') {
        Write-Host "[SKIP] $($svc.Container) 已在运行"
        continue
    }

    & docker start $svc.Container | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Host "[ERR ] 启动失败: $($svc.Container)"; continue }
    Write-Host "[START] $($svc.Container)  port=$($svc.Port)"
}

# ---------- wait ----------
Write-Host ""
Write-Host "Waiting for readiness (max ${TimeoutSeconds}s)..."
$mysqlPwd = Get-MySqlRootPassword
$pending = [ordered]@{}
foreach ($key in $services.Keys) { $pending[$key] = $true }

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ($pending.Count -gt 0 -and (Get-Date) -lt $deadline) {
    foreach ($key in @($pending.Keys)) {
        if (Test-Ready $key $mysqlPwd) { $pending.Remove($key) }
    }
    if ($pending.Count -gt 0) { Start-Sleep -Seconds 2 }
}

foreach ($key in $services.Keys) {
    $svc = $services[$key]
    if ($pending.Contains($key)) {
        Write-Host ("  [FAIL] {0,-6} :{1}  check: docker logs --tail 50 {2}" -f $key, $svc.Port, $svc.Container)
    } else {
        Write-Host ("  [UP  ] {0,-6} :{1}" -f $key, $svc.Port)
    }
}

if ($pending.Count -gt 0) { exit 1 }

Write-Host ""
Write-Host "中间件已就绪。下一步：powershell -ExecutionPolicy Bypass -File .\bin\start-backend.ps1"
