/*
 * py32f0xx_hal_msp.c — 外设底层硬件初始化
 *
 * HAL_UART_Init() 会自动调用 HAL_UART_MspInit()，
 * 在这里完成 GPIO 复用和 NVIC 配置。
 * 这样外设驱动层（uart.c）与硬件配置分离，方便移植。
 */
#include "main.h"
#include "uart.h"

void HAL_MspInit(void)
{
    /* 系统级 MSP 初始化，暂无额外操作 */
}

/*
 * HAL_UART_MspInit — 由 HAL_UART_Init() 自动调用
 *
 * 引脚分配（PY32F030K28U6，请核对数据手册 Alternate Function 表）：
 *   PA2  USART1_TX  AF1  推挽输出  → 接 WBR3 RX
 *   PA3  USART1_RX  AF1  上拉输入  ← 接 WBR3 TX
 *
 * 如果你的芯片 PA2/PA3 的 USART1 AF 编号不是 AF1，
 * 请修改下面两处 gpio.Alternate 的值。
 */
void HAL_UART_MspInit(UART_HandleTypeDef *huart)
{
    GPIO_InitTypeDef gpio = {0};

    if (huart->Instance == USART1)
    {
        /* 使能外设时钟 */
        __HAL_RCC_USART1_CLK_ENABLE();
        __HAL_RCC_GPIOA_CLK_ENABLE();

        /* PA2 → USART1_TX：推挽复用输出 */
        gpio.Pin       = GPIO_PIN_2;
        gpio.Mode      = GPIO_MODE_AF_PP;
        gpio.Pull      = GPIO_NOPULL;
        gpio.Speed     = GPIO_SPEED_FREQ_HIGH;
        gpio.Alternate = GPIO_AF1_USART1;
        HAL_GPIO_Init(GPIOA, &gpio);

        /* PA3 → USART1_RX：上拉复用输入 */
        gpio.Pin       = GPIO_PIN_3;
        gpio.Mode      = GPIO_MODE_AF_PP;
        gpio.Pull      = GPIO_PULLUP;
        gpio.Alternate = GPIO_AF1_USART1;
        HAL_GPIO_Init(GPIOA, &gpio);

        /* 配置 USART1 中断优先级并使能
         * 优先级 1（比 SysTick 优先级 3 高），确保接收字节不丢失 */
        HAL_NVIC_SetPriority(USART1_IRQn, 1, 0);
        HAL_NVIC_EnableIRQ(USART1_IRQn);
    }
}

void HAL_UART_MspDeInit(UART_HandleTypeDef *huart)
{
    if (huart->Instance == USART1)
    {
        __HAL_RCC_USART1_FORCE_RESET();
        __HAL_RCC_USART1_RELEASE_RESET();
        HAL_GPIO_DeInit(GPIOA, GPIO_PIN_2 | GPIO_PIN_3);
        HAL_NVIC_DisableIRQ(USART1_IRQn);
    }
}
