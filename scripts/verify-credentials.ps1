#Requires -Version 7.0
<#
.SYNOPSIS
    登录凭据对照实验：用同一份 Cookie 复现 Matter 的 /api/config 请求。

.DESCRIPTION
    回答三个问题，都不需要真机挂机：

    1. 本地凭据集合够不够？—— 把浏览器 Cookie 逐步裁剪，看哪一项删掉后
       `data.login` 从 true 变 false。Matter 的 cookieHeader 最少形态是
       「SUB + SUBP」（见 UserSession.cookieHeader），若 B 组就失败，
       说明缺口在凭据集合，不在时效。
    2. 服务端会不会轮换 SUB？—— -Watch 模式连打同一接口，把响应头里的
       Set-Cookie: SUB 打成指纹，同一指纹说明没有轮换。
    3. login=false 是不是真失效？—— A 组（浏览器全量）与 B 组在同一时刻、
       同一出口 IP 下的结果对比。A=true 且 B=false 就是请求姿势/缺项问题。

.PARAMETER Cookie
    浏览器 DevTools 里复制的完整 Cookie 串。默认读环境变量 WEIBO_COOKIE。

.PARAMETER CookieFile
    从文件读 Cookie，避免凭据进入命令历史。
    支持两种格式：单行 `k=v; k=v`，或直接粘贴 DevTools → Application → Cookies
    表格的整段内容（Tab 分隔，自动取 Name / Value 两列）。

.PARAMETER Watch
    连打次数。>0 时只跑「SUB 是否轮换」的观察，不做裁剪矩阵。

.PARAMETER Url
    观察轮换时请求的地址。默认 /api/config；想看服务端是否回 Set-Cookie，
    用 https://weibo.com/ 更直观（首页每次都会刷新会话 Cookie）。

.PARAMETER IntervalSeconds
    -Watch 模式下的间隔秒数。

.EXAMPLE
    $env:WEIBO_COOKIE = 'SUB=xxx; SUBP=yyy; _T_WM=zzz'
    ./scripts/verify-credentials.ps1

.EXAMPLE
    # 推荐：把 DevTools 里复制的 Cookies 表格存成文件再传，凭据不进命令历史
    ./scripts/verify-credentials.ps1 -CookieFile .\cookies.txt

.EXAMPLE
    ./scripts/verify-credentials.ps1 -Watch 10 -IntervalSeconds 30

.NOTES
    实验前把手机和电脑接到同一个 WiFi：出口 IP 变化本身就会触发风控，
    会让「login=false」的解读失效。
    输出里只出现 Cookie 的键名与 SUB 指纹，不出现凭据值，可以直接贴到 issue。
#>
param(
    [string]$Cookie = $env:WEIBO_COOKIE,
    [string]$CookieFile = '',
    [int]$Watch = 0,
    [int]$IntervalSeconds = 30,
    [string]$Url = 'https://m.weibo.cn/api/config'
)

$ErrorActionPreference = 'Stop'

$UA = 'Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36'
$ConfigUrl = 'https://m.weibo.cn/api/config'

function Read-CookieFile {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) { throw "找不到文件：$Path" }
    $raw = Get-Content -LiteralPath $Path -Raw
    if ([string]::IsNullOrWhiteSpace($raw)) { throw "文件为空：$Path" }

    # DevTools 的 Cookies 表格是 Tab 分隔：Name \t Value \t Domain \t Path ...
    if ($raw.Contains("`t")) {
        $pairs = foreach ($line in ($raw -split "`r?`n")) {
            if ([string]::IsNullOrWhiteSpace($line)) { continue }
            $cols = $line -split "`t"
            if ($cols.Count -lt 2) { continue }
            $name = $cols[0].Trim()
            $value = $cols[1].Trim()
            if ($name -and $value -and $name -ne 'Name') { "$name=$value" }
        }
        if ($pairs) { return ($pairs -join '; ') }
        throw "文件里没解析出任何 Cookie 行：$Path"
    }

    return $raw.Trim()
}

if (-not [string]::IsNullOrWhiteSpace($CookieFile)) {
    $Cookie = Read-CookieFile -Path $CookieFile
}

