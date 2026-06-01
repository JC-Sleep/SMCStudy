# PY32F030K28U6 + WBR3 + DHT11 涂鸦 IoT 温湿度 Demo

## 项目概述

本项目实现一个完整的物联网温湿度采集系统：

- **主控 MCU**：野火 PY32F030K28U6（ARM Cortex-M0+，64KB Flash，8KB RAM）
- **WiFi 模块**：涂鸦 WBR3（出厂已刷好涂鸦固件，**不需要写代码**）
- **传感器**：DHT11（单总线，温度精度 ±2°C，湿度精度 ±5% RH）
- **烧录方式**：**推荐 ISP（USB-TTL + PY32 ISP Tool）**，无需调试器；也支持 DAPLink（CMSIS-DAP）

> ⚠️ **关于 PY32F030K28 没有 PA9 的说明**
> 网上很多教程写"USB-TTL RX 接 MCU 的 PA9"，那是 **STM32F1** 的 USART1_TX 引脚。
> PY32F030K28U6（32 脚 LQFP/QFN 封装）**根本没有引出 PA9/PA10**，
> 它的 USART1 复用在 **PA2 (TX) / PA3 (RX)（AF1）** 上 —— 也就是 ISP 烧录和运行时跟 WBR3 通信
> 共用的同一对引脚。下面所有接线都按这个来。

**整体数据流：**

```
DHT11 ──[单总线 PA0]──▶ PY32F030 ──[UART1 115200]──▶ WBR3 ──[WiFi]──▶ 涂鸦云 ──▶ 手机 App
```

---

## 硬件连接

### ⚠️ 三种工作模式，接线不一样

PY32F030K28 的 USART1（PA2/PA3）同时被三个角色"抢用"：
**ISP 烧录的 USB-TTL** / **运行时的 WBR3** / **调试时的 XCOM 串口监视器**。
任意时刻只能接其中一个，要靠 **BOOT0(PF4) 状态** + **拔插跳线** 来切换。

| 模式 | BOOT0(PF4) | PA2 / PA3 接谁 | LED 表现 | 你能看到什么 |
|------|-----------|----------------|---------|-------------|
| **① ISP 烧录** | 短接到 **3V3**，然后**重新上电** | USB-TTL（TX↔PA3, RX↔PA2） | 不闪（在 BootROM 里） | PY32 ISP Tool 显示 `Connect PASS` |
| **② 调试运行（推荐先用这个验证 DHT11）** | **断开短接**（接 GND 或悬空），重新上电 | USB-TTL（TX↔PA3, RX↔PA2） | 每 500ms 闪一次 | XCOM 9600 8-N-1 看到 `[BOOT]…` 和 `[DHT11] T=25 C, H=60 %RH` |
| **③ 联网运行（最终形态）** | 断开短接 | WBR3（PA2→WBR3 RX，PA3←WBR3 TX） | 每 500ms 闪一次 | 涂鸦 App 显示温湿度，XCOM 拔掉 |

> ✅ "BOOT0 拔掉短接 + 断电 → 再上电"是从烧录模式切到运行模式的**必经步骤**，不做的话每次上电都进 BootROM，用户程序永远不跑。

### 模式 ① ISP 烧录接线

```
USB-TTL                       PY32F030K28
  TX ─────────────────────────  PA3 (USART1_RX)
  RX ─────────────────────────  PA2 (USART1_TX)
  GND ────────────────────────  GND
  3V3 ────────────────────────  3V3
                                PF4 ────┐
                                        ├─ 短接（跳线帽 / 杜邦线）
                                3V3 ────┘
```
操作：装好跳线 → 给板子上电 → PY32 ISP Tool 选 COM 口 → `connect` → 加载 hex → `Download` → 看到 `Connect PASS` 且烧录百分比到 100 即成功。

### 模式 ② 调试运行接线（USB-TTL 当串口监视器）

```
USB-TTL                       PY32F030K28              DHT11
  TX ─────────────────────────  PA3                    
  RX ─────────────────────────  PA2                    
  GND ────────────────────────  GND ────────────────── GND
  3V3 ────────────────────────  3V3 ────────────────── VCC
                                PA0 ──┬─────────────── DATA
                                      │
                              [4.7kΩ] │
                                      │
                                3V3 ──┘
                                PF4 (悬空 / 接 GND)
```
**WBR3 此时不接！** PA2/PA3 不能同时挂两个设备发送数据，会冲突。

XCOM 配置：波特率 **9600**、8 数据位、1 停止位、无校验、无流控。
上电后立刻能看到：
```
[BOOT] PY32 + WBR3 + DHT11 demo, baud=9600, build=Jun  1 2026 12:34:56
[DHT11] T=25 C, H=60 %RH      ← 每 30 秒一行
```
*XCOM 里会夹杂一些 `55 AA 03 07 …` 二进制字节（涂鸦 DP 帧），属于正常现象，因为 WBR3 没接也照常发。*

### 模式 ③ 联网运行接线（最终形态）

