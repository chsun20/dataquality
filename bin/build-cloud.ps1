#requires -Version 5.1
<#
.SYNOPSIS
  构建质控系统独立演示环境（本机 Docker Compose 先行，后续可迁移到云服务器）。

.DESCRIPTION
  1. 把后端源码复制到 docker/cloud/build/src 再打包，避免覆盖正在运行的 JAR；
  2. 构建 gateway、auth、system、dqservice 四个可执行 JAR；
  3. 构建业务前端 dist（默认同级 dataquality-ui-main，不使用后端内旧 ruoyi-ui）；
  4. 首次运行生成 docker/cloud/.env（随机凭据，已被忽略）；
  5. 可选地构建镜像，但不启动容器。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File bin/build-cloud.ps1
  powershell -ExecutionPolicy Bypass -File bin/build-cloud.ps1 -SkipFrontend -SkipImages
#>
param(
    [string]$BackendDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path,
    [string]$UiDir = (Join-Path (Split-Path -Parent (Resolve-Path (Join-Path $PSScriptRoot '..')).Path) 'dataquality-ui-main'),
    [switch]$SkipBackend,
    [switch]$SkipFrontend,
    [switch]$SkipImages
)

$ErrorActionPreference = 'Stop'

function Write-Step([string]$message) {
    Write-Host ""
    Write-Host "==> $message" -ForegroundColor Cyan
}

function New-RandomSecret {
    param([int]$Length = 24)
    $chars = 'abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'
    $bytes = New-Object byte[] $Length
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($bytes)
    $rng.Dispose()
    -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
}

function New-RandomAesKey {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($bytes)
    $rng.Dispose()
    [Convert]::ToBase64String($bytes)
}

$cloudDir = Join-Path $BackendDir 'docker\cloud'
$buildRoot = Join-Path $cloudDir 'build'
$srcDir = Join-Path $buildRoot 'src'
$jarDir = Join-Path $buildRoot 'jars'
$webDir = Join-Path $buildRoot 'web'
$envFile = Join-Path $cloudDir '.env'

if (-not (Test-Path $cloudDir)) { throw "未找到 $cloudDir" }

if (-not $SkipBackend) {
    Write-Step "复制后端源码到独立构建目录（排除 target、.git、docker 等）"
    if (Test-Path $srcDir) { Remove-Item -Path $srcDir -Recurse -Force }
    $excludeDirs = @('target', '.git', '.qoder', '.tmp', 'logs', 'docs', 'node_modules', 'docker', 'ruoyi-ui', '.idea', '.vscode')
    $robocopyArgs = @($BackendDir, $srcDir, '/E', '/NFL', '/NDL', '/NJH', '/NJS', '/XD') + $excludeDirs + @('/XF', '*.log')
    & robocopy @robocopyArgs | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "robocopy 复制失败，退出码 $LASTEXITCODE" }

    Write-Step "打包后端四个服务（跳过测试）"
    Push-Location $srcDir
    try {
        & mvn.cmd -B -q -DskipTests -pl ruoyi-gateway,ruoyi-auth,ruoyi-modules/ruoyi-system,ruoyi-modules/ruoyi-dqservice -am package
        if ($LASTEXITCODE -ne 0) { throw "Maven 打包失败，退出码 $LASTEXITCODE" }
    }
    finally { Pop-Location }

    Write-Step "收集 JAR 到 docker/cloud/build/jars"
    if (Test-Path $jarDir) { Remove-Item -Path $jarDir -Recurse -Force }
    New-Item -ItemType Directory -Path $jarDir -Force | Out-Null
    $jars = @{
        'ruoyi-gateway.jar'          = 'ruoyi-gateway\target\ruoyi-gateway.jar'
        'ruoyi-auth.jar'             = 'ruoyi-auth\target\ruoyi-auth.jar'
        'ruoyi-modules-system.jar'   = 'ruoyi-modules\ruoyi-system\target\ruoyi-modules-system.jar'
        'ruoyi-dqservice.jar'        = 'ruoyi-modules\ruoyi-dqservice\target\ruoyi-dqservice.jar'
    }
    foreach ($name in $jars.Keys) {
        $source = Join-Path $srcDir $jars[$name]
        if (-not (Test-Path $source)) { throw "缺少构建产物：$source" }
        Copy-Item $source (Join-Path $jarDir $name) -Force
        Write-Host ("    {0}  {1:N1} MB" -f $name, ((Get-Item $source).Length / 1MB))
    }
}

if (-not $SkipFrontend) {
    Write-Step "构建业务前端 dist：$UiDir"
    if (-not (Test-Path $UiDir)) { throw "未找到前端仓库：$UiDir" }
    Push-Location $UiDir
    try {
        if (-not (Test-Path 'node_modules')) {
            Write-Host '    未检测到 node_modules，执行 npm ci'
            & npm.cmd ci
            if ($LASTEXITCODE -ne 0) { throw "npm ci 失败，退出码 $LASTEXITCODE" }
        }
        # 前端是 Vue CLI 4 + webpack 4，Node 17+ 需要 legacy provider
        $env:NODE_OPTIONS = '--openssl-legacy-provider'
        & npm.cmd run build:prod
        if ($LASTEXITCODE -ne 0) { throw "前端构建失败，退出码 $LASTEXITCODE" }
    }
    finally {
        Remove-Item Env:NODE_OPTIONS -ErrorAction SilentlyContinue
        Pop-Location
    }

    if (-not (Test-Path (Join-Path $UiDir 'dist\index.html'))) { throw '前端未生成 dist/index.html' }
    if (Test-Path $webDir) { Remove-Item -Path $webDir -Recurse -Force }
    New-Item -ItemType Directory -Path $webDir -Force | Out-Null
    Copy-Item (Join-Path $UiDir 'dist\*') $webDir -Recurse -Force
    Write-Host "    dist 已复制到 $webDir"
}

Write-Step '检查演示环境变量'
if (-not (Test-Path $envFile)) {
    $lines = @(
        '# 由 bin/build-cloud.ps1 自动生成，含随机凭据，禁止提交',
        "DQ_DB_ROOT_PASSWORD=$(New-RandomSecret)",
        'DQ_DB_USER=ry',
        "DQ_DB_PASSWORD=$(New-RandomSecret)",
        "DQ_REDIS_PASSWORD=$(New-RandomSecret)",
        "DQ_SRC_ROOT_PASSWORD=$(New-RandomSecret)",
        'DQ_SRC_READER_USER=dq_reader',
        "DQ_SRC_READER_PASSWORD=$(New-RandomSecret)",
        "DQ_SOURCE_AES_KEY=$(New-RandomAesKey)",
        'DQ_HTTP_PORT=18080'
    )
    [System.IO.File]::WriteAllLines($envFile, $lines, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "    已生成 $envFile"
}
else {
    Write-Host "    复用已存在的 $envFile"
}

if (-not $SkipImages) {
    Write-Step '构建镜像（不启动容器）'
    Push-Location $cloudDir
    try {
        & docker compose build
        if ($LASTEXITCODE -ne 0) { throw "docker compose build 失败，退出码 $LASTEXITCODE" }
    }
    finally { Pop-Location }
}

Write-Step '完成'
Write-Host '启动演示环境：'
Write-Host '  docker compose --project-directory docker/cloud up -d'
Write-Host '访问地址（仅本机）：http://127.0.0.1:18080'
Write-Host '双副本演示步骤见 README.md 的「本地独立演示环境」小节'
