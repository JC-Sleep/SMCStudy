#ifndef __UART_H
#define __UART_H

#include "main.h"

/*
 * USART1 ── 连接涂鸦 WBR3 WiFi 模块
 *
 *   PY32  PA2 (USART1_TX, AF1) ──────→ WBR3  RX
 *   PY32  PA3 (USART1_RX, AF1) ←────── WBR3  TX
 *
 * 注意：TX 接对方 RX，RX 接对方 TX（交叉）
 * 波特率：115200，8-N-1
 *
 * 如果你的板子 PA2/PA3 不是 USART1 的 AF1，请查阅
 * PY32F030K28 数据手册"Pin Alternate Functions"表后
 * 修改 py32f0xx_hal_msp.c 中的 gpio.Alternate 值。
 */
#define TUYA_UART_INSTANCE    USART1
#define TUYA_UART_BAUD        115200U

extern UART_HandleTypeDef huart1;

void UART1_Init(void);
void UART1_SendByte(uint8_t byte);
void UART1_SendBytes(const uint8_t *data, uint16_t len);

#endif /* __UART_H */