```
                              PY32F030K28              DHT11
                                PA0 ──┬─[4.7kΩ]─ 3V3
                                      └────────── DATA  
                                3V3 ──────────── VCC ── GND ── GND
                                PA2 (USART1_TX) ─────── WBR3 RX
                                PA3 (USART1_RX) ─────── WBR3 TX
                                3V3 ─────────────────── WBR3 VCC（⚠️ 严禁 5V）
                                GND ─────────────────── WBR3 GND
                                PF4 (悬空)
```
USB-TTL 拔掉，温湿度通过涂鸦 App 看，LED 仍每 500ms 闪一次。

### 引脚分配总表

| MCU 引脚 | 功能 | 接谁 | 为什么 |
|---------|------|------|--------|
| **PA0**  | GPIO 动态切换 | DHT11 DATA（**裸件需 4.7kΩ 上拉到 3V3；3 脚模块版已自带上拉，直接接**） | DHT11 单总线协议 |
| **PA2**  | USART1_TX (AF1) | 模式①② USB-TTL RX / 模式③ WBR3 RX | 唯一可用的 USART1_TX |
| **PA3**  | USART1_RX (AF1) | 模式①② USB-TTL TX / 模式③ WBR3 TX | 唯一可用的 USART1_RX |
| **PB2**  | GPIO 输出 | 板载用户 LED（默认假设） | 心跳指示，500ms 翻转 |
| **PF4**  | BOOT0 选择 | 短接 3V3 = 进 BootROM / 悬空 = 跑用户程序 | 模式切换钥匙 |
| **PA13** | SWDIO | DAPLink SWDIO（可选） | 备选调试器 |
| **PA14** | SWDCK | DAPLink SWDCK（可选） | 备选调试器 |

> 💡 **LED 引脚（PB2）需对照野火规格书核对**：本仓库默认 `LED_GPIO_PIN = GPIO_PIN_2` / `GPIOB`，
> 在 [`Inc/main.h`](Inc/main.h) 顶部可一行改。若你的板子是 PC13 或 PA15，改完重编即可。

---

## 系统工作流程

### 数据流图

```
DHT11传感器                 PY32F030 MCU                 涂鸦云 & App
                           ┌─────────────┐
每30秒被读取一次            │             │
                           │  main.c     │
DHT11 ──单总线──────────▶  │  主循环     │
  湿度整数 (8bit)           │             │
  湿度小数 (8bit)           │  检验校验和  │
  温度整数 (8bit)    ────▶  │             │
  温度小数 (8bit)           │  tuya_port.c│  ──串口帧──▶  WBR3
  校验   (8bit)             │  构造DP帧   │              ──WiFi──▶  涂鸦云
                           │             │                         ──推送──▶  手机App
WBR3发来心跳/命令           │             │
   ──串口──────────────▶   │  ISR接收    │
                           │  状态机解析  │
                           │  自动回复   │
                           └─────────────┘
```

### DHT11 单总线时序

```
主机 PA0                  DHT11
  │
  │── 拉低 20ms ──────────────▶│ 收到启动信号
  │── 释放高 30µs ─────────────▶│
  │                             │── 拉低 80µs ──▶│ 应答低
  │                             │── 拉高 80µs ──▶│ 应答高，准备数据
  │
  │  ◀── 重复 40 次（每次一个 bit）──────────────
  │                             │── 低 50µs（前导）
  │                             │── 高 26µs → bit=0
  │                             │── 高 70µs → bit=1
  │
  │  在高电平开始后 40µs 采样：
  │    还是高电平 → 1
  │    已经变低   → 0
  │
  结果：5字节 = 湿度整数 + 湿度小数 + 温度整数 + 温度小数 + 校验
```

### 涂鸦串口握手时序

```
WBR3 上电                        PY32 MCU
  │
  │── 55 AA 03 00 00 01 00 03 ──▶│ 心跳查询
  │◀─ 55 AA 03 00 00 01 00 03 ───│ 第1次回复 data=0x00（MCU初始化中）
  │
  │── 55 AA 03 01 00 00 04 ──────▶│ 查询产品PID
  │◀─ 55 AA 03 01 xx xx {"p":"PID","v":"1.0.0","m":0} ──│ 回复PID
  │
  │── 55 AA 03 02 00 00 05 ──────▶│ 查询MCU版本
  │◀─ 55 AA 03 02 xx xx {"ver":"1.0.0"} ──────────────│ 回复版本
  │
  │── 55 AA 03 03 00 01 05 xx ──▶│ 通知：已连接涂鸦云
  │
  │   （每 30 秒）
  │◀─ 55 AA 03 07 00 08 01 02 00 04 00 00 00 19 xx ──── 上报温度 25°C
  │◀─ 55 AA 03 07 00 08 02 02 00 04 00 00 00 3C xx ──── 上报湿度 60%
  │
  │  手机 App 实时更新显示数据
```

---

## 涂鸦串口协议帧格式

每帧结构：

```
字节:    55   AA  [VER] [CMD] [LEN_H] [LEN_L] [DATA...]  [CHECKSUM]
位置:     0    1    2     3     4        5      6..n        n+1
```

| 字段 | 说明 |
|------|------|
| `55 AA` | 固定帧头，标识帧的开始 |
| `VER` | 版本号，MCU 发送固定用 `0x03` |
| `CMD` | 命令号（见下表） |
| `LEN` | DATA 段字节数，2字节大端 |
| `DATA` | 命令的 payload |
| `CHECKSUM` | `(VER + CMD + LEN_H + LEN_L + DATA各字节) & 0xFF` |

