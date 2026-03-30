#!/bin/bash
# KEDA部署脚本
# 用途：一键部署KEDA并配置Kafka自动扩缩容
# 使用：./deploy-keda.sh
# 作者：System
# 日期：2026-03-27
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}KEDA部署脚本${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
# 1. 检查kubectl
echo -e "${YELLOW}1. 检查kubectl...${NC}"
if ! command -v kubectl &> /dev/null; then
    echo "错误：kubectl未安装"
    exit 1
fi
echo "   ✓ kubectl已安装"
echo ""
# 2. 检查helm
echo -e "${YELLOW}2. 检查helm...${NC}"
if ! command -v helm &> /dev/null; then
    echo "错误：helm未安装"
    exit 1
fi
echo "   ✓ helm已安装"
echo ""
# 3. 添加KEDA Helm仓库
echo -e "${YELLOW}3. 添加KEDA Helm仓库...${NC}"
helm repo add kedacore https://kedacore.github.io/charts
helm repo update
echo ""
# 4. 安装KEDA
echo -e "${YELLOW}4. 安装KEDA...${NC}"
helm install keda kedacore/keda --namespace keda --create-namespace
if [ $? -ne 0 ]; then
    echo "提示：如果KEDA已安装，可以跳过此错误"
fi
echo ""
# 5. 等待KEDA就绪
echo -e "${YELLOW}5. 等待KEDA就绪...${NC}"
kubectl wait --for=condition=ready pod -l app=keda-operator -n keda --timeout=60s
echo ""
# 6. 应用Kafka Scaler配置
echo -e "${YELLOW}6. 应用Kafka Scaler配置...${NC}"
kubectl apply -f ../k8s/keda-kafka-scaler.yaml
echo ""
# 7. 查看状态
echo -e "${YELLOW}7. 查看KEDA状态：${NC}"
kubectl get scaledobject -n default
echo ""
kubectl get hpa -n default
echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}✓ KEDA部署完成！${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "监控命令："
echo "  查看ScaledObject：kubectl get scaledobject"
echo "  查看HPA：kubectl get hpa"
echo "  查看实时扩缩容：kubectl get hpa -w"
echo "  查看事件：kubectl get events | grep ScaledObject"