if ([string]::IsNullOrWhiteSpace($Cookie)) {
    throw '缺少 Cookie：用 -CookieFile 指定文件，设置 $env:WEIBO_COOKIE，或用 -Cookie 传入。'
}

function Split-Cookie {
    param([string]$Raw)
    $map = [ordered]@{}
    foreach ($part in ($Raw -split ';\s*')) {
        $trimmed = $part.Trim()
        if ($trimmed -eq '') { continue }
        $idx = $trimmed.IndexOf('=')
        if ($idx -le 0) { continue }
        $name = $trimmed.Substring(0, $idx).Trim()
        if (-not $map.Contains($name)) { $map[$name] = $trimmed }
    }
    return $map
}

function Get-Subset {
    param([System.Collections.Specialized.OrderedDictionary]$Map, [string[]]$Keys)
    $picked = foreach ($key in $Keys) { if ($Map.Contains($key)) { $Map[$key] } }
    return ($picked -join '; ')
}

function Get-Fingerprint {
    param([string]$Value)
    if ([string]::IsNullOrEmpty($Value)) { return 'empty' }
    $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
    $hash = [Security.Cryptography.SHA256]::HashData($bytes)
    $hex = ($hash[0..3] | ForEach-Object { $_.ToString('x2') }) -join ''
    return "len$($Value.Length):$hex"
}

function Get-CookieField {
    param([string]$CookieHeader, [string]$Name)
    foreach ($part in ($CookieHeader -split ';\s*')) {
        $idx = $part.IndexOf('=')
        if ($idx -le 0) { continue }
        if ($part.Substring(0, $idx).Trim() -ieq $Name) {
            return $part.Substring($idx + 1).Trim()
        }
    }
    return ''
}

function Invoke-ConfigProbe {
    param([string]$Name, [string]$CookieHeader)

    $headers = @{
        'Cookie'           = $CookieHeader
        'User-Agent'       = $UA
        'Referer'          = 'https://m.weibo.cn/'
        'X-Requested-With' = 'XMLHttpRequest'
        'Accept'           = 'application/json, text/plain, */*'
    }

    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    try {
        $response = Invoke-WebRequest -Uri $ConfigUrl -Headers $headers -Method Get -TimeoutSec 20
        $stopwatch.Stop()
        $json = $response.Content | ConvertFrom-Json

        $setCookie = @($response.Headers['Set-Cookie'])
        $rotated = @($setCookie | Where-Object { $_ -match '(?i)^SUB=' })

        [pscustomobject]@{
            Case          = $Name
            Login         = $json.data.login
            Uid           = $json.data.uid
            Ms            = $stopwatch.ElapsedMilliseconds
            SubRotated    = if ($rotated.Count -gt 0) { 'yes' } else { 'no' }
            SentSub       = Get-Fingerprint (Get-CookieField $CookieHeader 'SUB')
        }
    }
    catch {
        $stopwatch.Stop()
        $status = $_.Exception.Response.StatusCode.value__
        [pscustomobject]@{
            Case          = $Name
            Login         = "ERR $status"
            Uid           = ''
            Ms            = $stopwatch.ElapsedMilliseconds
            SubRotated    = 'n/a'
            SentSub       = Get-Fingerprint (Get-CookieField $CookieHeader 'SUB')
        }
    }
}

$map = Split-Cookie $Cookie
Write-Host ""
Write-Host "浏览器 Cookie 键名：$(($map.Keys) -join ', ')" -ForegroundColor Cyan
Write-Host "SUB 指纹：$(Get-Fingerprint ($map['SUB']))" -ForegroundColor Cyan
Write-Host ""

