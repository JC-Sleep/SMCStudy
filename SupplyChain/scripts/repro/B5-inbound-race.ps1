# =============================================================
# B5-inbound-race.ps1 — 复现"并发入库 Redis SET 覆盖导致超卖"
#
# 场景设计：
#   1. 初始库存 100，先 warmup 让 Redis=100
#   2. 启动一个慢"下单"线程，扣减 Redis（DECRBY 10）
#   3. 同时启动 5 个并发入库（各 +10），它们会做 "DB+10 → SELECT → SET Redis"
#   4. 最后比较：DB.available_qty vs Redis available
#      理论应：DB = 100 + 5*10 - 10 = 140, Redis 应 = 140
#      Bug 现象：Redis 被某个 SET 覆盖回 150（凭空多 10）
#
# 注：因 Redis SET 覆盖只在极特定 timing 触发，可能需要多跑几次才能复现。
# =============================================================

$ErrorActionPreference = 'Continue'
$base = 'http://localhost:8091'

Write-Host "`n[B5] === 复现：并发入库 Redis 覆盖导致漂移 ===" -ForegroundColor Magenta
& "$PSScriptRoot\00-setup.ps1" | Out-Null

# 先 warmup 让 Redis=100（必须，否则下单走 cache miss 路径）
Invoke-RestMethod -Uri "$base/api/inventory/1/1001/warmup" -Method Post | Out-Null
Start-Sleep -Seconds 1
Write-Host "[B5] 初始（warmup后）："
& "$PSScriptRoot\99-state.ps1"

# 用 RunspacePool 同时发：5 个入库 + 1 个下单
$pool = [runspacefactory]::CreateRunspacePool(1, 6)
$pool.Open()
$jobs = @()

# 5 个并发入库 +10
foreach ($i in 1..5) {
    $ps = [powershell]::Create()
    $ps.RunspacePool = $pool
    $body = @{ skuId=1001; warehouseId=1; qty=10 } | ConvertTo-Json
    [void]$ps.AddScript({
        param($url, $body, $idx)
        try {
            Invoke-RestMethod -Uri $url -Method Post -Body $body -ContentType 'application/json' | Out-Null
            return "INBOUND#$idx OK"
        } catch { return "INBOUND#$idx ERR $($_.Exception.Message)" }
    }).AddArgument("$base/api/inventory/inbound").AddArgument($body).AddArgument($i)
    $jobs += @{ ps=$ps; handle=$ps.BeginInvoke() }
}

# 1 个并发下单 -10
$ps = [powershell]::Create()
$ps.RunspacePool = $pool
$orderBody = @{ storeId=1; skuId=1001; qty=10; payAmount=99; address='测试'; warehouseId=1 } | ConvertTo-Json
[void]$ps.AddScript({
    param($url, $body)
    try {
        $r = Invoke-RestMethod -Uri $url -Method Post -Body $body -ContentType 'application/json'
        return "ORDER OK $($r.data)"
    } catch { return "ORDER ERR $($_.Exception.Message)" }
}).AddArgument("$base/api/fulfillment/order/create").AddArgument($orderBody)
$jobs += @{ ps=$ps; handle=$ps.BeginInvoke() }

$results = @()
foreach ($j in $jobs) {
    $results += $j.ps.EndInvoke($j.handle)
    $j.ps.Dispose()
}
$pool.Close()
$results | ForEach-Object { Write-Host "  $_" }

# 等 Kafka deduct 消费完
Start-Sleep -Seconds 4

Write-Host "`n[B5] === 最终状态 ===" -ForegroundColor Magenta
& "$PSScriptRoot\99-state.ps1"

Write-Host "`n判断：" -ForegroundColor Yellow
Write-Host "  期望：DB.available = 100 + 50 - 10 = 140，Redis = 140"
Write-Host "  修复前 BUG：Redis 可能 > 140（被 SET 覆盖），或 DB 落库异步未完成时 Redis 已偏离"
Write-Host "  修复后：Redis 用 INCRBY 累加，并发安全"
Write-Host "  ⚠️  此 Bug 时序窗口很窄，可能需要循环跑 10 次才能撞到"


