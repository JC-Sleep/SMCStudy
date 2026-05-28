# PY32F030K28U6 + WBR3 + DHT11 涂鸦 IoT 温湿度 Demo

## 项目概述

本项目实现一个完整的物联网温湿度采集系统：

- **主控 MCU**：野火 PY32F030K28U6（ARM Cortex-M0+，64KB Flash，8KB RAM）
- **WiFi 模块**：涂鸦 WBR3（出厂已刷好涂鸦固件，**不需要写代码**）
- **传感器**：DHT11（单总线，温度精度 ±2°C，湿度精度 ±5% RH）
- **调试器**：国产 DAPLink（CMSIS-DAP 协议）+ USB-TTL

**整体数据流：**

```
DHT11 ──[单总线 PA0]──▶ PY32F030 ──[UART1 115200]──▶ WBR3 ──[WiFi]──▶ 涂鸦云 ──▶ 手机 App
```

---

## 硬件连接

### 完整接线图

```
┌─────────────────────────────────────────────────────────────────┐
│                     PY32F030K28U6                               │
│                                                                  │
│  PA0  ──[4.7kΩ上拉到3.3V]──┬── DHT11 DATA                      │
│                              └── 3.3V                           │
│                                                                  │
│  PA2  (USART1_TX, AF1) ──────────────── WBR3 RX                 │
│  PA3  (USART1_RX, AF1) ──────────────── WBR3 TX                 │
│                                                                  │
│  PA13 (SWDIO) ─────────────────────────── DAPLink SWDIO         │
│  PA14 (SWDCK) ─────────────────────────── DAPLink SWDCK         │
│                                                                  │
│  3.3V ──────────────────────────────────── WBR3 VCC             │
│  3.3V ──────────────────────────────────── DHT11 VCC            │
│  GND  ──────────────────────────────────── WBR3 GND             │
│  GND  ──────────────────────────────────── DHT11 GND            │
│  GND  ──────────────────────────────────── DAPLink GND          │
└─────────────────────────────────────────────────────────────────┘
```

### 引脚详细说明

| 引脚 | 功能 | 连接到 | 为什么这么接 |
|------|------|--------|-------------|
| **PA0** | GPIO 输出/输入动态切换 | DHT11 DATA | DHT11 单总线协议，主机需要主动拉低再释放 |
| **PA2** | USART1_TX（AF1） | WBR3 **RX** | 你的嘴（TX）对着对方的耳朵（RX），交叉接 |
| **PA3** | USART1_RX（AF1） | WBR3 **TX** | 对方的嘴（TX）接进你的耳朵（RX） |
| **PA13** | SWDIO | DAPLink SWDIO | SWD 调试协议数据线，烧录程序用 |
| **PA14** | SWDCK | DAPLink SWDCK | SWD 调试协议时钟线 |
| **4.7kΩ** | 上拉电阻 | PA0 → 3.3V | DHT11 DATA 是开漏输出，没有上拉电阻无法读数据 |

> **⚠️ 重要：WBR3 只能接 3.3V，接 5V 会烧模块！**

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

### 2. 修改 EIDE 烧录设置（从 JLink 改为 DAPLink）

你使用的是国产 DAPLink（CMSIS-DAP 协议），当前配置里写的是 JLink，**必须改**：

> EIDE 侧边栏 → 右键项目名 → **烧录设置（Upload Settings）**
> → Uploader 改为 **pyOCD**
> → Target 填入 `py32f030x8`

---

## 编译和烧录步骤

```
1. 在 VS Code 中打开 EIDE 侧边栏
2. 确认已完成上面两项配置（PID + 烧录器）
3. Ctrl+Shift+B → 选择 "build" 任务
4. 编译成功后 → 选择 "flash" 任务烧录
5. DAPLink 连接 PA13/PA14/GND，上电烧录
```

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

## 常见问题排查

| 问题 | 可能原因 | 解决方法 |
|------|----------|----------|
| 编译报错找不到头文件 | EIDE incList 路径不对 | 检查 eide.yml 中的 PY32 SDK 绝对路径 |
| 烧录失败 | 烧录器类型选错 | 改为 pyOCD，选 CMSIS-DAP 接口 |
| DHT11 一直返回 ERROR | 没有上拉电阻 / 供电不稳 | 检查 4.7kΩ 上拉，确认 3.3V 供电 |
| App 看不到设备 | WBR3 握手失败 | 检查 TX/RX 是否交叉接；检查 PID 是否正确 |
| WiFi 配不上 | 使用了 5GHz 频段 | 手机改连 2.4GHz WiFi |
| 数据不更新 | 云连接状态未到 0x05 | 等待 30 秒或重启设备，观察串口日志 |

---

## 参考资料

- [PY32F0xx 官方 SDK（GitHub）](https://github.com/OpenPuya/PY32F0xx_Firmware)
- [涂鸦 IoT 平台](https://iot.tuya.com)
- [涂鸦 MCU SDK 接入文档](https://developer.tuya.com/cn/docs/iot/mcu-sdk-architecture?id=K9duaegcz2ycd)
- [DHT11 数据手册](https://www.mouser.com/datasheet/2/758/DHT11-Technical-Data-Sheet-Translated-Version-1143054.pdf)
