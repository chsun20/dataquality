<#
    Start / stop RuoYi backend services as independent processes.
    Processes survive after the current terminal closes; logs go to .\logs\.

    Usage:
      powershell -ExecutionPolicy Bypass -File .\bin\start-backend.ps1
      powershell -ExecutionPolicy Bypass -File .\bin\start-backend.ps1 -Only auth,gateway
      powershell -ExecutionPolicy Bypass -File .\bin\start-backend.ps1 -Stop
#>
param(
    [string[]]$Only = @('auth', 'system', 'dqservice', 'gateway'),
    [switch]$Stop
)

# -File invocation passes "auth,system" as one token; split it
$Only = @($Only | ForEach-Object { $_ -split ',' } | Where-Object { $_ })

$root = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $root 'logs'

# Prefer JDK 8 (project runtime), fall back to JAVA_HOME / PATH
$jdk8 = 'C:\Program Files\Eclipse Adoptium\jdk-8.0.504.1-hotspot\bin\java.exe'
if (Test-Path $jdk8) {
    $java = $jdk8
} elseif ($env:JAVA_HOME) {
    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
    $java = 'java'
}

$all = [ordered]@{
    auth      = @{ Jar = 'ruoyi-auth\target\ruoyi-auth.jar';                           Port = 9200 }
    system    = @{ Jar = 'ruoyi-modules\ruoyi-system\target\ruoyi-modules-system.jar';  Port = 9201 }
    dqservice = @{ Jar = 'ruoyi-modules\ruoyi-dqservice\target\ruoyi-dqservice.jar';    Port = 9205 }
    gateway   = @{ Jar = 'ruoyi-gateway\target\ruoyi-gateway.jar';                      Port = 8089 }
}

function Get-Listener([int]$Port) {
    Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
}

# ---------- stop ----------
if ($Stop) {
    foreach ($name in $Only) {
        $svc = $all[$name]
        if (-not $svc) { Write-Host "[SKIP] unknown service '$name'"; continue }
        $listeners = Get-Listener $svc.Port
        if (-not $listeners) { Write-Host "[DOWN] $name (:$($svc.Port)) is not running"; continue }
        foreach ($procId in ($listeners.OwningProcess | Select-Object -Unique)) {
            $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
            if ($proc -and $proc.ProcessName -eq 'java') {
                Stop-Process -Id $procId -Force
                Write-Host "[STOP] $name (PID $procId, :$($svc.Port))"
            } else {
                Write-Host "[WARN] :$($svc.Port) is held by non-java process PID $procId, skipped"
            }
        }
    }
    return
}

# ---------- start ----------
New-Item -ItemType Directory -Path $logDir -Force | Out-Null
Write-Host "Java : $java"
Write-Host "Logs : $logDir"
Write-Host ""

$started = @()
foreach ($name in $Only) {
    $svc = $all[$name]
    if (-not $svc) { Write-Host "[SKIP] unknown service '$name'"; continue }

    if (Get-Listener $svc.Port) {
        Write-Host "[SKIP] $name (:$($svc.Port)) already listening"
        continue
    }

    $jar = Join-Path $root $svc.Jar
    if (-not (Test-Path $jar)) {
        Write-Host "[ERR ] $name jar not found: $jar"
        continue
    }

    $out = Join-Path $logDir "$name.out.log"
    $err = Join-Path $logDir "$name.err.log"
    $proc = Start-Process -FilePath $java `
        -ArgumentList '-Dfile.encoding=utf-8', '-Xms512m', '-Xmx1024m', '-jar', $jar `
        -WorkingDirectory (Split-Path -Parent $jar) `
        -RedirectStandardOutput $out -RedirectStandardError $err `
        -WindowStyle Hidden -PassThru

    Write-Host "[START] $name  PID=$($proc.Id)  port=$($svc.Port)"
    $started += $name
}

if ($started.Count -eq 0) { Write-Host "Nothing to start."; return }

# Wait for ports (max ~120s); gateway is last so it starts after upstreams register
Write-Host ""
Write-Host "Waiting for ports..."
$deadline = (Get-Date).AddSeconds(120)
foreach ($name in $started) {
    $svc = $all[$name]
    $ok = $false
    while ((Get-Date) -lt $deadline) {
        if (Get-Listener $svc.Port) { $ok = $true; break }
        Start-Sleep -Seconds 2
    }
    if ($ok) { Write-Host ("  [UP  ] {0,-10} :{1}" -f $name, $svc.Port) }
    else     { Write-Host ("  [FAIL] {0,-10} :{1}  check logs\{0}.out.log" -f $name, $svc.Port) }
}