**命令号：**

| CMD | 方向 | 含义 |
|-----|------|------|
| `0x00` | 双向 | 心跳，WBR3 每 ~3s 问一次，MCU 必须回复 |
| `0x01` | WBR3→MCU→回复 | 查询产品 PID |
| `0x02` | WBR3→MCU→回复 | 查询 MCU 固件版本 |
| `0x03` | WBR3→MCU | WBR3 通知 WiFi/云连接状态（无需回复） |
| `0x07` | MCU→WBR3 | **MCU 上报传感器 DP 数据到云端** |
| `0x08` | WBR3→MCU | App 下发控制命令（本 Demo 传感器只读，留空） |

**DP 数据段格式（CMD=0x07 的 DATA）：**

```
[DP_ID 1B] [TYPE 1B] [LEN 2B] [VALUE 4B]
   0x01       0x02    0x00 04  大端 int32
```

---

## PY32F0xx 官方 SDK 文件说明

本项目不把 SDK 文件复制进来，而是在 `eide.yml` 中通过**绝对路径**引用
`PY32F0xx_Firmware-master` 里的文件，分"需要编译的 .c/.s"和"需要找到的 .h"两类。

### SDK 目录结构（只需关注这些部分）

```
PY32F0xx_Firmware-master/
│
├── Templates/PY32F030xx_Templates/EIDE/          ← 直接引用这里的两个文件
│   ├── startup_py32f030xx.s                      ← [编译] 启动文件
│   └── py32f030x8.ld                             ← [链接脚本] 内存布局
│
└── Drivers/
    ├── CMSIS/
    │   ├── Include/                              ← [头文件路径] ARM内核头文件
    │   │   └── core_cm0plus.h                    ← __NOP / SysTick 等内核操作
    │   └── Device/PY32F0xx/
    │       ├── Include/                          ← [头文件路径] 芯片头文件
    │       │   ├── py32f0xx.h                    ← 主芯片头（根据宏选择子文件）
    │       │   └── py32f030x8.h                  ← PY32F030x8 寄存器定义
    │       └── Source/
    │           └── system_py32f0xx.c             ← [编译] SystemInit()
    │
    └── PY32F0xx_HAL_Driver/
        ├── Inc/                                  ← [头文件路径] HAL 函数声明
        │   ├── py32f0xx_hal.h
        │   ├── py32f0xx_hal_uart.h
        │   ├── py32f0xx_hal_gpio.h
        │   └── ... (其他 HAL 头文件)
        └── Src/                                  ← [编译] HAL 驱动实现
            ├── py32f0xx_hal.c
            ├── py32f0xx_hal_cortex.c
            ├── py32f0xx_hal_dma.c
            ├── py32f0xx_hal_flash.c
            ├── py32f0xx_hal_gpio.c
            ├── py32f0xx_hal_pwr.c
            ├── py32f0xx_hal_rcc.c
            ├── py32f0xx_hal_rcc_ex.c
            └── py32f0xx_hal_uart.c
```

### 每个文件的作用和必要性

#### 必须编译的 .c / .s 文件（共 11 个）

| 文件 | 作用 | 如果不加会怎样 |
|------|------|---------------|
| `startup_py32f030xx.s` | **汇编启动文件**。芯片上电后第一个执行的代码，负责：① 把中断向量表放到 Flash 开头 ② 清零 BSS 段 ③ 复制初始化数据到 RAM ④ 调用 `SystemInit()` ⑤ 跳转到 `main()` | 没有它，芯片上电根本不知道从哪里开始执行，程序无法运行 |
| `system_py32f0xx.c` | 实现 `SystemInit()`，HAL_Init() 内部会自动调用，完成 Flash 时序等系统级初始化 | 链接报错：`undefined reference to SystemInit` |
| `py32f0xx_hal.c` | HAL 核心：`HAL_Init()`、`HAL_Delay()`、`HAL_GetTick()`，整个 HAL 体系的入口 | 无法调用任何 HAL 函数 |
| `py32f0xx_hal_cortex.c` | SysTick 配置、NVIC（中断控制器）优先级设置。`HAL_Init()` 内部配置 SysTick 就依赖它 | SysTick 不工作，`HAL_Delay()` 永远不返回；中断优先级无法设置 |
| `py32f0xx_hal_dma.c` | DMA 控制器驱动。HAL_UART 内部依赖 DMA 的数据结构定义，即使不用 DMA 传输也需要 | 编译报错，HAL_UART 相关函数缺少符号 |
| `py32f0xx_hal_flash.c` | Flash 读写和等待时序。`SystemClock_Config()` 配置时钟时必须调用 | `HAL_RCC_ClockConfig()` 内部调用 Flash 配置函数，报链接错误 |
| `py32f0xx_hal_gpio.c` | GPIO 初始化（`HAL_GPIO_Init`）、读写（`HAL_GPIO_ReadPin/WritePin`）。DHT11 驱动全靠它 | DHT11 无法工作，UART 引脚无法配置 |
| `py32f0xx_hal_pwr.c` | 电源管理。`HAL_Init()` 内部调用 `HAL_PWR_EnableBkUpAccess()` 等函数 | 链接阶段报未定义符号错误 |
| `py32f0xx_hal_rcc.c` | 时钟控制：配置 HSI/HSE/PLL，设置 HCLK/PCLK 分频，`SystemClock_Config()` 直接用它 | 无法配置系统时钟，芯片跑在默认低速时钟，UART 波特率会偏差 |
| `py32f0xx_hal_rcc_ex.c` | RCC 扩展：外设时钟使能宏（`__HAL_RCC_USART1_CLK_ENABLE()`、`__HAL_RCC_GPIOA_CLK_ENABLE()` 等）的实现 | UART 和 GPIO 时钟未使能，外设寄存器全为 0，无任何响应 |
| `py32f0xx_hal_uart.c` | UART/USART 驱动：`HAL_UART_Init()`、`HAL_UART_Transmit()`、中断使能等。连接 WBR3 的核心 | 无法初始化串口，无法与 WBR3 通信 |

