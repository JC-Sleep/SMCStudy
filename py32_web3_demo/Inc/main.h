#ifndef __MAIN_H
#define __MAIN_H

#include "py32f0xx_hal.h"
#include <stdint.h>
#include <string.h>

/* ─────────────────────────────────────────────────────────────────────────────
 *  调试开关
 *  DEBUG_UART_PRINTF = 1：main.c 会通过 USART1 输出 "T=xx H=xx\r\n" 文本，
 *                        方便 XCOM 观察。此时 PA2/PA3 接 USB-TTL，**不要接 WBR3**。
 *  DEBUG_UART_PRINTF = 0：纯涂鸦协议帧上报，PA2/PA3 接 WBR3，用 App 看数据。
 * ────────────────────────────────────────────────────────────────────────── */
#define DEBUG_UART_PRINTF        1

/* ─────────────────────────────────────────────────────────────────────────────
 *  板载用户 LED（心跳指示）
 *  野火 PY32F030K28 核心板：请按规格书 V1.1 第 “LED 电路” 一节核对实际引脚！
 *  常见可能：PB2 / PC13 / PA15。若你板子上 LED 不闪，先改这里再编译。
 *  接线极性：本驱动假设 LED 阴极接 GPIO，输出低电平点亮 → Toggle 即可闪烁。
 * ────────────────────────────────────────────────────────────────────────── */
#define LED_GPIO_PORT            GPIOB
#define LED_GPIO_PIN             GPIO_PIN_2
#define LED_GPIO_CLK_ENABLE()    __HAL_RCC_GPIOB_CLK_ENABLE()

/* Called on unrecoverable errors: disables IRQ and hangs */
void APP_ErrorHandler(void);

#endif /* __MAIN_H */
