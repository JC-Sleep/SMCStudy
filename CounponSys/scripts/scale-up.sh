#!/bin/bash
# Kafka积压快速扩容脚本
# 用途：当Kafka积压严重时，快速扩容消费者实例
# 使用：./scale-up.sh [目标实例数，默认10]
# 作者：System
# 日期：2026-03-27
# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color
# 目标实例数（默认10）
TARGET_REPLICAS=${1:-10}
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Kafka积压快速扩容脚本${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
# 1. 检查当前副本数
echo -e "${YELLOW}1. 检查当前副本数...${NC}"
CURRENT=$(kubectl get deployment coupon-system -o jsonpath='{.spec.replicas}')
if [ -z "$CURRENT" ]; then
    echo -e "${RED}错误：无法获取当前副本数${NC}"
    exit 1
fi
echo -e "   当前副本数: ${GREEN}$CURRENT${NC}"
echo ""
# 2. 检查Kafka积压
echo -e "${YELLOW}2. 检查Kafka积压...${NC}"
# TODO: 调用API获取积压数量
# LAG=$(curl -s http://localhost:8090/actuator/metrics/kafka.consumer.lag | jq '.measurements[0].value')
echo -e "   提示：建议先查看Grafana确认积压情况${NC}"
echo ""
# 3. 确认扩容
echo -e "${YELLOW}3. 准备扩容...${NC}"
echo -e "   目标副本数: ${GREEN}$TARGET_REPLICAS${NC}"
echo ""
read -p "确认扩容？(y/n): " -n 1 -r
echo ""
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo -e "${RED}取消扩容${NC}"
    exit 0
fi
# 4. 执行扩容
echo -e "${YELLOW}4. 执行扩容...${NC}"
kubectl scale deployment coupon-system --replicas=$TARGET_REPLICAS
if [ $? -ne 0 ]; then
    echo -e "${RED}扩容失败！${NC}"
    exit 1
fi
echo -e "${GREEN}✓ 扩容命令已发送${NC}"
echo ""
# 5. 等待Pod就绪
echo -e "${YELLOW}5. 等待Pod启动...${NC}"
kubectl wait --for=condition=ready pod -l app=coupon-system --timeout=90s
if [ $? -ne 0 ]; then
    echo -e "${RED}警告：部分Pod启动超时${NC}"
fi
echo ""
# 6. 查看当前状态
echo -e "${YELLOW}6. 当前状态：${NC}"
kubectl get pods -l app=coupon-system
echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}✓ 扩容完成！${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "监控命令："
echo -e "  查看实时积压：kubectl logs -f deployment/coupon-system | grep 'Kafka监控'"
echo -e "  查看Pod状态：kubectl get pods -l app=coupon-system -w"
echo -e "  查看HPA：kubectl get hpa"
echo ""
echo -e "缩容命令："
echo -e "  ./scale-down.sh"