#### 必须包含的头文件路径（共 3 个路径）

| 路径 | 包含什么 | 为什么需要 |
|------|----------|-----------|
| `Drivers/PY32F0xx_HAL_Driver/Inc` | 所有 HAL 函数声明（`py32f0xx_hal_uart.h` 等） | 你的代码调用 `HAL_UART_Init()` 时，编译器需要在这里找到它的声明 |
| `Drivers/CMSIS/Device/PY32F0xx/Include` | 芯片寄存器定义（`py32f030x8.h`），外设基地址、结构体 | `USART1->SR`、`GPIOA->ODR` 等寄存器操作，地址都在这里定义 |
| `Drivers/CMSIS/Include` | ARM 内核操作（`core_cm0plus.h`）：`__NOP()`、`SysTick->VAL`、`__disable_irq()` | DHT11 驱动里的 `SysTick->VAL` 微秒延时、中断开关都依赖这里 |

#### 链接脚本 .ld（1 个）

| 文件 | 作用 |
|------|------|
| `Templates/.../py32f030x8.ld` | 告诉链接器：Flash 从 `0x08000000` 开始，64KB；RAM 从 `0x20000000` 开始，8KB。还定义了栈大小（1KB）和堆大小。**没有它程序会烧到错误地址或栈溢出** |

### 如何添加到 EIDE 项目（两种方式）

#### 方式一：直接编辑 eide.yml（已完成✅）

本项目的 `eide.yml` 已配置好所有路径。EIDE 插件有时会还原配置，
若你发现 virtualFolder 为空，重新在 eide.yml 里写入（参考文件内的注释）。

#### 方式二：通过 EIDE 界面手动添加（GUI 操作）

1. EIDE 侧边栏 → 展开项目 → **Source Files（源文件）** 区域
2. 点击 `+` 图标 → **Add File（添加文件）**
3. 逐个选择上表中的 11 个 `.c/.s` 文件（或创建分组后批量添加一个目录）
4. **Include Directories（头文件目录）** 区域 → 点击 `+` → 添加上面 3 个路径
5. **Preprocessor Macros（预处理宏）** → 添加 `PY32F030x8`
6. **Linker Script（链接脚本）** → 选择 `py32f030x8.ld`

> 两种方式等效，GUI 操作最终也是修改 eide.yml，
> 建议用 GUI 操作后再检查 eide.yml 是否正确保存。

---

## 项目文件结构

```
py32_web3_demo/
├── .eide/
│   └── eide.yml              ← EIDE 工程配置（CPU/SDK路径/源文件/头文件路径）
├── Inc/
│   ├── py32f0xx_hal_conf.h   ← HAL 模块开关（启用了 RCC/GPIO/UART/DMA）
│   ├── main.h                ← 基础头文件
│   ├── dht11.h               ← DHT11 驱动接口定义
│   ├── uart.h                ← UART1 驱动接口定义
│   └── tuya_port.h           ← 涂鸦协议层接口（PID 在此配置）
└── Src/
    ├── main.c                ← 主循环：每 30s 读 DHT11 → 上报两个 DP
    ├── dht11.c               ← DHT11 单总线驱动（SysTick µs 延时）
    ├── uart.c                ← USART1 115200 初始化和发送
    ├── tuya_port.c           ← 涂鸦串口协议完整实现（帧解析状态机）
    ├── py32f0xx_hal_msp.c    ← GPIO 复用/NVIC 底层配置（PA2/PA3）
    └── py32f0xx_it.c         ← 中断服务（SysTick + USART1 接收）

外部 SDK（不复制，通过绝对路径引用）：
C:/WorkSoftware/a_program/a_ioHardware/PY32F0xx_Firmware-master/
├── Templates/PY32F030xx_Templates/EIDE/
│   ├── startup_py32f030xx.s  ← [编译] 启动文件
│   └── py32f030x8.ld         ← [链接] 内存布局
└── Drivers/
    ├── PY32F0xx_HAL_Driver/Src/  ← [编译] 9 个 HAL 驱动
    ├── PY32F0xx_HAL_Driver/Inc/  ← [头文件路径]
    ├── CMSIS/Device/PY32F0xx/Source/  ← [编译] system_py32f0xx.c
    ├── CMSIS/Device/PY32F0xx/Include/ ← [头文件路径] 寄存器定义
    └── CMSIS/Include/             ← [头文件路径] ARM 内核操作
```

