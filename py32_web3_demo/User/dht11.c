/*
 * dht11.c — DHT11 温湿度传感器单总线驱动
 *
 * 硬件连接：
 *   DHT11 DATA ──[4.7kΩ 上拉到 3.3V]── PA0
 *
 * 协议概述（单总线，主机主动发起）：
 *   1. 主机输出拉低 20ms（叫醒传感器）
 *   2. 释放后传感器响应：低 80µs → 高 80µs
 *   3. 传输 40 bit 数据：
 *        每 bit = 50µs 低（前导）+ 高电平
 *        高电平 ~26µs → bit=0
 *        高电平 ~70µs → bit=1
 *   4. 校验：byte4 == (byte0+byte1+byte2+byte3) & 0xFF
 *
 * 微秒延时使用 SysTick 硬件计数器，比软件循环更精确。
 * 需要在 HAL_Init() + SystemClock_Config() 之后才能调用。
 */
#include "dht11.h"

/* ── 微秒级延时（基于 SysTick，最大 1000µs，SysTick 1ms 周期内安全） ── */
static void delay_us(uint32_t us)
{
    uint32_t start  = SysTick->VAL;                          /* SysTick 当前值（倒计时） */
    uint32_t ticks  = us * (SystemCoreClock / 1000000U);     /* 需要经过的 tick 数 */
    uint32_t reload = SysTick->LOAD;

    while (1)
    {
        uint32_t now  = SysTick->VAL;
        uint32_t diff;
        if (start >= now)
        {
            diff = start - now;                              /* 正常：未发生重载 */
        }
        else
        {
            diff = (reload + 1U) + start - now;             /* 发生过一次重载 */
        }
        if (diff >= ticks)
        {
            break;
        }
    }
}

/* ── GPIO 模式切换辅助函数 ──────────────────────────────────────────────── */

static void DHT11_SetOutput(void)
{
    GPIO_InitTypeDef gpio = {0};
    gpio.Pin   = DHT11_GPIO_PIN;
    gpio.Mode  = GPIO_MODE_OUTPUT_PP;   /* 推挽输出，主机可主动拉低 */
    gpio.Pull  = GPIO_NOPULL;
    gpio.Speed = GPIO_SPEED_FREQ_HIGH;
    HAL_GPIO_Init(DHT11_GPIO_PORT, &gpio);
}

static void DHT11_SetInput(void)
{
    GPIO_InitTypeDef gpio = {0};
    gpio.Pin  = DHT11_GPIO_PIN;
    gpio.Mode = GPIO_MODE_INPUT;
    gpio.Pull = GPIO_PULLUP;            /* 上拉，配合外部 4.7kΩ 保持总线空闲高电平 */
    HAL_GPIO_Init(DHT11_GPIO_PORT, &gpio);
}

/* ── 公共 API ────────────────────────────────────────────────────────────── */

void DHT11_Init(void)
{
    __HAL_RCC_GPIOA_CLK_ENABLE();   /* DHT11_GPIO_PORT 当前是 GPIOA；若改 PB/PC 需同步改 */
    DHT11_SetOutput();
    HAL_GPIO_WritePin(DHT11_GPIO_PORT, DHT11_GPIO_PIN, GPIO_PIN_SET);  /* 总线空闲高 */
    HAL_Delay(1);  /* 上电后稳定等待 */
}

/*
 * DHT11_TestIdleLevel — 测 DATA 线空闲电平（诊断用）
 *
 * 把 PA0 设为浮空输入（不开内部上拉），连续读 N 次，看读到 1 的比例。
 * 接线正确时（有外部上拉，DHT11 上电正常）：应该 100% 读到 1。
 * 全 0 → DATA 线被拉死（短路到 GND / DHT11 占线异常 / 引脚根本没接）
 * 0/1 抖动 → 浮空（没接 DHT11，或 DATA 线断了，没有任何驱动）
 */
