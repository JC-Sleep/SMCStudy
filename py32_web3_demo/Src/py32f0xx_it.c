/*
 * py32f0xx_it.c — 中断服务函数
 *
 * 所有 ISR 都在这里集中管理，便于查找和调试。
 */
#include "main.h"
#include "tuya_port.h"

extern UART_HandleTypeDef huart1;

/* ── 系统异常 ───────────────────────────────────────────────────────────── */

void NMI_Handler(void)
{
    /* 不可屏蔽中断，直接死循环 */
    while (1) {}
}

void HardFault_Handler(void)
{
    /* 硬件错误，直接死循环
     * 调试时在此打断点，查看 r0-r3, lr, pc 寄存器定位出错位置 */
    while (1) {}
}

/* ── SysTick：HAL 时基（每 1ms 触发一次） ──────────────────────────────── */

void SysTick_Handler(void)
{
    HAL_IncTick();   /* HAL_GetTick() 的计数器 +1 */
}

/* ── USART1：接收中断 ───────────────────────────────────────────────────── */
/*
 * 每当 WBR3 发来一个字节，RXNE 标志置位，进入此 ISR。
 * 我们直接读走数据寄存器（DR），交给涂鸦协议解析器缓冲。
 *
 * 关键原则：ISR 内只做"存入缓冲区"，不做任何协议处理，
 * 保证 ISR 执行时间极短，不影响 DHT11 的微秒级时序。
 */
void USART1_IRQHandler(void)
{
    /* 接收寄存器非空（有新字节到达） */
    if (READ_BIT(USART1->SR, USART_SR_RXNE))
    {
        uint8_t byte = (uint8_t)(USART1->DR & 0xFFU);
        TuyaPort_FeedByte(byte);   /* 存入环形缓冲区，主循环里处理 */
    }

    /* 清除溢出错误（ORE）——如果处理不及时导致溢出，读 SR 再读 DR 即可清除
     * 不清除会导致 ISR 反复触发，陷入死循环 */
    if (READ_BIT(USART1->SR, USART_SR_ORE))
    {
        (void)USART1->SR;
        (void)USART1->DR;
    }
}
