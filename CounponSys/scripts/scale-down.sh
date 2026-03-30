#!/bin/bash
# Kafka积压快速缩容脚本
# 用途：当Kafka积压消费完成后，缩容消费者实例
# 使用：./scale-down.sh [目标实例数，默认2]
# 作者：System
# 日期：2026-03-27
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'
TARGET_REPLICAS=${1:-2}
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Kafka积压快速缩容脚本${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
# 1. 检查当前副本数
echo -e "${YELLOW}1. 检查当前副本数...${NC}"
CURRENT=$(kubectl get deployment coupon-system -o jsonpath='{.spec.replicas}')
echo -e "   当前副本数: ${GREEN}$CURRENT${NC}"
echo ""
# 2. 检查Kafka积压
echo -e "${YELLOW}2. 检查Kafka积压...${NC}"
echo -e "   ${RED}警告：请确认Kafka积压已消费完成！${NC}"
echo -e "   查看命令：kubectl logs deployment/coupon-system | grep 'Kafka监控'"
echo ""
# 3. 确认缩容
echo -e "${YELLOW}3. 准备缩容...${NC}"
echo -e "   目标副本数: ${GREEN}$TARGET_REPLICAS${NC}"
echo ""
read -p "确认缩容？(y/n): " -n 1 -r
echo ""
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo -e "${RED}取消缩容${NC}"
    exit 0
fi
# 4. 执行缩容
echo -e "${YELLOW}4. 执行缩容...${NC}"
kubectl scale deployment coupon-system --replicas=$TARGET_REPLICAS
if [ $? -ne 0 ]; then
    echo -e "${RED}缩容失败！${NC}"
    exit 1
fi
echo -e "${GREEN}✓ 缩容命令已发送${NC}"
echo ""
# 5. 查看状态
echo -e "${YELLOW}5. 当前状态：${NC}"
kubectl get pods -l app=coupon-system
echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}✓ 缩容完成！${NC}"
echo -e "${GREEN}========================================${NC}"
