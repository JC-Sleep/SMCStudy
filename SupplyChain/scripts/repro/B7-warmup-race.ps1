# =============================================================
# B7-warmup-race.ps1 — 复现"冷启动缓存预热竞态超卖"
#
# 场景：
#   - 库存只有 10 件
#   - Redis key 不存在（冷启动）
#   - 100 个并发同时下单 1 件
#   - 修复前：每个线程都看到 -2 → 都触发 warmup → 都 SET=10 → 互相覆盖扣减 → 99 单成功 ❌
#   - 修复后：仅 1 个 SETNX 生效 → 仅前 10 个 Lua 扣减成功 → 其余 90 单 stockInsufficient ✅
# =============================================================

$ErrorActionPreference = 'Continue'
$base = 'http://localhost:8091'
$threads = 100
$initialStock = 10

Write-Host "`n[B7] === 复现：冷启动 100 并发抢 $initialStock 件库存 ===" -ForegroundColor Magenta

# 重置库存到 10，并删除 Redis key（模拟冷启动）
docker exec sc-mysql mysql -uroot -proot123 sc_supply_chain -e `
  "DELETE FROM sc_fulfillment_order WHERE sku_id=1001; UPDATE sc_inventory SET available_qty=$initialStock, locked_qty=0, total_qty=$initialStock WHERE sku_id=1001 AND warehouse_id=1; DELETE FROM sc_inventory_log WHERE sku_id=1001;" | Out-Null
docker exec sc-redis redis-cli DEL "sc:inventory:available:1:1001" | Out-Null

Write-Host "[B7] 初始：DB=$initialStock，Redis=null（冷启动）"
& "$PSScriptRoot\99-state.ps1"

Write-Host "`n[B7] 启动 $threads 个并发抢购..." -ForegroundColor Cyan
$pool = [runspacefactory]::CreateRunspacePool(1, $threads)
$pool.Open()
$jobs = @()

$body = @{ storeId=1; skuId=1001; qty=1; payAmount=9.9; address='测试'; warehouseId=1 } | ConvertTo-Json

foreach ($i in 1..$threads) {
    $ps = [powershell]::Create()
    $ps.RunspacePool = $pool
    [void]$ps.AddScript({
        param($url, $body)
        try {
            $r = Invoke-RestMethod -Uri $url -Method Post -Body $body -ContentType 'application/json' -TimeoutSec 30
            if ($r.code -eq 0 -or $r.success) { return "OK" } else { return "FAIL_BIZ_$($r.code)" }
        } catch {
            $code = if ($_.Exception.Response) { $_.Exception.Response.StatusCode.value__ } else { 0 }
            return "FAIL_HTTP_$code"
        }
    }).AddArgument("$base/api/fulfillment/order/create").AddArgument($body)
    $jobs += @{ ps=$ps; handle=$ps.BeginInvoke() }
}

$results = @()
foreach ($j in $jobs) {
    $results += $j.ps.EndInvoke($j.handle)
    $j.ps.Dispose()
}
$pool.Close()

$ok = ($results | Where-Object { $_ -eq 'OK' }).Count
$fail = $threads - $ok
Write-Host "[B7] 完成：成功下单=$ok 失败=$fail" -ForegroundColor Cyan

Start-Sleep -Seconds 3
Write-Host "`n[B7] === 最终状态 ===" -ForegroundColor Magenta
& "$PSScriptRoot\99-state.ps1"

Write-Host "`n=== 复现判定 ===" -ForegroundColor Yellow
if ($ok -gt $initialStock) {
    Write-Host "  ❌❌ 超卖！预期最多 $initialStock 单成功，实际 $ok 单成功" -ForegroundColor Red
    Write-Host "      → B7 Bug 复现成功！修复前的代码确实会超卖" -ForegroundColor Red
} elseif ($ok -eq $initialStock) {
    Write-Host "  ✅ 仅 $initialStock 单成功，无超卖（说明 B7 已修复 / 或 timing 没撞上）" -ForegroundColor Green
} else {
    Write-Host "  ⚠️  仅 $ok 单成功（小于 $initialStock，可能因 cache miss 重试逻辑 timing 影响）" -ForegroundColor Yellow
}

