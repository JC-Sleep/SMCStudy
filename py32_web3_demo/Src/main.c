#include "main.h"
#include "dht11.h"
#include "uart.h"
#include "tuya_port.h"

static void SystemClock_Config(void);

int main(void)
{
    /* 1. 初始化 HAL（配置 SysTick 为 1ms 时基） */
    HAL_Init();

    /* 2. 配置系统时钟为内部 HSI 8MHz */
    SystemClock_Config();

    /* 3. 外设初始化 */
    UART1_Init();    /* USART1 → WBR3，115200bps，同时使能 RXNE 中断 */
    DHT11_Init();    /* PA0 输出模式，空闲拉高 */
    TuyaPort_Init(); /* 初始化涂鸦协议状态机和缓冲区 */

    uint32_t lastReportTick = 0U;

    while (1)
    {
        /* 驱动涂鸦串口协议状态机（解析来自 WBR3 的心跳、查询等帧） */
        TuyaPort_Process();

        /* 每 30 秒读取一次 DHT11 并上报到涂鸦云 */
        if ((HAL_GetTick() - lastReportTick) >= 30000UL)
        {
            lastReportTick = HAL_GetTick();

            DHT11_Data_t sensor;
            if (DHT11_Read(&sensor) == DHT11_OK)
            {
                TuyaPort_ReportTemperature((int32_t)sensor.temperature);
                TuyaPort_ReportHumidity((int32_t)sensor.humidity);
            }
            /* 校验失败时静默跳过，下一个 30s 周期重试 */
        }
    }
}

/*
 * SystemClock_Config
 * 使用内部 HSI 8MHz，HCLK = PCLK = 8MHz，Flash 等待 0 周期。
 */
static void SystemClock_Config(void)
{
    RCC_OscInitTypeDef osc = {0};
    RCC_ClkInitTypeDef clk = {0};

    osc.OscillatorType      = RCC_OSCILLATORTYPE_HSI;
    osc.HSIState            = RCC_HSI_ON;
    osc.HSIDiv              = RCC_HSI_DIV1;
    osc.HSICalibrationValue = RCC_HSICALIBRATION_8MHz;
    if (HAL_RCC_OscConfig(&osc) != HAL_OK)
    {
        APP_ErrorHandler();
    }

    clk.ClockType      = RCC_CLOCKTYPE_HCLK | RCC_CLOCKTYPE_SYSCLK | RCC_CLOCKTYPE_PCLK1;
    clk.SYSCLKSource   = RCC_SYSCLKSOURCE_HSI;
    clk.AHBCLKDivider  = RCC_SYSCLK_DIV1;
    clk.APB1CLKDivider = RCC_HCLK_DIV1;
    if (HAL_RCC_ClockConfig(&clk, FLASH_LATENCY_0) != HAL_OK)
    {
        APP_ErrorHandler();
    }
}

/*
 * APP_ErrorHandler
 * 不可恢复的错误：关中断后死循环。
 * 调试时可在此处打断点观察调用栈。
 */
void APP_ErrorHandler(void)
{
    __disable_irq();
    while (1) {}
}

#ifdef USE_FULL_ASSERT
void assert_failed(uint8_t *file, uint32_t line)
{
    (void)file;
    (void)line;
    APP_ErrorHandler();
}
#endif
