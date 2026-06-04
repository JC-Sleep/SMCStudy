# =============================================================
# B3-cancel-double.ps1 — 复现"并发取消双倍释放库存"
#
# 步骤：
#   1. 下一笔订单（库存预扣 5）
#   2. 50 个并发线程同时调用 cancel
#   3. 修复前：Redis 会 INCRBY 多次（凭空多 N*5），DB updateById 也覆盖多次
#   4. 修复后：仅 1 个线程 cancelIfCancelable 返回 affected=1，其余 affected=0 跳过
# =============================================================

$ErrorActionPreference = 'Continue'
$base = 'http://localhost:8091'
$threads = 50
$qty = 5

Write-Host "`n[B3] === 复现：并发取消双倍释放库存 ===" -ForegroundColor Magenta

& "$PSScriptRoot\00-setup.ps1" | Out-Null

# 1. 下单
$body = @{ storeId=1; skuId=1001; qty=$qty; payAmount=49.5; address='测试'; warehouseId=1 } | ConvertTo-Json
$resp = Invoke-RestMethod -Uri "$base/api/fulfillment/order/create" -Method Post -Body $body -ContentType 'application/json'
$orderNo = $resp.data
Write-Host "[B3] 已下单 orderNo=$orderNo qty=$qty"

# 等 Kafka 消费完毕，让 DB locked_qty 已 = 5
Start-Sleep -Seconds 2
& "$PSScriptRoot\99-state.ps1"

Write-Host "`n[B3] 启动 $threads 个并发线程同时 cancel..." -ForegroundColor Cyan

# 用 RunspacePool 实现毫秒级真并发
$pool = [runspacefactory]::CreateRunspacePool(1, $threads)
$pool.Open()
$jobs = @()
foreach ($i in 1..$threads) {
    $ps = [powershell]::Create()
    $ps.RunspacePool = $pool
    [void]$ps.AddScript({
        param($url)
        try {
            $r = Invoke-WebRequest -Uri $url -Method Post -UseBasicParsing -TimeoutSec 10
            return "OK $($r.StatusCode)"
        } catch {
            return "ERR $($_.Exception.Message)"
        }
    }).AddArgument("$base/api/fulfillment/order/$orderNo/cancel")
    $jobs += @{ ps=$ps; handle=$ps.BeginInvoke() }
}

# 等所有完成
$results = @()
foreach ($j in $jobs) {
    $results += $j.ps.EndInvoke($j.handle)
    $j.ps.Dispose()
}
$pool.Close()

$ok = ($results | Where-Object { $_ -like "OK*" }).Count
$err = ($results | Where-Object { $_ -like "ERR*" }).Count
Write-Host "[B3] 完成。OK=$ok  ERR=$err" -ForegroundColor Cyan

# 等异步 Kafka restore 消费完
Start-Sleep -Seconds 3

Write-Host "`n[B3] === 最终状态 ===" -ForegroundColor Magenta
& "$PSScriptRoot\99-state.ps1"

Write-Host "`n判断：" -ForegroundColor Yellow
Write-Host "  修复前：Redis available > 100  (凭空多货)"
Write-Host "  修复后：Redis available = 100，且 OK 计数应该 = 1，其余 50 个全 ERR/no-op"

