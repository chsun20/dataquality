#requires -Version 5.1
<#
.SYNOPSIS
  双副本（1 Gateway + 2 dqservice）演示：请求分流、停一个实例后由另一实例继续、恢复后重新加入。

.DESCRIPTION
  演示只使用已鉴权的只读查询接口 GET /prod-api/dqservice/rule/list，
  通过响应头 X-DQ-Instance 判断本次请求由哪个副本处理。
  不自动重试写接口，也不宣称任务续跑或零失败。

  登录令牌需要人工在浏览器登录后取得（验证码保持开启）：
    浏览器打开 http://127.0.0.1:18080 → 登录 → F12 → Application → Cookies → 复制 Admin-Token
    或用 -Token 直接传入，或写入 docker/cloud/build/token.txt 后省略 -Token。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File bin/demo-ha.ps1 -Action status
  powershell -ExecutionPolicy Bypass -File bin/demo-ha.ps1 -Action probe -Token <token>
  powershell -ExecutionPolicy Bypass -File bin/demo-ha.ps1 -Action failover -Token <token>
#>
param(
    [ValidateSet('status', 'login', 'seed', 'probe', 'stop-a', 'start-a', 'failover', 'execute')]
    [string]$Action = 'status',
    [string]$BaseUrl = 'http://127.0.0.1:18080',
    [string]$Token,
    [string]$TokenFile = (Join-Path (Resolve-Path (Join-Path $PSScriptRoot '..')).Path 'docker\cloud\build\token.txt'),
    [string]$EnvFile = (Join-Path (Resolve-Path (Join-Path $PSScriptRoot '..')).Path 'docker\cloud\.env'),
    [string]$Username = 'admin',
    [string]$Password = 'admin123',
    [int]$Count = 20,
    [long]$RuleId = 0,
    [int]$MaxWaitSeconds = 60,
    # 优雅停止会先向 Nacos 注销，流量可在命令返回前就切换完成；
    # 加 -HardKill 模拟进程崩溃（不主动注销），用于对比断开连接与优雅停机两种摘除路径。
    [switch]$HardKill
)

$ErrorActionPreference = 'Stop'
$readPath = "$BaseUrl/prod-api/dqservice/rule/list"

function Get-Token {
    if ($Token) { return $Token.Trim() }
    if (Test-Path $TokenFile) { return (Get-Content $TokenFile -Raw).Trim() }
    throw "缺少访问令牌：请用 -Token 传入，或把 Admin-Token 写入 $TokenFile"
}

function Invoke-DemoLogin {
    if (-not (Test-Path $EnvFile)) { throw "未找到演示环境变量文件：$EnvFile" }
    $redisPwd = ((Get-Content $EnvFile | Select-String '^DQ_REDIS_PASSWORD=') -split '=', 2)[1]
    if (-not $redisPwd) { throw '未能从 .env 读取 DQ_REDIS_PASSWORD' }

    # 验证码保持开启：这里走真实登录接口，从演示 Redis 取本次验证码答案，仅用于自动化验证
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $code = Invoke-RestMethod -Uri "$BaseUrl/prod-api/code" -TimeoutSec 20
        $uuid = $code.uuid
        $raw = docker exec dq-demo-platform-redis redis-cli --no-auth-warning -a $redisPwd get "captcha_codes:$uuid"
        $answer = (($raw | Select-Object -First 1).Trim()).Trim('"')
        if (-not $answer) { throw '演示 Redis 中找不到本次验证码，请确认容器 dq-demo-platform-redis 正常' }

        $body = @{ username = $Username; password = $Password; code = $answer; uuid = $uuid } | ConvertTo-Json -Compress
        try {
            $login = Invoke-RestMethod -Uri "$BaseUrl/prod-api/auth/login" -Method Post -ContentType 'application/json' `
                -Body $body -TimeoutSec 30
        }
        catch {
            Write-Host "    第 $attempt 次登录请求失败：$($_.Exception.Message)" -ForegroundColor Yellow
            Start-Sleep -Seconds 2
            continue
        }
        if ($login.code -eq 200 -and $login.data.access_token) {
            $dir = Split-Path -Parent $TokenFile
            if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
            $login.data.access_token | Set-Content $TokenFile -NoNewline
            Write-Host "登录成功，令牌已保存到 $TokenFile" -ForegroundColor Green
            return $login.data.access_token
        }
        Write-Host "    第 $attempt 次登录未成功：$($login.msg)" -ForegroundColor Yellow
        Start-Sleep -Seconds 2
    }
    throw '登录失败：请改用浏览器登录后把 Admin-Token 写入 token.txt'
}

function Invoke-DqProbe {
    param([string]$Id, [string]$Bearer)
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = Invoke-WebRequest -Uri $readPath -Method Get -UseBasicParsing -TimeoutSec 15 `
            -Headers @{ Authorization = "Bearer $Bearer" }
        $watch.Stop()
        $instance = $response.Headers['X-DQ-Instance']
        if ($instance -is [array]) { $instance = $instance[0] }
        return [pscustomobject]@{
            Id       = $Id
            Success  = $true
            Instance = if ($instance) { [string]$instance } else { '(无实例标识)' }
            Ms       = [math]::Round($watch.Elapsed.TotalMilliseconds)
            Status   = $response.StatusCode
            Error    = $null
        }
    }
    catch {
        $watch.Stop()
        $status = $null
        if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        return [pscustomobject]@{
            Id       = $Id
            Success  = $false
            Instance = '-'
            Ms       = [math]::Round($watch.Elapsed.TotalMilliseconds)
            Status   = $status
            Error    = $_.Exception.Message
        }
    }
}

