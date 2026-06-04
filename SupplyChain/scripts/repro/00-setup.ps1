# =============================================================
# 00-setup.ps1 — 复现前置数据准备
# 创建 SPU=1 / SKU=1001 / 仓库=1 / 初始库存=100
# 直接通过 docker exec mysql 写入，绕过 API 速度更快
# =============================================================

$ErrorActionPreference = 'Stop'
$mysql = 'docker exec sc-mysql mysql -uroot -proot123 sc_supply_chain -N -e'

Write-Host "[setup] 写入 SPU/SKU/仓库/库存基础数据..." -ForegroundColor Cyan

$sql = @"
INSERT INTO sc_warehouse (id, warehouse_name, address, type) VALUES (1, '主仓', '深圳', 'MAIN')
  ON DUPLICATE KEY UPDATE warehouse_name=VALUES(warehouse_name);

INSERT INTO sc_spu (id, spu_name, fresh_type, status) VALUES (1, '复现测试-苹果', 'NORMAL', 'ON_SALE')
  ON DUPLICATE KEY UPDATE status='ON_SALE';

INSERT INTO sc_sku (id, spu_id, sku_name, price, status) VALUES (1001, 1, '500g装', 9.90, 'ENABLED')
  ON DUPLICATE KEY UPDATE status='ENABLED';

INSERT INTO sc_store (id, store_name, address, status) VALUES (1, '测试小店', '深圳', 'OPEN')
  ON DUPLICATE KEY UPDATE status='OPEN';

-- 库存 reset 到 100
INSERT INTO sc_inventory (id, sku_id, warehouse_id, available_qty, locked_qty, total_qty)
  VALUES (10001, 1001, 1, 100, 0, 100)
  ON DUPLICATE KEY UPDATE available_qty=100, locked_qty=0, total_qty=100;

-- 清理之前可能残留的订单/流水
DELETE FROM sc_fulfillment_order WHERE store_id = 1;
DELETE FROM sc_fulfillment_record WHERE order_id NOT IN (SELECT id FROM sc_fulfillment_order);
DELETE FROM sc_inventory_log WHERE sku_id = 1001;
DELETE FROM sc_inventory_batch WHERE sku_id = 1001;

INSERT INTO sc_inventory_batch (id, batch_no, sku_id, warehouse_id, inbound_qty, remain_qty, inbound_time, status)
  VALUES (100001, 'BATCH-INIT-1001', 1001, 1, 100, 100, NOW(), 'NORMAL');
"@

# 把 SQL 通过 stdin 喂给 mysql
$tmp = [System.IO.Path]::GetTempFileName()
Set-Content -Path $tmp -Value $sql -Encoding UTF8
Get-Content $tmp -Raw | docker exec -i sc-mysql mysql -uroot -proot123 sc_supply_chain | Out-Null
Remove-Item $tmp

Write-Host "[setup] 清理 Redis 库存 key..." -ForegroundColor Cyan
docker exec sc-redis redis-cli DEL "sc:inventory:available:1:1001" | Out-Null
docker exec sc-redis redis-cli DEL "sc:inventory:batch:1:1001" | Out-Null

Write-Host "[setup] ✅ 完成。状态：" -ForegroundColor Green
& "$PSScriptRoot\99-state.ps1"