uint8_t DHT11_TestIdleLevel(void)
{
    GPIO_InitTypeDef gpio = {0};
    uint8_t high_count = 0;

    gpio.Pin  = DHT11_GPIO_PIN;
    gpio.Mode = GPIO_MODE_INPUT;
    gpio.Pull = GPIO_NOPULL;   /* 不开内部上拉，只看外部驱动状态 */
    HAL_GPIO_Init(DHT11_GPIO_PORT, &gpio);

    for (int i = 0; i < 10; i++)
    {
        if (HAL_GPIO_ReadPin(DHT11_GPIO_PORT, DHT11_GPIO_PIN) == GPIO_PIN_SET)
        {
            high_count++;
        }
        HAL_Delay(1);
    }

    /* 测完恢复成驱动模式 */
    DHT11_SetOutput();
    HAL_GPIO_WritePin(DHT11_GPIO_PORT, DHT11_GPIO_PIN, GPIO_PIN_SET);

    return high_count;   /* 0~10，期望 10 */
}

/*
 * DHT11_SampleWaveform — 抓取启动脉冲后 1ms 内 DATA 线电平变化
 *
 * 用途：当 DHT11_Read 一直 stage=1 (无响应) 时，调这个看传感器到底有没有
 * 任何电平变化。结果字符串形如 "HHHHHHLLLLLLLLLLHHHHHHHH..."
 *   - 全 H：传感器完全无反应（基本可以判定坏了）
 *   - 出现 L 段：传感器有响应，只是我们的位读取时序对不上
 *
 * 参数 start_ms：启动脉冲低电平时长（DHT11=20，DHT22=5，可试 1/5/10/20/30）
 * 输出 buf[100]：100 个采样点，每点间隔 10µs（共 ~1ms 窗口）
 */
void DHT11_SampleWaveform(uint8_t start_ms, char buf[101])
{
    /* 启动脉冲 */
    DHT11_SetOutput();
    HAL_GPIO_WritePin(DHT11_GPIO_PORT, DHT11_GPIO_PIN, GPIO_PIN_RESET);
    HAL_Delay(start_ms);
    HAL_GPIO_WritePin(DHT11_GPIO_PORT, DHT11_GPIO_PIN, GPIO_PIN_SET);
    delay_us(30);
    DHT11_SetInput();

    /* 禁中断，连续采样 */
    __disable_irq();
    for (int i = 0; i < 100; i++)
    {
        buf[i] = (HAL_GPIO_ReadPin(DHT11_GPIO_PORT, DHT11_GPIO_PIN) == GPIO_PIN_SET)
                  ? 'H' : 'L';
        delay_us(10U);
    }
    __enable_irq();
    buf[100] = '\0';

    /* 恢复 IO */
    DHT11_SetOutput();
    HAL_GPIO_WritePin(DHT11_GPIO_PORT, DHT11_GPIO_PIN, GPIO_PIN_SET);
}

/*
 * DHT11_Read — 读取一次温湿度数据
 *
 * 返回 DHT11_OK：data 中包含有效数据
 * 返回 DHT11_ERROR：超时或校验失败，data 内容无效，可在下次循环重试
 *
 * 上一次失败的具体阶段（供调试用，断点观察或在 main.c 中打印）：
 *   1 = 等不到 DHT11 拉低应答（接线 / 供电 / 没传感器）
 *   2 = 等不到应答低电平结束（传感器异常）
 *   3 = 等不到应答高电平结束（传感器异常）
 *   4 = 读 bit 时前导低电平超时（中断打断 / 时序漂移）
 *   5 = 读 bit 时高电平超时（中断打断 / 时序漂移）
 *   6 = 40 bit 都读到了但校验和不匹配
 */
volatile uint8_t g_dht11_fail_stage = 0;
volatile uint8_t g_dht11_raw[5]     = {0};