function Show-Distribution {
    param([object[]]$Results, [string]$Title)
    Write-Host ""
    Write-Host $Title -ForegroundColor Cyan
    $ok = @($Results | Where-Object { $_.Success })
    $failed = @($Results | Where-Object { -not $_.Success })
    Write-Host ("    请求 {0} 次，成功 {1}，失败 {2}" -f $Results.Count, $ok.Count, $failed.Count)
    $ok | Group-Object Instance | Sort-Object Name | ForEach-Object {
        Write-Host ("    实例 {0,-14} 处理 {1} 次" -f $_.Name, $_.Count)
    }
    if ($failed.Count -gt 0) {
        $failed | Select-Object -First 5 | ForEach-Object {
            Write-Host ("    失败示例：状态 {0}，耗时 {1} ms，{2}" -f $_.Status, $_.Ms, $_.Error) -ForegroundColor Yellow
        }
    }
}

function Wait-Until {
    param(
        [scriptblock]$Probe,
        [int]$TimeoutSeconds,
        [string]$Description,
        [string]$ExpectedInstance,
        # 恢复后请求会在两个副本间交替，要求“连续 N 次都来自 dq-a”的概率约为 1/2^N，
        # 因此恢复确认只看“该实例是否重新出现”，只有摘除确认才要求连续 N 次。
        [switch]$StopAfterFirst,
        [int]$ConsecutiveTarget = 10,
        [int]$PollMilliseconds = 500
    )
    $start = Get-Date
    $consecutive = 0
    $firstMatchSeconds = $null
    $matchCount = 0
    $records = New-Object System.Collections.ArrayList
    while (((Get-Date) - $start).TotalSeconds -lt $TimeoutSeconds) {
        $result = & $Probe
        $result | Add-Member -NotePropertyName AtSeconds -NotePropertyValue ([math]::Round(((Get-Date) - $start).TotalSeconds, 1)) -Force
        [void]$records.Add($result)
        $matched = $result.Success -and ((-not $ExpectedInstance) -or ($result.Instance -eq $ExpectedInstance))
        if ($matched) {
            $matchCount++
            if ($null -eq $firstMatchSeconds) {
                $firstMatchSeconds = [math]::Round(((Get-Date) - $start).TotalSeconds, 1)
            }
            if ($StopAfterFirst) { break }
            $consecutive++
            if ($consecutive -ge $ConsecutiveTarget) { break }
        }
        else {
            $consecutive = 0
        }
        Start-Sleep -Milliseconds $PollMilliseconds
    }
    $elapsed = [math]::Round(((Get-Date) - $start).TotalSeconds, 1)
    $failed = @($records | Where-Object { -not $_.Success })
    $reached = if ($StopAfterFirst) { $matchCount -ge 1 } else { $consecutive -ge $ConsecutiveTarget }
    $instanceLabel = if ($ExpectedInstance) { $ExpectedInstance } else { '任一实例' }
    $firstLabel = if ($null -eq $firstMatchSeconds) { '未出现' } else { "$firstMatchSeconds 秒" }
    if ($reached) {
        if ($StopAfterFirst) {
            Write-Host ("    {0}：首个 {1} 成功响应于 {2}（轮询间隔 {3} ms）" -f `
                $Description, $instanceLabel, $firstLabel, $PollMilliseconds) -ForegroundColor Green
        }
        else {
            Write-Host ("    {0}：首个 {1} 成功响应于 {2}，连续 {3} 次成功于 {4} 秒达成（轮询间隔 {5} ms）" -f `
                $Description, $instanceLabel, $firstLabel, $ConsecutiveTarget, $elapsed, $PollMilliseconds) -ForegroundColor Green
        }
    }
    else {
        Write-Host ("    {0}：{1} 秒等待上限内未出现 {2} 的成功响应，演示判定失败" -f $Description, $elapsed, $instanceLabel) -ForegroundColor Red
    }
    return [pscustomobject]@{
        Reached             = $reached
        FirstSuccessSeconds = $firstMatchSeconds
        MatchCount          = $matchCount
        ElapsedSeconds      = $elapsed
        FailureCount        = $failed.Count
        Records             = $records.ToArray()
    }
}