---

## 开始前必须完成的配置

### 1. 填写涂鸦产品 PID

打开 `Inc/tuya_port.h`，第 16 行：

```c
// 改这里 ↓
#define TUYA_PRODUCT_ID    "YOUR_PID_HERE"
```

**在哪里找 PID：**
> [iot.tuya.com](https://iot.tuya.com) → 产品管理 → 选中你的产品 → 硬件开发 → 产品信息 → 产品 ID

### 1.5 调试开关（建议第一次先开）

打开 [`Inc/main.h`](Inc/main.h)：

```c
#define DEBUG_UART_PRINTF        1   // 1=XCOM 输出文本日志（调试），0=纯涂鸦协议（联网）
```

- 第一次跑：保持 `1` + 模式②接线 → 在 XCOM 看温湿度文本，验证 DHT11 工作正常
- 验证 OK 后：保持 `1` 也行，或改 `0` 减少 Flash 占用 → 切到模式③接 WBR3 联网


### 2. 选择烧录方式（二选一）

**方式 A — ISP 烧录（推荐，无需调试器）**

只需一根 USB-TTL。详细步骤见下面"ISP 烧录完整流程"。

**方式 B — DAPLink（SWD）烧录**

> EIDE 侧边栏 → 右键项目名 → **烧录设置（Upload Settings）**
> → Uploader 改为 **pyOCD**
> → Target 填入 `py32f030x8`

---

## ISP 烧录完整流程（最容易踩坑的地方）

```
┌─ 第 1 步：进入 BootROM ────────────────────────────────────────┐
│   ① 断电                                                       │
│   ② 用跳线帽 / 杜邦线把 PF4 短接到 3V3                         │
│   ③ USB-TTL 接 PA2/PA3/GND/3V3（见上面"模式①"）                │
│   ④ 上电（接 USB-TTL 的 USB 即可供电）                         │
└──────────────────────────────────────────────────────────────┘
                              ↓
┌─ 第 2 步：用 PY32 ISP Tool 烧录 ───────────────────────────────┐
│   ① 打开 PY32 ISP Tool V1.0.0                                  │
│   ② Device 选 USB-SERIAL CH340 的 COM 口，波特率 115200       │
│   ③ 点 connect → 应显示 "Connect PASS"（不代表程序在跑！）     │
│   ④ open 选择 .hex 文件                                        │
│   ⑤ 勾选 Program + Verify，点 Download                         │
│   ⑥ 等进度到 100%，下方提示烧录成功                            │
└──────────────────────────────────────────────────────────────┘
                              ↓
┌─ 第 3 步：切到运行模式（关键！90% 的人卡在这） ────────────────┐
│   ① 断电（拔掉 USB）                                          │
│   ② 拆掉 PF4 ↔ 3V3 的短接（让 PF4 悬空或接 GND）              │
│   ③ 重新上电                                                  │
│   ④ 板载 LED 应该开始每 500ms 闪一次 → 程序在跑 ✅            │
│   ⑤ 打开 XCOM（波特率 9600）应看到 [BOOT] 横幅                │
└──────────────────────────────────────────────────────────────┘
```

> ❗ **"Connect PASS" ≠ 程序在跑**
> 它只表示 ISP Tool 能跟 BootROM 通信成功，**只要 PF4 还短接到 3V3，每次上电都进 BootROM**，
> 你的 `main()` 一行也不会执行，所以 LED 不闪、XCOM 没数据。
> 看到 `Connect PASS` 之后，**必须断电 → 拆 BOOT0 短接 → 再上电** 才能跑用户代码。

---

## 如何验证程序在跑

按优先级排查：

| 现象 | 含义 | 解决 |
|------|------|------|
| LED 完全不闪 | 程序根本没跑 / BOOT0 没拔 / 卡 `APP_ErrorHandler` | 拆 PF4 短接、重新上电；或换 LED 引脚（改 `Inc/main.h`） |
| LED 闪 + XCOM 无数据 | 接线方向反了 / 波特率不对 | USB-TTL 的 **TX 接 PA3，RX 接 PA2**，XCOM 选 **9600 8-N-1** |
| LED 闪 + XCOM 只有横幅没有 `[DHT11]` | DHT11 没接好 / 没上拉电阻 | 检查 PA0 → DHT11 DATA、4.7kΩ 到 3V3、VCC/GND |
| LED 闪 + XCOM 看到 `[DHT11] T=.. H=..` ✅ | 一切正常 | 拔 USB-TTL，接 WBR3，进入模式③ |


---

## 编译和烧录步骤

```
1. 在 VS Code 中打开 EIDE 侧边栏（或用 Keil 打开 .uvprojx）
2. 确认已完成上面三项配置：PID + 烧录器 + 调试开关 (DEBUG_UART_PRINTF=1)
3. Ctrl+Shift+B 编译，生成 .hex
4. ISP 方式：按"ISP 烧录完整流程"那一节操作
   DAPLink 方式：连 PA13/PA14/GND，EIDE 选 flash 任务
5. 烧录完务必 → 断电 → 拆 BOOT0 短接 → 再上电
6. 看到 LED 闪 + XCOM 输出 = 完成 ✅
```

> **调试模式 vs 联网模式快速切换：**
> - 想看 XCOM 文本日志：保持 `DEBUG_UART_PRINTF=1`，PA2/PA3 接 USB-TTL，**不接 WBR3**。
> - 想用涂鸦 App 看：可以保持 `DEBUG_UART_PRINTF=1`（涂鸦 SDK 同时收发不受影响），
>   也可改成 `0` 减少 Flash 占用；PA2/PA3 接 WBR3，**拔掉 USB-TTL**。

---

## 首次配网步骤（手机 App）

```
1. 下载"涂鸦智能"或"Smart Life" App
2. 手机连接 2.4GHz WiFi（WBR3 不支持 5GHz！）
3. PY32 + WBR3 上电，等待握手完成（约 3 秒）
4. App → 添加设备 → 自动发现（或扫描二维码）
5. 输入 WiFi 密码，等待配网完成
6. 设备出现在 App 首页，显示温湿度数值
7. 每 30 秒自动刷新一次数据
```

---

## 涂鸦官方 MCU SDK 集成说明

当前 `tuya_port.c` 是本项目自己实现的协议层，功能完整，**无需官方 SDK 也能正常运行**。

如果你后来在 [iot.tuya.com](https://iot.tuya.com) 下载到了官方 MCU SDK，可按以下步骤替换：

**① 下载 SDK**
> iot.tuya.com → 产品管理 → 你的产品 → 硬件开发 → MCU SDK → 下载 C 语言版本

**② 解压到项目目录，典型文件：**
```
tuya_mcu_sdk/
├── protocol.c / protocol.h    ← 涂鸦串口协议核心
├── mcu_api.c / mcu_api.h      ← DP 上报/接收 API
└── system.c                   ← 系统回调接口
```

**③ 在 EIDE 中添加 SDK 文件**
> 在 eide.yml 的 virtualFolder 里新建一个 `Tuya_SDK` 分组，加入上面的 `.c` 文件。
> incList 里添加 SDK 的 `Inc/` 路径。

**④ 实现 SDK 要求的硬件回调**（在 `uart.c` 中添加）：
```c
// SDK 会调用这个函数发送数据，你负责把数据从串口发出去
void uart_transmit_output(unsigned char value)
{
    UART1_SendByte(value);
}
```

**⑤ 替换 tuya_port.c 中的上报调用**（用 SDK API 替换自实现的 SendFrame）：
```c
// 原来自实现的方式（不变也行）：
SendFrame(CMD_REPORT_DP, payload, 8U);

// 改用 SDK 的方式：
mcu_dp_value_update(DP_ID_TEMPERATURE, temp_c);
mcu_dp_value_update(DP_ID_HUMIDITY, humi_pct);
```

---

## DHT11 `read FAIL` 深度诊断（接线没问题也会失败的原因）

> 现象：XCOM 反复打印
> ```
> [DHT11] read FAIL stage=4 raw=00 00 00 00 00 (...)
> ```
> VCC/DATA/GND 都接对了、上拉电阻也对了，**仍然失败 ≠ 你接错了**。
> 90% 是软件层面 µs 时序问题，不是硬件问题。

### `stage` 字段含义

main.c 失败时会打印 `stage=N`，N 的含义（在 [`dht11.c`](User/dht11.c) 顶部注释）：

| stage | 失败位置 | 90% 真实原因 |
|-------|----------|--------------|
| **1** | 主机拉低 20ms 释放后，DHT11 一直没拉低应答 | DATA 接错 / 没供电 / 传感器死了 / 5V 模块只接了 3V3 |
| **2** | 应答低电平结束等不到 | 传感器异常或被强干扰 |
| **3** | 应答高电平结束等不到 | 同上 |
| **4** | 读 bit 时，每个 bit 的前导 50µs 低电平等不到结束 | **µs 时序漂移**（最常见，与硬件无关） |
| **5** | bit=1 的 70µs 高电平等不到结束 | **µs 时序漂移**（最常见） |
| **6** | 40 bit 都读到了但校验和不对 | 时序刚好让某些 bit 抖动，干扰强 / 线太长 |

### 为什么"接线没问题"还会失败？

DHT11 的 1 个 bit 时间窗只有 ~26µs(0) vs ~70µs(1)，**40µs 这条线区分 0/1**。
要可靠区分这两种脉冲，MCU 在 40µs 内必须能完成"轮询读引脚 + 判断 + 计时"。

| 干扰源 | 后果 | 修复 |
|--------|------|------|
| **系统时钟太低（如 8MHz HSI）** | `HAL_GPIO_ReadPin` 一次就要 5–10µs，整个读循环节拍变粗，bit 误判 → `stage=4/5` 频发 | 改 24MHz HSI（**本工程已修复** ✅）：`SystemClock_Config()` 里 `HSICalibrationValue = RCC_HSICALIBRATION_24MHz` |
| **SysTick 中断每 1ms 抢占** | 中断进入要 5–10µs，正好打断 40µs 采样窗口 → 那个 bit 必错 | 读取期间 `__disable_irq()`，读完 `__enable_irq()`（**本工程已修复** ✅） |
| **USART1 接收中断每字节抢占** | 同上 | 同上：临界区禁中断；约 5ms 临界区内丢几个 WBR3 字节没关系，涂鸦协议会重发 |
| **printf 调试输出本身慢** | 大段 printf 期间不能调 DHT11_Read | 没问题：DHT11_Read 只在 30s 间隔的瞬间执行 |
| **5V 模块挂 3V3** | DHT11 上拉电平不足，无法稳定输出高 | 模块标 `VCC 5V` 的请用 USB-TTL 的 5V 引脚 |
| **接线太长（>30cm）** | 上升沿被电容拖慢，bit=1 高电平的边沿模糊 | 缩短到 <20cm；或换 4.7kΩ → 2.2kΩ 上拉（裸件） |
| **首次上电立刻读** | DHT11 上电稳定时间 ~1s，第一次读经常没应答 | main.c 启动后 `HAL_Delay(2000)` 再读（**本工程已修复** ✅） |

### 本次代码已修复的三件事（2026-06-01）

1. **时钟从 8MHz → 24MHz HSI**：`User/main.c` 的 `SystemClock_Config()`
2. **DHT11 临界区禁中断**：`User/dht11.c` 在 5ms 位读取段 `__disable_irq()/__enable_irq()`
3. **诊断信息加强**：失败时打印 `stage=` + `raw=` 5 字节，外加启动延时 2s + 启动后立刻读一次

### 修复后预期 XCOM 输出

```
[BOOT] PY32+WBR3+DHT11 demo build Jun  1 2026 23:55:00
[BOOT] SystemCoreClock = 24000000 Hz (expect 24000000)   ← 必须是 24M，不是 8M
[BOOT] DHT11 DATA idle high = 10/10 (expect 10; <10 = HW problem)  ← 关键诊断！
[DHT11] T=25 C, H=60 %RH                                 ← 2s 后第一行，然后每 30s 一行
[DHT11] T=25 C, H=61 %RH
```

### 第二行 `idle high = N/10` 的含义（一秒判断硬件是否 OK）

| 输出 | 含义 | 结论 |
|------|------|------|
| `idle high = 10/10` | PA0 稳定高电平 → DHT11 上电、接线、上拉都对 | 硬件没问题；如果还 FAIL → 看 stage |
| `idle high = 0/10`  | PA0 被强拉低 | DATA **短路到 GND** / DHT11 内部坏短 / **DATA 接错引脚**（接到了另一个被设成低输出的脚） |
| `idle high = 1~9/10`| PA0 浮动（无驱动源） | DATA 线**根本没接通**（虚焊 / 引脚错） **或 DHT11 没上电**（VCC/GND 没接） |

> ⚠️ **XCOM 不支持 UTF-8，源码中的 printf 字符串必须用纯 ASCII**
> 如果在 printf 里写了中文，XCOM(GBK) 会显示 `鎺ョ嚎/渚涚數` 这种乱码。
> 本工程所有 printf 已改为纯英文。**你自己添加调试输出时也不要写中文**。

### 出现 `stage=1 raw=00 00 00 00 00` 怎么办（硬件层排查清单）

`stage=1` 意味着 DHT11 完全没应答。**不是软件能修的**，按下表逐条排查（按"最常踩"排序）：

| 排查项 | 检查方法 | 修复 |
|--------|----------|------|
| **🔥 ⓪ GND 没接 / 接错引脚（实测最常见）** | 3 脚模块 R1 只需 VCC 就能拉高 DATA → `idle=10/10` 会骗你以为接对了；但 DHT11 芯片需要 GND 才工作 | 万用表蜂鸣档测 DHT11 的 `-`/`G` 引脚 ↔ PY32 GND 是否短路；不通就重接。**先按模块丝印 S/V/G 或 S/+/- 确认引脚顺序，不要按颜色猜！** |
| **① 模块要 5V，你接了 3V3** | 看 DHT11 模块丝印；蓝色带 PCB 模块大多标 `VCC: 5V` | 把 DHT11 的 VCC 接到 USB-TTL 的 **5V 引脚**（不是 3V3），DATA 信号 PY32 这边 3.3V 是兼容的 |
| **② USB-TTL 的 3V3 电流不够** | CH340G/E 的 3V3 输出只有 ~30mA，带 PY32 + DHT11 会跌压 | 用独立 3.3V 电源；或直接用 PY32 板上的 3V3 引脚给 DHT11 供电 |
| **③ 引脚顺序记错** | **不要假设**模块的 VCC/DATA/GND 顺序，看丝印 | 不同厂家顺序不一样（常见 VCC-DATA-GND 也有 DATA-VCC-GND） |
| **④ GND 没共地** | 万用表打蜂鸣档测 DHT11 GND ↔ PY32 GND | 任何信号传输都必须共地，只共 VCC 没用 |
| **⑤ DATA 实际没接到 PA0** | 万用表蜂鸣档测 DHT11 DATA ↔ PY32 板上 PA0 焊点 | 重接 |
| **⑥ 野火板 PA0 已被板载电路占用** | 查规格书 PDF "管脚分配" / "板载外设" 节 | 把 DHT11 换到 PA1 / PA4 / PA5 / PA6 / PA7 等空闲 IO，同步改 `Inc/dht11.h` 的 `DHT11_GPIO_PIN` 宏 |
| **⑦ DHT11 本身坏了** | 换一颗试 | DHT11 很便宜，被反插/雷击过就死了 |
| **⑧ 上拉电阻缺失（4 脚裸件才有这问题）** | 看引脚数：3 脚 = 已自带；4 脚 = 必须外加 | 4 脚裸件加 4.7kΩ DATA→3V3 |

**最快验证：** 把 DHT11 VCC 从 3V3 改接到 USB-TTL 的 5V，其余不变，重新上电观察 XCOM。
80% 的"接线没问题但 stage=1"是供电问题（A 或 B）。

---



| 问题 | 可能原因 | 解决方法 |
|------|----------|----------|
| **ISP Tool 显示 `Connect PASS` 但板子没反应** | BOOT0(PF4) 还短接在 3V3，芯片重启又进 BootROM | 断电 → 拆 PF4↔3V3 跳线 → 再上电 |
| **LED 不闪** | ① BOOT0 没拔 ② LED 引脚配错 ③ 时钟卡死 | ① 先排除 BOOT0；② 改 `Inc/main.h` 的 `LED_GPIO_PORT/PIN`（野火 K28 板载 LED 真实引脚见规格书 PDF "LED 电路" 节）；③ Keil 在 `APP_ErrorHandler` 打断点 |
| **XCOM 完全无数据** | ① TX/RX 接反 ② 波特率不是 9600 ③ 没勾 MicroLIB（Keil） | ① USB-TTL **TX → PA3，RX → PA2** ② 改 9600 8-N-1 ③ Keil: Options→Target→Use MicroLIB 打勾 |
| **XCOM 看到一堆乱码（高位字节）** | 波特率不匹配 | 改 XCOM 为 **9600**；本工程不再用 115200 |
| **XCOM 看到 `[BOOT]` 但收不到 `[DHT11]`** | DHT11 接线或上拉电阻问题 | PA0 → DATA、4.7kΩ → 3V3、VCC=3V3、GND 共地 |
| **`idle=10/10` 但 `stage=1` 一直 FAIL（接对了 VCC/DATA 但没温度）** | **GND 接错引脚或没接通**！3 脚 DHT11 模块的 R1 只需 VCC 通电就能拉高 DATA → idle=10/10，但 DHT11 芯片本身没 GND 回路不工作 → stage=1 | 用万用表蜂鸣档测 DHT11 的 `-`/`G` 引脚 ↔ PY32 GND 是否短路；不通就重接（实测案例 2026-06-02：用户卡在这一步，重接 GND 后立刻读到 T=30°C H=59%RH） |
| **`[DHT11] FAIL stage=N` 一直打印** | N=1 是硬件（看上一行，最容易踩 GND 没接、5V 模块挂 3V3、USB-TTL 3V3 带不动）；N=4/5 是 µs 时序漂移；N=6 是干扰 | 看上面"DHT11 read FAIL 深度诊断"和"出现 stage=1 怎么办"清单 |
| **XCOM 看到 `鎺ョ嚎/渚涚數` 类乱码** | 源码 printf 字符串含中文，XCOM 按 GBK 解码 UTF-8 出错 | 把所有 printf 字符串改成纯英文 ASCII（本工程已修复）；XCOM 没有 UTF-8 选项 |
| **`SystemCoreClock` 打印不是 24000000** | `SystemClock_Config` 没生效 / SDK 老版本不支持 24MHz 校准 | 重新编译；确认 SDK 版本 ≥ V1.5.0；HAL 库报错就改回 `RCC_HSICALIBRATION_8MHz` 并接受 DHT11 偶尔失败 |
| **手上 DHT11 不知道要不要加电阻** | 分不清裸件 vs 模块版 | 3 脚带 PCB 小板 = 已自带上拉直接接；4 脚蓝壳裸件 = 必须 DATA→3V3 加 4.7kΩ；万用表测 VCC↔DATA 在 3k~10k 即说明已上拉 |
| **"我板子没有 PA9 啊？"** | 把 STM32F1 教程套用到了 PY32 | PY32F030K28 没有 PA9/PA10，USART1 在 **PA2(TX)/PA3(RX)** |
| **App 看不到设备** | WBR3 握手失败 / PA2/PA3 还接着 USB-TTL | 拔掉 USB-TTL 后再接 WBR3；TX/RX 交叉；PID 填对 |
| **WiFi 配不上** | 用了 5GHz 频段 | 手机改连 2.4GHz WiFi |
| **数据不更新** | 云连接状态未到 0x05 | 等 30 秒或重启，观察 XCOM 中收到的帧 |
| **Keil 编译报 `__use_no_semihosting` undefined** | 没勾 MicroLIB，或用了 AC5 还触发了 AC6 分支 | Options→Target→Use MicroLIB 打勾；或把 `DEBUG_UART_PRINTF` 改成 0 |

---

## 参考资料

- [PY32F0xx 官方 SDK（GitHub）](https://github.com/OpenPuya/PY32F0xx_Firmware)
- [涂鸦 IoT 平台](https://iot.tuya.com)
- [涂鸦 MCU SDK 接入文档](https://developer.tuya.com/cn/docs/iot/mcu-sdk-architecture?id=K9duaegcz2ycd)
- [DHT11 数据手册](https://www.mouser.com/datasheet/2/758/DHT11-Technical-Data-Sheet-Translated-Version-1143054.pdf)