function Invoke-SetCookieProbe {
    param([string]$TargetUrl, [string]$CookieHeader)

    $headers = @{
        'Cookie'           = $CookieHeader
        'User-Agent'       = $UA
        'Referer'          = 'https://m.weibo.cn/'
        'X-Requested-With' = 'XMLHttpRequest'
        'Accept'           = 'text/html,application/json,*/*'
    }
    try {
        $response = Invoke-WebRequest -Uri $TargetUrl -Headers $headers -Method Get -TimeoutSec 20 -SkipHttpErrorCheck
        $subHeaders = @(@($response.Headers['Set-Cookie']) | Where-Object { $_ -match '(?i)^SUB=' })
        if ($subHeaders.Count -eq 0) {
            return [pscustomobject]@{ Status = $response.StatusCode; Sub = 'none'; Expires = '-' }
        }
        $first = $subHeaders[0]
        $value = ($first -split ';')[0].Split('=', 2)[1]
        $expires = if ($first -match '(?i)expires=([^;]+)') { $Matches[1].Trim() }
                   elseif ($first -match '(?i)max-age=(\d+)') { "max-age=$($Matches[1])" }
                   else { 'session' }
        return [pscustomobject]@{ Status = $response.StatusCode; Sub = (Get-Fingerprint $value); Expires = $expires }
    }
    catch {
        return [pscustomobject]@{ Status = '-'; Sub = 'ERR'; Expires = $_.Exception.Message }
    }
}

if ($Watch -gt 0) {
    Write-Host "观察 SUB 是否轮换：连打 $Watch 次，间隔 ${IntervalSeconds}s，目标 $Url" -ForegroundColor Yellow
    Write-Host "（SUB 一栏是值指纹：指纹不变 = 只续期不换值；none = 服务端没回 SUB）" -ForegroundColor DarkGray
    for ($i = 1; $i -le $Watch; $i++) {
        $probe = Invoke-SetCookieProbe -TargetUrl $Url -CookieHeader $Cookie
        Write-Host ("[{0:HH:mm:ss}] status={1} SUB={2} expires={3}" -f (Get-Date), $probe.Status, $probe.Sub, $probe.Expires)
        if ($i -lt $Watch) { Start-Sleep -Seconds $IntervalSeconds }
    }
    Write-Host ""
    Write-Host "SUB=none 一直出现 → 服务端不回 SUB，轮换落盘没有收益，掉线来自别的通道。" -ForegroundColor Yellow
    Write-Host "SUB 指纹恒定、expires 往后推 → 服务端在续期但不换值；只要客户端保持活跃就不会掉。" -ForegroundColor Yellow
    Write-Host "SUB 指纹变化 → 确认存在轮换，必须落盘（本次已实现）。" -ForegroundColor Yellow
    return
}

Write-Host "裁剪实验：每一步删掉一部分 Cookie，看 login 何时变 false" -ForegroundColor Yellow

$cases = @(
    @{ Name = 'A 全量（浏览器原样）';                          Keys = @($map.Keys) },
    @{ Name = 'B SUB+SUBP（Matter 最小形态）';                  Keys = @('SUB', 'SUBP') },
    @{ Name = 'C 只留 SUB';                                     Keys = @('SUB') },
    @{ Name = 'D SUB+SUBP+_T_WM';                               Keys = @('SUB', 'SUBP', '_T_WM') },
    @{ Name = 'E SUB+SUBP+MLOGIN';                              Keys = @('SUB', 'SUBP', 'MLOGIN') },
    @{ Name = 'F SUB+SUBP+_T_WM+MLOGIN+SSOLoginState';          Keys = @('SUB', 'SUBP', '_T_WM', 'MLOGIN', 'SSOLoginState') }
)

$results = @()
foreach ($case in $cases) {
    $header = Get-Subset -Map $map -Keys $case.Keys
    if ([string]::IsNullOrWhiteSpace($header)) {
        Write-Host "跳过 $($case.Name)：这份 Cookie 里没有它需要的键" -ForegroundColor DarkGray
        continue
    }
    $results += Invoke-ConfigProbe -Name $case.Name -CookieHeader $header
    # 相邻两次请求留出间隔，避免自己触发频控
    Start-Sleep -Seconds 2
}
$results | Format-Table Case, Login, Uid, Ms, SubRotated -AutoSize

Write-Host ""
Write-Host "判定表" -ForegroundColor Cyan
Write-Host "  B=true            → 凭据集合够用，掉线来自时效/误判，重点看实验 2、3"
Write-Host "  B=false, A=true   → Matter 存的凭据集合不完整（缺配套 Cookie），先补齐"
Write-Host "  A=false           → 这份 Cookie 本身已经失效，换一份新登录的再测"
Write-Host "  ERR 432/403       → 风控/频控介入，等 10 分钟再测，或换网络"
Write-Host ""
