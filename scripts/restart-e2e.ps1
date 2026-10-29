# 端到端重启测试：真实启动专用服务端三次，验证市场状态随世界存档保存、停服重启后接着走（不补算）、暂停状态保持。
# 用法：pwsh -NoProfile -File scripts/restart-e2e.ps1
# 流程：
#   1. 删除旧测试世界，启动服务端，等行情推进几步，用 creeper dev market advance 快进 2000 步攒出 K 线，
#      再执行 creeper dev market pause，再 stop。
#   2. 第二次启动：断言从存档恢复、步数与停服时一致、暂停保持；K 线文件读回的根数与步数一致（不缺不重）；
#      等待数秒确认冻结（不补算、不推进）。然后 creeper dev market resume，断言下一个行情步恰好是「停服步数 + 1」。运行中 stop。
#   3. 离线几秒后第三次启动：断言恢复的步数不变、K 线根数仍与步数一致，第一个行情步仍是「停服步数 + 1」（运行中停服同样不补算）。
# 断言依据服务端自己写的开发日志 logs/yyyy-MM-dd HH-mm-ss.log（每次启动一个文件）；
# 服务端控制台输出另存为 logs/restart-e2e <时间> 第N次.stdout.log，本脚本的过程记录在 logs/restart-e2e <时间>.log。
[CmdletBinding()]
param(
    [int]$StartupTimeoutSec = 300,
    [int]$FrozenWaitSec = 5,
    [int]$OfflineSec = 8
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$worldDir = Join-Path $root 'neoforge/runs/server/world'
$logDir = Join-Path $root 'logs'
New-Item -ItemType Directory -Force $logDir | Out-Null
$stamp = Get-Date -Format 'yyyy-MM-dd HH-mm-ss'
$logFile = Join-Path $logDir "restart-e2e $stamp.log"
$utf8 = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = $utf8
$devLogPattern = '^\d{4}-\d{2}-\d{2} \d{2}-\d{2}-\d{2}\.log$'

function Write-Log([string]$text) {
    [System.IO.File]::AppendAllText($logFile, $text + [Environment]::NewLine, $utf8)
    Write-Host $text
}

# 启动一次服务端。服务端自己的开发日志是断言依据；控制台输出只落盘，同时避免管道写满阻塞服务端。
function Start-Server([string]$label, [int]$index) {
    Write-Log "===== $label：启动服务端 ====="
    $before = @(Get-ChildItem $logDir -File | Where-Object { $_.Name -match $devLogPattern } | ForEach-Object Name)
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = Join-Path $root 'gradlew.bat'
    $psi.Arguments = ':neoforge:runServer --console=plain'
    $psi.WorkingDirectory = $root
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.StandardOutputEncoding = $utf8
    $psi.StandardErrorEncoding = $utf8
    $proc = [System.Diagnostics.Process]::Start($psi)
    $stdoutFile = [System.IO.File]::Create((Join-Path $logDir "restart-e2e $stamp 第${index}次.stdout.log"))
    $copyOut = $proc.StandardOutput.BaseStream.CopyToAsync($stdoutFile)
    $copyErr = $proc.StandardError.BaseStream.CopyToAsync([System.IO.Stream]::Null)
    return @{ Process = $proc; Before = $before; DevLog = $null; Offset = 0L; Seen = (New-Object System.Collections.Generic.List[string]); Pending = (New-Object System.Collections.Generic.List[string]) }
}

# 读出开发日志里新增的完整行（服务端写日志时共享读取）。
function Read-NewLines($server) {
    if (-not $server.DevLog) {
        $created = Get-ChildItem $logDir -File | Where-Object { $_.Name -match $devLogPattern -and $server.Before -notcontains $_.Name } | Select-Object -First 1
        if (-not $created) { return @() }
        $server.DevLog = $created.FullName
        Write-Log "开发日志：$($created.Name)"
    }
    $stream = [System.IO.File]::Open($server.DevLog, 'Open', 'Read', 'ReadWrite')
    try {
        $stream.Seek($server.Offset, 'Begin') | Out-Null
        $buffer = New-Object byte[] ($stream.Length - $server.Offset)
        $read = $stream.Read($buffer, 0, $buffer.Length)
        $text = $utf8.GetString($buffer, 0, $read)
    } finally { $stream.Dispose() }
    $lastNewline = $text.LastIndexOf("`n")
    if ($lastNewline -lt 0) { return @() }
    $server.Offset += $utf8.GetByteCount($text.Substring(0, $lastNewline + 1))
    return $text.Substring(0, $lastNewline + 1) -split "`r?`n" | Where-Object { $_ -ne '' }
}

# 取出尚未处理的日志行：上次匹配之后剩下的行排在前面，再接上日志里新增的行。
function Take-Lines($server) {
    $lines = @($server.Pending) + @(Read-NewLines $server)
    $server.Pending.Clear()
    return $lines
}

# 等到开发日志出现匹配 $pattern 的行并返回；匹配行之后同一批读到的行留给下一次调用，不丢。超时或服务端提前退出则失败。
function Wait-ForLine($server, [string]$pattern, [int]$timeoutSec) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $lines = @(Take-Lines $server)
        for ($i = 0; $i -lt $lines.Count; $i++) {
            $line = $lines[$i]
            $server.Seen.Add($line)
            if ($line -match '\[(ServerMarket|MinecraftServer)\]') { Write-Log $line }
            if ($line -match $pattern) {
                for ($j = $i + 1; $j -lt $lines.Count; $j++) { $server.Pending.Add($lines[$j]) }
                return $line
            }
        }
        if ($server.Process.HasExited) { break }
        Start-Sleep -Milliseconds 100
    }
    throw "等待超时或服务端提前退出：$pattern"
}

