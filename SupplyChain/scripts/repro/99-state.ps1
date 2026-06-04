# =============================================================
# 99-state.ps1 — 实时打印 Redis + DB 状态
# 用于复现前/复现后对比是否漂移
# =============================================================

$skuId = 1001
$whId = 1

# Redis 可用量
$redisVal = docker exec sc-redis redis-cli GET "sc:inventory:available:$whId`:$skuId"
if (-not $redisVal) { $redisVal = '<null>' }

# DB 库存
$dbRow = docker exec sc-mysql mysql -uroot -proot123 sc_supply_chain -N -e `
  "SELECT available_qty,locked_qty,total_qty FROM sc_inventory WHERE sku_id=$skuId AND warehouse_id=$whId" 2>$null

# 订单统计
$orderStats = docker exec sc-mysql mysql -uroot -proot123 sc_supply_chain -N -e `
  "SELECT status, COUNT(*) FROM sc_fulfillment_order WHERE sku_id=$skuId GROUP BY status" 2>$null

# 流水统计
$logStats = docker exec sc-mysql mysql -uroot -proot123 sc_supply_chain -N -e `
  "SELECT op_type, COUNT(*), SUM(delta_qty) FROM sc_inventory_log WHERE sku_id=$skuId GROUP BY op_type" 2>$null

Write-Host "================ STATE (sku=$skuId wh=$whId) ================" -ForegroundColor Yellow
Write-Host "Redis available  : $redisVal"
Write-Host "DB inventory     : $dbRow  (available  locked  total)"
Write-Host "Order count      :"
if ($orderStats) { $orderStats | ForEach-Object { "  $_" } } else { Write-Host "  (none)" }
Write-Host "Inventory log    :"
if ($logStats) { $logStats | ForEach-Object { "  $_" } } else { Write-Host "  (none)" }
Write-Host "==============================================================" -ForegroundColor Yellow