function Show-Status {
    Write-Host "容器状态（独立 Compose 项目 dq-demo）：" -ForegroundColor Cyan
    docker ps -a --filter "name=dq-demo-" --format "{{.Names}}`t{{.Status}}`t{{.Ports}}"
}

function Invoke-DqApi {
    param([string]$Method, [string]$Path, $Body, [string]$Bearer)
    $request = @{
        Uri         = "$BaseUrl/prod-api$Path"
        Method      = $Method
        UseBasicParsing = $true
        TimeoutSec  = 120
        Headers     = @{ Authorization = "Bearer $Bearer" }
    }
    if ($null -ne $Body) {
        # PowerShell 5.1 默认按 ANSI 发送字符串，中文名称必须显式转为 UTF-8 字节
        $json = $Body | ConvertTo-Json -Depth 8 -Compress
        $request.ContentType = 'application/json; charset=utf-8'
        $request.Body = [System.Text.Encoding]::UTF8.GetBytes($json)
    }
    $response = Invoke-WebRequest @request
    $instance = $response.Headers['X-DQ-Instance']
    if ($instance -is [array]) { $instance = $instance[0] }
    Write-Host ("    {0,-6} {1,-38} 实例={2}" -f $Method, $Path, $instance)
    return ($response.Content | ConvertFrom-Json)
}

function Invoke-DemoSeed {
    $bearer = Get-Token
    if (-not (Test-Path $EnvFile)) { throw "未找到演示环境变量文件：$EnvFile" }
    $readerPwd = ((Get-Content $EnvFile | Select-String '^DQ_SRC_READER_PASSWORD=') -split '=', 2)[1]

    $sources = Invoke-DqApi -Method GET -Path '/dqservice/dataSource/list' -Bearer $bearer
    $source = $sources.rows | Where-Object { $_.databaseName -eq 'dq_demo' } | Select-Object -First 1
    if (-not $source) {
        $sourceBody = @{
            name = '演示-客户库'; type = 'MYSQL'; description = '独立演示数据源（只读账号）'
            host = '172.30.51.10'; port = 3306; databaseName = 'dq_demo'; username = 'dq_reader'
            password = $readerPwd; tlsMode = 'REQUIRED'; timeZone = 'Asia/Shanghai'; enabled = $true
        }
        $test = Invoke-DqApi -Method POST -Path '/dqservice/dataSource/test' -Body $sourceBody -Bearer $bearer
        if ($test.code -ne 200) { throw "数据源试连失败：$($test.msg)" }
        $created = Invoke-DqApi -Method POST -Path '/dqservice/dataSource' -Body $sourceBody -Bearer $bearer
        if ($created.code -ne 200) { throw "数据源创建失败：$($created.msg)" }
        $sources = Invoke-DqApi -Method GET -Path '/dqservice/dataSource/list' -Bearer $bearer
        $source = $sources.rows | Where-Object { $_.databaseName -eq 'dq_demo' } | Select-Object -First 1
    }
    if (-not $source) { throw '未能创建或找到演示数据源' }
    Write-Host "    数据源 id=$($source.id) 库=$($source.databaseName)" -ForegroundColor Green

    $rules = Invoke-DqApi -Method GET -Path '/dqservice/rule/list' -Bearer $bearer
    $rule = $rules.rows | Where-Object { $_.tableName -eq 'dq_customer' } | Select-Object -First 1
    if (-not $rule) {
        $ruleBody = @{
            ruleName = '演示-客户年龄空值检测'
            dbName = 'dq_demo'
            sourceId = $source.id
            tableName = 'dq_customer'
            typeName = '空值检测'
            typeCode = 'nullCount'
            cols = @(
                @{ name = 'age'; comment = '年龄'; type = 'int'; rules = @('nullCount'); params = @{} }
            )
        }
        $createdRule = Invoke-DqApi -Method POST -Path '/dqservice/rule' -Body $ruleBody -Bearer $bearer
        if ($createdRule.code -ne 200) { throw "规则创建失败：$($createdRule.msg)" }
        $rules = Invoke-DqApi -Method GET -Path '/dqservice/rule/list' -Bearer $bearer
        $rule = $rules.rows | Where-Object { $_.tableName -eq 'dq_customer' } | Select-Object -First 1
    }
    if (-not $rule) { throw '未能创建或找到演示规则' }
    Write-Host "    规则 id=$($rule.ruleId) 模板=$($rule.templateCode)" -ForegroundColor Green
    Write-Host "    下一步：demo-ha.ps1 -Action execute -RuleId $($rule.ruleId)" -ForegroundColor Cyan
}

