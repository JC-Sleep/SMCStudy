# Kafka积压自动化解决方案 - 完整使用指南
## 📋 文件清单
### K8s配置文件（k8s/目录）
- ✅ keda-kafka-scaler.yaml - KEDA完整配置（推荐使用）
- ✅ keda-simple.yaml - KEDA简化配置（快速测试）
- ✅ hpa-kafka-lag.yaml - HPA配置（备选方案）
- ✅ README.md - 快速开始指南
### Shell脚本（scripts/目录）
- ✅ deploy-keda.sh - KEDA一键部署脚本
- ✅ scale-up.sh - 快速扩容脚本
- ✅ scale-down.sh - 快速缩容脚本
---
## 🚀 快速部署（3步）
### 步骤1：安装KEDA
``bash
cd CounponSys/scripts
chmod +x deploy-keda.sh
./deploy-keda.sh
``
**说明**：
- 自动添加Helm仓库
- 自动安装KEDA到keda命名空间
- 自动应用Kafka Scaler配置
- 耗时：约10分钟
### 步骤2：验证部署
``bash
# 查看ScaledObject
kubectl get scaledobject
# 应该看到：
# NAME                   SCALETARGETKIND      SCALETARGETNAME   MIN   MAX   TRIGGERS   READY
# coupon-kafka-scaler    apps/v1.Deployment   coupon-system     1     20    kafka      True
# 查看HPA
kubectl get hpa
# 应该看到：
# NAME                              REFERENCE                  TARGETS       MINPODS   MAXPODS   REPLICAS
# keda-hpa-coupon-kafka-scaler      Deployment/coupon-system   100/1000      1         20        2
``
### 步骤3：测试自动扩缩容
``bash
# 模拟积压（手动发送大量消息）
# 观察自动扩容
kubectl get hpa -w
# 应该看到：
# 积压增加 → TARGETS变大 → REPLICAS自动增加
``
---
## 📊 工作原理
### 自动扩容流程
``
积压检测（15秒）
    ↓
KEDA计算所需Pod数（5秒）
    ↓
K8s创建新Pod（30秒）
    ↓
新Pod开始消费（立即）
    ↓
积压快速消费（1-2分钟）
    ↓
5分钟后自动缩容
总耗时：约2分钟
人工介入：0次 ✅
``
### 扩缩容规则
| 积压量 | Pod数量 | 响应时间 |
|--------|--------|---------|
| 0-1000 | 1个 | - |
| 1000-5000 | 5个 | 45秒 |
| 5000-10000 | 10个 | 45秒 |
| 10000-20000 | 20个（max） | 45秒 |
| 20000+ | 20个（max） | 45秒 |
---
## 🔧 手动操作（临时方案）
### 快速扩容
``bash
cd scripts
chmod +x scale-up.sh
# 扩容到10个实例
./scale-up.sh 10
# 或者直接kubectl命令
kubectl scale deployment coupon-system --replicas=10
``
### 快速缩容
``bash
cd scripts
chmod +x scale-down.sh
# 缩容到2个实例
./scale-down.sh 2
# 或者直接kubectl命令
kubectl scale deployment coupon-system --replicas=2
``
---
## 📊 监控命令
### 查看实时积压
``bash
# 方式1：查看应用日志
kubectl logs -f deployment/coupon-system | grep "Kafka监控"
# 输出示例：
# Kafka监控: group=seckill-order-consumer, lag=15000, speed=2000/s, 预计恢复=7分钟
# 方式2：查看HPA
kubectl get hpa -w
# 输出示例：
# NAME                              TARGETS        MINPODS   MAXPODS   REPLICAS
# keda-hpa-coupon-kafka-scaler      15000/1000     1         20        15
``
### 查看扩缩容历史
``bash
# 查看事件
kubectl get events | grep -E "ScaledObject|HorizontalPodAutoscaler"
# 查看ScaledObject详情
kubectl describe scaledobject coupon-kafka-scaler
``
---
## ⚙️ 配置调优
### 调整扩容阈值
``yaml
# 编辑 keda-kafka-scaler.yaml
lagThreshold: '1000'  # 改为2000，更激进扩容
# 重新应用
kubectl apply -f k8s/keda-kafka-scaler.yaml
``
### 调整副本范围
``yaml
minReplicaCount: 1   # 改为2，保持最小2个实例
maxReplicaCount: 20  # 改为30，允许更大规模扩容
``
### 调整检查频率
``yaml
pollingInterval: 15  # 改为10，更快响应（但增加负载）
``
---
## 🎯 效果对比
### 人工处理 vs KEDA自动化
| 阶段 | 人工处理 | KEDA自动 |
|------|---------|---------|
| 检测积压 | 30秒 | 15秒 |
| 人工响应 | 5分钟 | 0秒 ✅ |
| 扩容执行 | 10秒 | 5秒 |
| Pod启动 | 30秒 | 30秒 |
| 开始消费 | 6分20秒 | 50秒 ✅ |
| **总计** | **11分钟** | **2分钟** |
| **提升** | - | **5.5倍** ⭐⭐⭐⭐⭐ |
---
## ❓ 常见问题
### Q: KEDA和HPA有什么区别？
A: KEDA基于HPA，但更专注于事件驱动扩缩容（如Kafka Lag）。KEDA配置更简单，无需Prometheus Adapter。
### Q: 能缩容到0吗？
A: 可以！设置minReplicaCount: 0即可。但生产环境不建议，建议保持至少1个实例。
### Q: 扩容太快会不会浪费资源？
A: 不会。KEDA有冷却期（5分钟），积压消失后会自动缩容。
### Q: 夜间积压怎么办？
A: KEDA全自动，夜间也能处理，无需人工值守 ✅
---
## 📖 详细文档
- Kafka积压问题深度分析与自动化解决方案.md - 完整技术文档
- 功能与代码位置索引.md - 代码位置索引
- plan-couponSystem.prompt.md - 项目计划文档
---
## ✅ 验收标准
- [ ] KEDA成功部署
- [ ] ScaledObject状态为Ready
- [ ] HPA能看到kafka lag指标
- [ ] 模拟积压能自动扩容
- [ ] 积压消失能自动缩容
---
**🎉 部署完成后，Kafka积压问题将完全自动化处理！**
@since 2026-03-27