# 把当前已写入的日志读完，返回其中匹配 $pattern 的行（用于断言“这段时间里没有发生某事”）。
function Drain-Matching($server, [string]$pattern) {
    $hits = @()
    foreach ($line in (Take-Lines $server)) {
        $server.Seen.Add($line)
        if ($line -match '\[(ServerMarket|MinecraftServer)\]') { Write-Log $line }
        if ($line -match $pattern) { $hits += $line }
    }
    return $hits
}

function Send-Command($server, [string]$command) {
    Write-Log ">>> $command"
    $server.Process.StandardInput.WriteLine($command)
    $server.Process.StandardInput.Flush()
}

function Stop-Server($server) {
    Send-Command $server 'stop'
    $line = Wait-ForLine $server '停服时的市场' 60
    if (-not $server.Process.WaitForExit(120000)) { throw '服务端未能在 120 秒内退出' }
    return $line
}

function Assert-True([bool]$condition, [string]$message) {
    if (-not $condition) { throw "断言失败：$message" }
    Write-Log "[PASS] $message"
}

function Get-Step([string]$line, [string]$pattern) {
    return [long][regex]::Match($line, $pattern).Groups[1].Value
}

$snapshotStep = 'step=(\d+),'
$stepLog = '行情步 (\d+)：'
$klineLog = 'K 线存储 .*读回 1m (\d+) 根、1h (\d+) 根、1d (\d+) 根'