DHT11_Status DHT11_Read(DHT11_Data_t *data)
{
    uint8_t  raw[5] = {0};
    uint32_t timeout;

    /* ── 步骤 1：主机发起信号（输出模式，低 20ms 叫醒 DHT11） ──────────── */
    DHT11_SetOutput();
    HAL_GPIO_WritePin(DHT11_GPIO_PORT, DHT11_GPIO_PIN, GPIO_PIN_RESET);
    HAL_Delay(20);                                           /* ≥18ms 启动脉冲 */
    HAL_GPIO_WritePin(DHT11_GPIO_PORT, DHT11_GPIO_PIN, GPIO_PIN_SET);
    delay_us(30);                                            /* 释放后等 30µs */

    /* ── 步骤 2：切换输入，等待 DHT11 应答 ─────────────────────────────── */
    DHT11_SetInput();

    /* ⚠️ 进入临界区：禁中断，防止 SysTick(1ms) 在 µs 级位采样窗口里打断时序。
     *    整个 40 bit 读取约耗 5ms，期间 HAL_GetTick() 不前进、HAL_Delay 不可用，
     *    但 main 循环对这点偏差不敏感（30s 上报周期）。 */
    __disable_irq();

    /* 等待 DHT11 拉低（应答低电平） */
    timeout = 100U;
    while (HAL_GPIO_ReadPin(DHT11_GPIO_PORT, DHT11_GPIO_PIN) == GPIO_PIN_SET)
    {
        delay_us(1U);
        if (--timeout == 0U) { __enable_irq(); g_dht11_fail_stage = 1; return DHT11_ERROR; }
    }
    /* 等待低电平结束（约 80µs） */
    timeout = 100U;
    while (HAL_GPIO_ReadPin(DHT11_GPIO_PORT, DHT11_GPIO_PIN) == GPIO_PIN_RESET)
    {
        delay_us(1U);
        if (--timeout == 0U) { __enable_irq(); g_dht11_fail_stage = 2; return DHT11_ERROR; }
    }
    /* 等待高电平结束（约 80µs，准备数据传输） */
    timeout = 100U;
    while (HAL_GPIO_ReadPin(DHT11_GPIO_PORT, DHT11_GPIO_PIN) == GPIO_PIN_SET)
    {
        delay_us(1U);
        if (--timeout == 0U) { __enable_irq(); g_dht11_fail_stage = 3; return DHT11_ERROR; }
    }

    /* ── 步骤 3：读取 40 bit（5 byte = 湿度整/小 + 温度整/小 + 校验） ─── */
    for (int b = 0; b < 5; b++)
    {
        for (int bit = 7; bit >= 0; bit--)   /* 高位先出 */
        {
            /* 等待每个 bit 的 50µs 前导低电平结束 */
            timeout = 100U;
            while (HAL_GPIO_ReadPin(DHT11_GPIO_PORT, DHT11_GPIO_PIN) == GPIO_PIN_RESET)
            {
                delay_us(1U);
                if (--timeout == 0U) { __enable_irq(); g_dht11_fail_stage = 4; return DHT11_ERROR; }
            }

            /* 高电平开始后 40µs 采样：
             *   bit=0：高电平约 26µs，40µs 时已结束变低 → 读到低电平
             *   bit=1：高电平约 70µs，40µs 时尚未结束  → 读到高电平 */
            delay_us(40U);
            if (HAL_GPIO_ReadPin(DHT11_GPIO_PORT, DHT11_GPIO_PIN) == GPIO_PIN_SET)
            {
                raw[b] |= (uint8_t)(1U << bit);   /* 高电平还在 → 置 1 */

                /* 等待高电平结束（最多 ~70µs - 已采样的 40µs = 30µs 左右），
                 * 给 100 个 µs 的余量，否则下一个 bit 的前导低电平计时会乱 */
                timeout = 100U;
                while (HAL_GPIO_ReadPin(DHT11_GPIO_PORT, DHT11_GPIO_PIN) == GPIO_PIN_SET)
                {
                    delay_us(1U);
                    if (--timeout == 0U) { __enable_irq(); g_dht11_fail_stage = 5; return DHT11_ERROR; }
                }
            }
        }
    }

    /* ── 临界区结束，恢复中断 ─────────────────────────────────────────── */
    __enable_irq();

    /* 保存原始字节供调试观察（即使校验失败也保留） */
    g_dht11_raw[0] = raw[0]; g_dht11_raw[1] = raw[1]; g_dht11_raw[2] = raw[2];
    g_dht11_raw[3] = raw[3]; g_dht11_raw[4] = raw[4];

    /* ── 步骤 4：校验 ────────────────────────────────────────────────────── */
    /* raw[4] == (raw[0] + raw[1] + raw[2] + raw[3]) 的低 8 位 */
    if (((raw[0] + raw[1] + raw[2] + raw[3]) & 0xFFU) != raw[4])
    {
        g_dht11_fail_stage = 6;
        return DHT11_ERROR;
    }

    g_dht11_fail_stage = 0;
    data->humidity    = raw[0];   /* 湿度整数部分（raw[1] 小数部分 DHT11 恒为 0） */
    data->temperature = raw[2];   /* 温度整数部分（raw[3] 小数部分 DHT11 恒为 0） */
    return DHT11_OK;
}
