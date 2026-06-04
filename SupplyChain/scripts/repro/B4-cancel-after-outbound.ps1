# =============================================================
# B4-cancel-after-outbound.ps1 — 复现"已出库订单还能取消"
# =============================================================

$ErrorActionPreference = 'Continue'
$base = 'http://localhost:8091'

Write-Host "`n[B4] === 复现：OUTBOUND 后取消导致库存错乱 ===" -ForegroundColor Magenta
& "$PSScriptRoot\00-setup.ps1" | Out-Null

# 1. 下单
$body = @{ storeId=1; skuId=1001; qty=5; payAmount=49.5; address='测试'; warehouseId=1 } | ConvertTo-Json
$resp = Invoke-RestMethod -Uri "$base/api/fulfillment/order/create" -Method Post -Body $body -ContentType 'application/json'
$orderNo = $resp.data
Write-Host "[B4] 已下单 orderNo=$orderNo"
Start-Sleep -Seconds 2

# 2. 支付
Invoke-RestMethod -Uri "$base/api/fulfillment/order/$orderNo/paid" -Method Post | Out-Null
Write-Host "[B4] 已支付 → PAID"

# 3. 出库
Invoke-RestMethod -Uri "$base/api/fulfillment/order/$orderNo/outbound" -Method Post | Out-Null
Write-Host "[B4] 已出库 → OUTBOUND"
Start-Sleep -Seconds 2
& "$PSScriptRoot\99-state.ps1"

# 4. 尝试取消（修复前：返回 200 凭空释放库存；修复后：返回 400 illegalStatus）
Write-Host "`n[B4] 尝试在 OUTBOUND 状态下取消..." -ForegroundColor Cyan
try {
    $r = Invoke-WebRequest -Uri "$base/api/fulfillment/order/$orderNo/cancel" -Method Post -UseBasicParsing
    Write-Host "  HTTP $($r.StatusCode) - 取消接口返回成功 ❌（修复前的 BUG！）" -ForegroundColor Red
} catch {
    $code = $_.Exception.Response.StatusCode.value__
    Write-Host "  HTTP $code - 取消被拒绝 ✅（修复后的正确行为）" -ForegroundColor Green
}

Start-Sleep -Seconds 2
Write-Host "`n[B4] === 最终状态 ===" -ForegroundColor Magenta
& "$PSScriptRoot\99-state.ps1"

Write-Host "`n判断：" -ForegroundColor Yellow
Write-Host "  修复前：Redis=100（凭空多5，因为货已发但又INCRBY回来），DB total_qty=95"
Write-Host "  修复后：HTTP 400 拒绝，Redis 与 DB 保持 outbound 后状态"