switch ($Action) {
    'status' {
        Show-Status
    }
    'login' {
        Invoke-DemoLogin | Out-Null
    }
    'seed' {
        Invoke-DemoSeed
    }
    'stop-a' {
        Show-Status
        Write-Host "停止 dq-demo-dqservice-a ..." -ForegroundColor Yellow
        docker stop dq-demo-dqservice-a | Out-Null
        Show-Status
    }
    'start-a' {
        Write-Host "启动 dq-demo-dqservice-a ..." -ForegroundColor Yellow
        docker start dq-demo-dqservice-a | Out-Null
        Show-Status
    }
    'probe' {
        $bearer = Get-Token
        $results = 1..$Count | ForEach-Object { Invoke-DqProbe -Id $_ -Bearer $bearer }
        Show-Distribution -Results $results -Title "连续 $Count 次只读请求分布"
    }
    'execute' {
        if ($RuleId -le 0) { throw '请用 -RuleId 指定要执行的规则' }
        $bearer = Get-Token
        # 写接口只发一次：失败或结果未知时先回查报告，不自动重试
        $uri = "$BaseUrl/prod-api/dqservice/execute/$RuleId?limit=1000"
        $watch = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            $response = Invoke-WebRequest -Uri $uri -Method Post -UseBasicParsing -TimeoutSec 120 `
                -Headers @{ Authorization = "Bearer $bearer" }
            $watch.Stop()
            $instance = $response.Headers['X-DQ-Instance']
            Write-Host ("执行成功：实例 {0}，耗时 {1} ms" -f $instance, $watch.ElapsedMilliseconds) -ForegroundColor Green
            Write-Host $response.Content
        }
        catch {
            $watch.Stop()
            Write-Host ("执行失败或结果未知（耗时 {0} ms）：{1}" -f $watch.ElapsedMilliseconds, $_.Exception.Message) -ForegroundColor Yellow
            Write-Host '在途请求可能已写入报告，请先到「质量报告」回查，再决定是否重新执行。'
        }
    }
    'failover' {
        $bearer = Get-Token
        Write-Host "目标：$BaseUrl；只读接口：$readPath" -ForegroundColor Cyan

        $warmup = 1..$Count | ForEach-Object { Invoke-DqProbe -Id $_ -Bearer $bearer }
        Show-Distribution -Results $warmup -Title "步骤 1：正常运行，连续 $Count 次请求"

        Write-Host ""
        if ($HardKill) {
            Write-Host '步骤 2：强制终止 dqservice-a（模拟进程崩溃，不走优雅停机注销）' -ForegroundColor Yellow
            docker kill dq-demo-dqservice-a | Out-Null
        }
        else {
            Write-Host '步骤 2：停止 dqservice-a（模拟实例故障，优雅停机）' -ForegroundColor Yellow
            docker stop dq-demo-dqservice-a | Out-Null
        }

        $down = Wait-Until -Description '故障摘除' -TimeoutSeconds $MaxWaitSeconds -ExpectedInstance 'dq-b' -Probe {
            Invoke-DqProbe -Id 0 -Bearer $bearer
        }
        $downRecords = @($down.Records)
        $afterFirst = @($downRecords | Where-Object { $_.Success -and $_.Instance -ne 'dq-b' })
        if ($down.Reached -and $afterFirst.Count -eq 0) {
            Write-Host '    摘除后连续 10 次请求均由 dq-b 响应，达标' -ForegroundColor Green
        }
        else {
            Write-Host '    摘除后未能确认全部由 dq-b 响应，本轮演示失败' -ForegroundColor Red
        }
        Show-Distribution -Results $downRecords -Title '步骤 2 期间的全部请求记录'

        Write-Host ""
        Write-Host '步骤 3：恢复 dqservice-a' -ForegroundColor Yellow
        docker start dq-demo-dqservice-a | Out-Null
        $up = Wait-Until -Description '实例恢复加入' -TimeoutSeconds $MaxWaitSeconds -ExpectedInstance 'dq-a' -StopAfterFirst -Probe {
            Invoke-DqProbe -Id 0 -Bearer $bearer
        }
        if ($up.Reached) {
            Write-Host ("    dq-a 重新承接请求，恢复加入耗时约 {0} 秒（从 docker start 返回到首个 dq-a 成功响应）" -f $up.FirstSuccessSeconds) -ForegroundColor Green
        }
        else {
            Write-Host '    恢复等待上限内未观察到 dq-a 参与，请检查容器日志与 Nacos 注册记录' -ForegroundColor Red
        }

        $final = 1..$Count | ForEach-Object { Invoke-DqProbe -Id $_ -Bearer $bearer }
        Show-Distribution -Results $final -Title "步骤 4：恢复后连续 $Count 次请求"
        $finalOk = @($final | Where-Object { $_.Success })
        $finalA = @($finalOk | Where-Object { $_.Instance -eq 'dq-a' }).Count
        $finalB = @($finalOk | Where-Object { $_.Instance -eq 'dq-b' }).Count
        if ($finalA -gt 0 -and $finalB -gt 0) {
            Write-Host ("    恢复后两个实例同时参与服务（dq-a {0} 次 / dq-b {1} 次）" -f $finalA, $finalB) -ForegroundColor Green
        }
        else {
            Write-Host ("    恢复后未能同时观察到两个实例（dq-a {0} 次 / dq-b {1} 次）" -f $finalA, $finalB) -ForegroundColor Red
        }

        Write-Host ""
        Write-Host '边界说明：' -ForegroundColor Cyan
        $downLabel = if ($null -eq $down.FirstSuccessSeconds) { '未出现' } else { "$($down.FirstSuccessSeconds) 秒" }
        $upLabel = if ($null -eq $up.FirstSuccessSeconds) { '未出现' } else { "$($up.FirstSuccessSeconds) 秒" }
        if ($down.FailureCount -gt 0) {
            $failedTimes = @($down.Records | Where-Object { -not $_.Success } | ForEach-Object { $_.AtSeconds })
            $failSpan = "，失败请求 {0} 次（{1}-{2} 秒）" -f $down.FailureCount, ($failedTimes | Measure-Object -Minimum).Minimum, ($failedTimes | Measure-Object -Maximum).Maximum
        }
        else {
            $failSpan = '，期间没有失败请求'
        }
        Write-Host ("    1) 切换延迟：停止后首个 dq-b 成功响应出现在 {0}{1}。" -f $downLabel, $failSpan)
        if ($HardKill) {
            Write-Host '       Nacos 2.x 在 gRPC 连接断开时立即注销实例，实测强杀后的切换仍接近 0；只有连接未断的网络黑洞等场景才需等 15 秒心跳超时。'
        }
        else {
            Write-Host '       优雅停止时服务先自行注销，流量可在命令返回前切换，因此失败请求可能为 0。'
        }
        Write-Host ("    2) 恢复延迟：docker start 返回到 dq-a 首个成功响应 {0}，其中包含 JVM 启动与 Nacos 注册耗时。" -f $upLabel)
        Write-Host '    3) 在途检测不会自动续跑：检测在请求线程内一次跑完，承载实例被停时该请求直接失败或结果未知，不会由另一副本接手；请先在「质量报告」回查本次执行是否已落库，再决定是否重跑。'
        Write-Host '    4) 本演示只覆盖 dqservice 进程级故障；gateway、MySQL、Redis、Nacos 与宿主机仍是单点。'
    }
}
