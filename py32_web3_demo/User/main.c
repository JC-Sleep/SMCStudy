#include "main.h"
#include "dht11.h"
#include "uart.h"
#include "wifi.h"    /* 涂鸦官方 SDK 入口（包含 mcu_api.h / protocol.h） */
#include <stdio.h>

/* DHT11 调试信息（由 dht11.c 写入） */
extern volatile uint8_t g_dht11_fail_stage;
extern volatile uint8_t g_dht11_raw[5];

static void SystemClock_Config(void);
static void LED_Init(void);

/* 最近一次传感器读数：由主循环写入，all_data_update() 读取（在 protocol.c 中 extern 声明） */
volatile uint8_t g_last_temp = 0U;
volatile uint8_t g_last_humi = 0U;

int main(void)
{
    /* 1. 初始化 HAL（配置 SysTick 为 1ms 时基） */
    HAL_Init();

    /* 2. 配置系统时钟为内部 HSI 8MHz */
    SystemClock_Config();

    /* 3. 外设初始化 */
    UART1_Init();           /* USART1 → WBR3 或 USB-TTL，9600bps，使能 RXNE 中断 */
    LED_Init();             /* 板载 LED 心跳指示 */
    DHT11_Init();           /* PA0 输出模式，空闲拉高 */
    wifi_protocol_init();   /* 涂鸦 SDK 初始化：清空 RX 缓冲区、复位状态机 */

#if DEBUG_UART_PRINTF
    /* MCU 复位瞬间 USART TX 引脚跳变会让 USB-TTL 收到几字节噪声（framing error），
     * 先延时让线稳定，再发一串前导，把真正的 banner 推到噪声窗口之后 */
    HAL_Delay(100);
    printf("\r\n\r\n\r\n========================================\r\n");
    printf("[BOOT] PY32+WBR3+DHT11 demo build " __DATE__ " " __TIME__ "\r\n");
    printf("[BOOT] SystemCoreClock = %lu Hz (expect 24000000)\r\n",
           (unsigned long)SystemCoreClock);

    /* 主动测一次 PA0 空闲电平 — 比等 30s DHT11 失败有用得多
     *   high=10  -> wiring OK, line idle HIGH (good, DHT11 powered & connected)
     *   high=0   -> line stuck LOW   (DATA short to GND? wrong pin? sensor broken?)
     *   high=1~9 -> line floating    (DATA not connected, or sensor not powered) */
    uint8_t idle_high = DHT11_TestIdleLevel();
    printf("[BOOT] DHT11 DATA idle high = %u/10 (expect 10; <10 = HW problem)\r\n",
           (unsigned)idle_high);
    printf("========================================\r\n");
#endif

    /* DHT11 上电后第一次读取通常不稳定，先等 2s 让它稳定，再立刻试一次 */
    HAL_Delay(2000);

    uint32_t lastReportTick = (uint32_t)0 - 28000UL;   /* 让 2s 后立刻满足 30s 触发条件 */
    uint32_t lastBlinkTick  = 0U;

    while (1)
    {
        /* 驱动涂鸦串口协议状态机（解析来自 WBR3 的心跳、查询等帧） */
        wifi_uart_service();

        /* 心跳 LED：每 500ms 翻转一次。LED 不闪 = 程序没跑 / 卡死 / BOOT0 未拔 */
        if ((HAL_GetTick() - lastBlinkTick) >= 500UL)
        {
            lastBlinkTick = HAL_GetTick();
            HAL_GPIO_TogglePin(LED_GPIO_PORT, LED_GPIO_PIN);
        }

        /* 每 30 秒读取一次 DHT11 并上报到涂鸦云 */
        if ((HAL_GetTick() - lastReportTick) >= 30000UL)
        {
            lastReportTick = HAL_GetTick();

            DHT11_Data_t sensor;
            if (DHT11_Read(&sensor) == DHT11_OK)
            {
                /* 保存到全局变量供 all_data_update() 使用 */
                g_last_temp = sensor.temperature;
                g_last_humi = sensor.humidity;

#if DEBUG_UART_PRINTF
                printf("[DHT11] T=%u C, H=%u %%RH\r\n",
                       (unsigned)sensor.temperature,
                       (unsigned)sensor.humidity);
#endif

                /* 通过涂鸦官方 SDK 上报 DP 到云端
                 * 注意：调试模式（PA2/PA3 接 USB-TTL，没接 WBR3）下，
                 * 这一行只是把二进制 55 AA 帧发到 XCOM，会和上面 printf 混在一起，
                 * 这是正常的，等切到运行模式接 WBR3 时才有实际作用。 */
                mcu_dp_value_update(DPID_TEMP_CURRENT,   (u32)sensor.temperature);
                mcu_dp_value_update(DPID_HUMIDITY_VALUE, (u32)sensor.humidity);
            }
#if DEBUG_UART_PRINTF
            else
            {
                /* 失败时先测一次硬件电平（最重要的诊断），再打印失败堆栈 */
                uint8_t lvl = DHT11_TestIdleLevel();
                printf(">>> [DIAG] DATA idle high = %u/10  "
                       "(10=HW OK, sensor maybe dead; 0=short to GND; 1-9=floating/no power)\r\n",
                       (unsigned)lvl);

                /* 当 idle=10/10 但仍 stage=1：抓波形看传感器有无任何响应。
                 * 同时尝试 DHT11 时序(20ms)和 DHT22 时序(5ms)，自动判断你手上的传感器类型。 */
                if (lvl == 10 && g_dht11_fail_stage == 1)
                {
                    char wave[101];
                    DHT11_SampleWaveform(20, wave);   /* DHT11 标准启动脉冲 20ms */
                    printf(">>> [WAVE 20ms ] %s\r\n", wave);
                    HAL_Delay(50);
                    DHT11_SampleWaveform(5,  wave);   /* DHT22 标准启动脉冲 5ms  */
                    printf(">>> [WAVE  5ms ] %s\r\n", wave);
                    printf(">>> Read meaning: all 'H' = sensor DEAD; any 'L' = sensor RESPONDS, "
                           "timing tweak needed\r\n");
                }

                /* fail_stage meaning (see dht11.c, ASCII-only to avoid XCOM mojibake):
                 *   1 = no response from DHT11   -> WIRING/POWER (most common)
                 *   2 = ack low  not finished    -> sensor bad / strong EMI
                 *   3 = ack high not finished    -> sensor bad / strong EMI
                 *   4 = bit preamble low timeout -> us-level timing drift
                 *   5 = bit high timeout         -> us-level timing drift
                 *   6 = checksum mismatch        -> cable too long / EMI */
                printf("[DHT11] FAIL stage=%u raw=%02X %02X %02X %02X %02X "
                       "(1=HW/PWR 4/5=timing 6=EMI)\r\n",
                       (unsigned)g_dht11_fail_stage,
                       g_dht11_raw[0], g_dht11_raw[1], g_dht11_raw[2],
                       g_dht11_raw[3], g_dht11_raw[4]);
            }
#endif
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
    /* DHT11 单总线对时序敏感，8MHz 下 HAL_GPIO_ReadPin 轮询太慢
     * （>5µs / 次），无法可靠区分 26µs(bit=0) vs 70µs(bit=1) 高脉冲。
     * 改用 24MHz HSI（PY32F030 出厂校准支持）后 µs 级位翻转可靠捕获。 */
    osc.HSICalibrationValue = RCC_HSICALIBRATION_24MHz;
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
 * LED_Init
 * 板载用户 LED → 推挽输出，上电默认熄灭（输出高，假设 LED 阴极接 GPIO）。
 */
static void LED_Init(void)
{
    GPIO_InitTypeDef gpio = {0};

    LED_GPIO_CLK_ENABLE();

    gpio.Pin   = LED_GPIO_PIN;
    gpio.Mode  = GPIO_MODE_OUTPUT_PP;
    gpio.Pull  = GPIO_NOPULL;
    gpio.Speed = GPIO_SPEED_FREQ_LOW;
    HAL_GPIO_Init(LED_GPIO_PORT, &gpio);

    HAL_GPIO_WritePin(LED_GPIO_PORT, LED_GPIO_PIN, GPIO_PIN_SET);
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
