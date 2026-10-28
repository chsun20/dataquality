<#
    Start the RuoYi frontend dev server in a separate PowerShell window.

    Node 17+ needs --openssl-legacy-provider because @vue/cli-service 4.x uses
    webpack 4 (md4 hashing), otherwise you get ERR_OSSL_EVP_UNSUPPORTED.

    Usage:
      powershell -ExecutionPolicy Bypass -File .\bin\start-ui.ps1
      powershell -ExecutionPolicy Bypass -File .\bin\start-ui.ps1 -Port 8080
#>
param(
    [int]$Port = 8081,
    [string]$UiPath = ''
)

# default: sibling folder of the backend repo (d:\workspace\dataquality-ui-main)
if (-not $UiPath) {
    $UiPath = Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'dataquality-ui-main'
}

if (-not (Test-Path $UiPath)) {
    Write-Host "[ERR ] frontend folder not found: $UiPath"
    exit 1
}
if (-not (Test-Path (Join-Path $UiPath 'node_modules'))) {
    Write-Host "[ERR ] node_modules missing. Run first:"
    Write-Host "       cd `"$UiPath`"; npm install --registry=https://registry.npmmirror.com"
    exit 1
}

$listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
if ($listener) {
    Write-Host "[SKIP] port $Port is already in use (PID $($listener[0].OwningProcess))"
    exit 0
}

$npmCmd = "`$env:NODE_OPTIONS='--openssl-legacy-provider'; " +
          "`$env:npm_config_port='$Port'; " +
          "Set-Location '$UiPath'; " +
          "npm run dev"

Write-Host "UI path : $UiPath"
Write-Host "URL     : http://localhost:$Port"
Write-Host "Starting a new window; close that window to stop the dev server."
Start-Process powershell -ArgumentList '-NoExit', '-Command', $npmCmd
