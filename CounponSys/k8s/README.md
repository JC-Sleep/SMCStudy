# Kafka积压自动化解决方案 - 部署指南
## 快速开始
### 方案1：KEDA自动扩缩容（推荐）⭐⭐⭐⭐⭐
``bash
# 1. 安装KEDA（10分钟）
cd scripts
chmod +x deploy-keda.sh
./deploy-keda.sh
# 2. 验证部署
kubectl get scaledobject
kubectl get hpa
# 3. 完成！积压会自动扩容
``
### 方案2：手动快速扩缩容（临时方案）
``bash
# 扩容到10个实例
cd scripts
chmod +x scale-up.sh
./scale-up.sh 10
# 缩容到2个实例
./scale-down.sh 2
``
## 文件说明
- keda-kafka-scaler.yaml - KEDA完整配置（推荐）
- keda-simple.yaml - KEDA简化配置（快速测试）
- hpa-kafka-lag.yaml - HPA配置（备选方案）
- scale-up.sh - 快速扩容脚本
- scale-down.sh - 快速缩容脚本
- deploy-keda.sh - KEDA一键部署脚本
## 效果
- 积压检测：15秒
- 自动扩容：30秒内开始
- 恢复正常：1-2分钟
- 自动缩容：5分钟后
- 人工介入：0次 ✅
@since 2026-03-27