# 重启后 K 线存储读回的根数必须与市场步数严格对应：1 行情步 = 6 市场秒，所以 1m 共 floor(步数/10)+1 根（含正在走的一根），
# 1h 共 floor(步数/600)+1 根，1d 共 floor(步数/14400)+1 根。多了说明重复，少了说明有缺口。
function Assert-Klines($server, [long]$step, [string]$who) {
    $line = Wait-ForLine $server $klineLog 30
    $m = [regex]::Match($line, $klineLog)
    Assert-True ([long]$m.Groups[1].Value -eq [math]::Floor($step / 10) + 1) "$who：1m K 线 $($m.Groups[1].Value) 根，与步数 $step 对应（不缺不重）"
    Assert-True ([long]$m.Groups[2].Value -eq [math]::Floor($step / 600) + 1) "$who：1h K 线 $($m.Groups[2].Value) 根，与步数 $step 对应"
    Assert-True ([long]$m.Groups[3].Value -eq [math]::Floor($step / 14400) + 1) "$who：1d K 线 $($m.Groups[3].Value) 根，与步数 $step 对应"
}
try {
    if (Test-Path $worldDir) { Remove-Item -Recurse -Force $worldDir }

    # ---- 第一次启动：行情推进、暂停、停服 ----
    $first = Start-Server '第 1 次' 1
    Wait-ForLine $first '新建市场' $StartupTimeoutSec | Out-Null
    Wait-ForLine $first '行情步 5：' 60 | Out-Null
    Send-Command $first 'creeper dev market advance 2000'
    Wait-ForLine $first 'Market advanced|市场已快进' 60 | Out-Null
    Send-Command $first 'creeper dev market pause'
    Wait-ForLine $first 'Market paused|市场已暂停' 30 | Out-Null
    Start-Sleep -Seconds 2
    $stopLine = Stop-Server $first
    Assert-True ($stopLine -match $snapshotStep) '第 1 次停服时记录了市场步数'
    $savedStep = Get-Step $stopLine $snapshotStep
    Assert-True ($savedStep -ge 5) "停服前市场已推进到第 $savedStep 步（>= 5）"
    Assert-True ($savedStep -ge 2005) "快进后市场已推进到第 $savedStep 步（>= 2005）"
    Assert-True ($stopLine -match '暂停=true') '停服时市场处于暂停'
    $klineDir = Join-Path $worldDir 'creepercapital/klines'
    Assert-True ((Test-Path (Join-Path $klineDir 'DIAMONDCRP-1m.klines')) -and (Test-Path (Join-Path $klineDir 'DIAMONDCRP-1h.klines'))) 'K 线文件已写进世界目录'

    # ---- 第二次启动：从存档恢复、保持暂停，恢复后不补算 ----
    $second = Start-Server '第 2 次' 2
    $restoreLine = Wait-ForLine $second '从存档恢复市场|新建市场' $StartupTimeoutSec
    Assert-True ($restoreLine -match '从存档恢复市场') '重启后从存档恢复市场，而不是新建'
    Assert-True ((Get-Step $restoreLine $snapshotStep) -eq $savedStep) "恢复的步数与停服时一致（$savedStep）"
    Assert-True ($restoreLine -match '暂停=true') '暂停状态在重启后保持'
    Assert-Klines $second $savedStep '第 2 次启动'

    Start-Sleep -Seconds $FrozenWaitSec
    $advanced = @(Drain-Matching $second $stepLog)
    Assert-True ($advanced.Count -eq 0) "暂停期间 $FrozenWaitSec 秒内没有任何行情步"

    Send-Command $second 'creeper dev market resume'
    $next = Wait-ForLine $second $stepLog 60
    $nextStep = Get-Step $next $stepLog
    Assert-True ($nextStep -eq $savedStep + 1) "恢复后的第一个行情步是 $nextStep，紧接停服前的第 $savedStep 步"
    $stopLine2 = Stop-Server $second
    Assert-True ($stopLine2 -match '暂停=false') '第 2 次是在运行中（未暂停）停服的'
    $savedStep2 = Get-Step $stopLine2 $snapshotStep

    # ---- 第三次启动：运行中停服后，离线若干秒再启动，同样不补算 ----
    Start-Sleep -Seconds $OfflineSec
    $third = Start-Server '第 3 次' 3
    $restoreLine3 = Wait-ForLine $third '从存档恢复市场|新建市场' $StartupTimeoutSec
    Assert-True ($restoreLine3 -match '从存档恢复市场') '第 3 次启动从存档恢复市场'
    Assert-True ((Get-Step $restoreLine3 $snapshotStep) -eq $savedStep2) "恢复的步数与第 2 次停服时一致（$savedStep2）"
    Assert-True ($restoreLine3 -match '暂停=false') '未暂停的市场重启后仍在运行'
    Assert-Klines $third $savedStep2 '第 3 次启动'
    $next3 = Wait-ForLine $third $stepLog 60
    $nextStep3 = Get-Step $next3 $stepLog
    Assert-True ($nextStep3 -eq $savedStep2 + 1) "离线 $OfflineSec 秒后，第一个行情步是 $nextStep3，紧接停服前的第 $savedStep2 步（不补算）"
    Stop-Server $third | Out-Null

    Write-Log 'RESULT: PASS'
    exit 0
} catch {
    Write-Log "RESULT: FAIL - $($_.Exception.Message)"
    foreach ($s in @($first, $second, $third)) {
        if ($s -and -not $s.Process.HasExited) { $s.Process.Kill($true) }
    }
    exit 1
}
