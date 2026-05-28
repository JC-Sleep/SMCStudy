/*
 * uart.c — USART1 驱动（连接涂鸦 WBR3 模块）
 *
 * 初始化：
 *   - 115200bps，8-N-1
 *   - GPIO 配置和 NVIC 配置由 py32f0xx_hal_msp.c 的 HAL_UART_MspInit() 完成
 *   - 使能 RXNE 中断，每收到一个字节触发 USART1_IRQHandler
 *
 * 发送：
 *   - 使用阻塞轮询发送（HAL_UART_Transmit），超时 1000ms
 *   - 对于涂鸦协议帧（最大约 30 字节），轮询发送简单可靠
 */
#include "uart.h"
#include "tuya_port.h"

UART_HandleTypeDef huart1;

void UART1_Init(void)
{
    huart1.Instance                    = TUYA_UART_INSTANCE;
    huart1.Init.BaudRate               = TUYA_UART_BAUD;
    huart1.Init.WordLength             = UART_WORDLENGTH_8B;
    huart1.Init.StopBits               = UART_STOPBITS_1;
    huart1.Init.Parity                 = UART_PARITY_NONE;
    huart1.Init.HwFlowCtl              = UART_HWCONTROL_NONE;
    huart1.Init.Mode                   = UART_MODE_TX_RX;
    huart1.Init.OverSampling           = UART_OVERSAMPLING_16;
    huart1.AdvancedInit.AdvFeatureInit = UART_ADVFEATURE_NO_INIT;

    if (HAL_UART_Init(&huart1) != HAL_OK)   /* 内部调用 HAL_UART_MspInit() */
    {
        APP_ErrorHandler();
    }

    /* 使能接收寄存器非空中断（RXNE）
     * 每收到一个字节就触发 USART1_IRQHandler → TuyaPort_FeedByte() */
    __HAL_UART_ENABLE_IT(&huart1, UART_IT_RXNE);
}

void UART1_SendByte(uint8_t byte)
{
    HAL_UART_Transmit(&huart1, &byte, 1U, 100U);
}

void UART1_SendBytes(const uint8_t *data, uint16_t len)
{
    HAL_UART_Transmit(&huart1, (uint8_t *)data, len, 1000U);
}
