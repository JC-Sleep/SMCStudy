# Shell 脚本中 Sleep 重试的最佳实践

## 当前代码分析

### 问题代码模式
```bash
retry_time=1
while true
do
   if [ ${retry_time} -eq 3 ]
   then
      exit
   fi
   retry_time=`expr ${retry_time} \+ 1`
   sleep 600  # 潜在问题：10分钟太长
   get_subr_info_file
done
```

### 总等待时间
- **第1次失败**: sleep 600秒 (10分钟)
- **第2次失败**: sleep 600秒 (10分钟)
- **第3次失败**: 直接退出
- **总计**: 最多等待 **20分钟** (中间两次sleep)

---

## 潜在风险详解

### 1. 脚本不会"死"，但会有这些问题：

#### ❌ 风险1：阻塞后续任务
```bash
# 如果这个脚本在 cron 中每小时运行一次
0 * * * * /path/to/download_custpro_cmd_nb.sh

# 如果文件获取失败，脚本会运行 20+ 分钟
# 可能与下一次调度冲突
```

#### ❌ 风险2：缺少超时保护
```bash
# 当前代码没有总体超时限制
# 理论上最长运行时间 = 文件处理时间 + 20分钟等待
```

#### ❌ 风险3：信号处理缺失
```bash
# 如果在 sleep 期间手动终止脚本
# 可能留下不完整的文件或锁文件
```

#### ❌ 风险4：资源占用
```bash
# 进程会一直存在，占用：
# - 进程ID (PID)
# - 文件描述符
# - 少量内存
# 对于单个脚本影响不大，但多实例运行会累积
```

---

## 改进方案

### 方案1：优化当前重试逻辑（推荐）

```bash
#!/bin/ksh

# 配置参数
MAX_RETRY=3
RETRY_INTERVAL=300  # 缩短到5分钟
MAX_TOTAL_WAIT=1800 # 总等待时间不超过30分钟

# 信号处理
cleanup() {
    echo "Script interrupted at `date`" >> ${logf}
    exit 130
}
trap cleanup INT TERM

# 改进的重试逻辑
get_file_with_retry() {
    local file_path=$1
    local retry_count=0
    local start_time=$(date +%s)
    
    while [ ${retry_count} -lt ${MAX_RETRY} ]; do
        echo "`date`: Attempt $((retry_count + 1)) of ${MAX_RETRY}" >> ${logf}
        
        get_subr_info_file
        
        if [ -s "${file_path}" ]; then
            echo "`date`: Successfully retrieved file" >> ${logf}
            return 0
        fi
        
        retry_count=$((retry_count + 1))
        
        # 检查总等待时间
        current_time=$(date +%s)
        elapsed=$((current_time - start_time))
        
        if [ ${elapsed} -ge ${MAX_TOTAL_WAIT} ]; then
            echo "ERROR: Total wait time exceeded ${MAX_TOTAL_WAIT} seconds" >> ${logf}
            return 1
        fi
        
        # 如果还有重试机会，则等待
        if [ ${retry_count} -lt ${MAX_RETRY} ]; then
            echo "Waiting ${RETRY_INTERVAL} seconds before retry..." >> ${logf}
            sleep ${RETRY_INTERVAL}
        fi
    done
    
    echo "ERROR: Failed after ${MAX_RETRY} attempts" >> ${logf}
    return 1
}

# 使用示例
if ! get_file_with_retry "${SUBR_INFO_FILE}"; then
    trigger_email_alert "[download_custpro_cmd_nb.sh] Failed to get file" $MAILLIST
    exit 1
fi
```

### 方案2：指数退避（适合网络问题）

```bash
#!/bin/ksh

# 指数退避重试
exponential_backoff_retry() {
    local max_attempts=5
    local base_wait=30  # 从30秒开始
    local max_wait=600  # 最多等待10分钟
    local attempt=1
    
    while [ ${attempt} -le ${max_attempts} ]; do
        echo "Attempt ${attempt}/${max_attempts}" >> ${logf}
        
        get_subr_info_file
        
        if [ -s "${SUBR_INFO_FILE}" ]; then
            return 0
        fi
        
        if [ ${attempt} -lt ${max_attempts} ]; then
            # 计算等待时间: base_wait * 2^(attempt-1)
            wait_time=$((base_wait * (1 << (attempt - 1))))
            
            # 限制最大等待时间
            if [ ${wait_time} -gt ${max_wait} ]; then
                wait_time=${max_wait}
            fi
            
            echo "Waiting ${wait_time} seconds (exponential backoff)..." >> ${logf}
            sleep ${wait_time}
        fi
        
        attempt=$((attempt + 1))
    done
    
    return 1
}
```

### 方案3：使用 timeout 命令（最简单）

```bash
#!/bin/bash

# 为整个操作设置超时（需要 bash 和 timeout 命令）
timeout 1800 bash -c '
    retry_count=0
    while [ ${retry_count} -lt 3 ]; do
        get_subr_info_file
        if [ -s "${SUBR_INFO_FILE}" ]; then
            exit 0
        fi
        retry_count=$((retry_count + 1))
        [ ${retry_count} -lt 3 ] && sleep 600
    done
    exit 1
'

if [ $? -eq 124 ]; then
    echo "ERROR: Operation timed out after 30 minutes" >> ${logf}
    exit 1
fi
```

### 方案4：后台运行 + 定时检查

```bash
#!/bin/ksh

# 后台等待，主进程定期检查
wait_for_file_with_timeout() {
    local file_path=$1
    local timeout=1800  # 30分钟超时
    local check_interval=30  # 每30秒检查一次
    local elapsed=0
    
    while [ ${elapsed} -lt ${timeout} ]; do
        if [ -s "${file_path}" ]; then
            echo "File available after ${elapsed} seconds" >> ${logf}
            return 0
        fi
        
        sleep ${check_interval}
        elapsed=$((elapsed + check_interval))
        
        # 每5分钟尝试重新获取
        if [ $((elapsed % 300)) -eq 0 ]; then
            echo "Re-attempting file transfer at ${elapsed}s..." >> ${logf}
            get_subr_info_file &
        fi
    done
    
    echo "ERROR: Timeout waiting for file" >> ${logf}
    return 1
}
```

---

## 最佳实践建议

### ✅ DO - 应该做的

1. **设置合理的重试间隔**
   - 网络问题: 30-60秒足够
   - 文件生成等待: 300-600秒合理
   - **避免低于10秒**（可能被视为攻击）

2. **添加总超时控制**
   ```bash
   MAX_SCRIPT_RUNTIME=3600  # 脚本最多运行1小时
   ```

3. **实现信号处理**
   ```bash
   trap cleanup INT TERM EXIT
   ```

4. **记录详细日志**
   ```bash
   echo "`date`: Retry ${retry_count}, waited ${total_wait}s" >> ${logf}
   ```

5. **使用指数退避**
   - 第1次: 30秒
   - 第2次: 60秒
   - 第3次: 120秒
   - 第4次: 240秒
   - 第5次: 300秒（上限）

### ❌ DON'T - 不应该做的

1. **不要无限重试**
   ```bash
   # 危险！可能永远运行
   while true; do
       sleep 600
   done
   ```

2. **不要在 sleep 前不检查条件**
   ```bash
   # 错误：即使成功也会 sleep
   get_file
   sleep 600
   ```

3. **不要忽略脚本运行时间**
   - 如果通过 cron 调度，确保执行间隔 > 最大运行时间

4. **不要在生产环境使用过长的 sleep**
   - 600秒是极限，建议 ≤ 300秒

---

## 针对您的代码的具体建议

### 当前问题
```bash
# 第293-322行的重试逻辑
retry_time=1
while true
do
   if ( test -s ${SUBR_INFO_FILE})
   then
      break
   else
      if [ ${retry_time} -eq 3 ]
      then
         trigger_email_alert "..."
         exit
      fi
      retry_time=`expr ${retry_time} \+ 1`
      sleep 600  # ⚠️ 问题：每次都等10分钟
      get_subr_info_file
   fi
done
```

### 建议修改
```bash
# 改进版本
MAX_RETRY=3
RETRY_INTERVAL=300  # 减少到5分钟
retry_time=1

while true
do
   if ( test -s ${SUBR_INFO_FILE})
   then
      echo "`date`: Successfully get ${SUBR_INFO_FILE}" >> ${logf}
      break
   else
      echo "`date`: Retry ${retry_time}/${MAX_RETRY}" >> ${logf}
      
      if [ ${retry_time} -ge ${MAX_RETRY} ]
      then
         echo "ERROR: Failed after ${MAX_RETRY} attempts" >> ${logf}
         trigger_email_alert "[download_custpro_cmd_nb.sh] File retrieval failed" $MAILLIST
         exit 1
      fi
      
      retry_time=$((retry_time + 1))
      
      # 只在还有重试机会时才 sleep
      if [ ${retry_time} -le ${MAX_RETRY} ]; then
          echo "Waiting ${RETRY_INTERVAL} seconds..." >> ${logf}
          sleep ${RETRY_INTERVAL}
      fi
      
      get_subr_info_file
   fi
done
```

---

## 严重后果总结

### 🔴 严重级别：中等

#### 不会发生的：
- ✅ 脚本不会"死"或"挂死"
- ✅ 系统不会崩溃
- ✅ 不会造成内存泄漏

#### 可能发生的：
- ⚠️ 阻塞后续批处理任务
- ⚠️ cron 任务堆积（如果执行间隔 < 脚本运行时间）
- ⚠️ 监控告警误报（进程运行时间过长）
- ⚠️ 资源浪费（进程长时间空转）

#### 实际影响：
```
最坏情况时间线：
00:00 - 脚本启动
00:05 - 第1次获取文件失败
00:15 - Sleep 600秒 (10分钟)
00:15 - 第2次获取文件失败  
00:25 - Sleep 600秒 (10分钟)
00:25 - 第3次获取文件失败
00:25 - 退出并告警

总运行时间：约 25 分钟
```

如果您的 cron 是每30分钟或每小时运行，这个设计是**安全**的。
如果是每15分钟运行，可能会有**重叠风险**。

---

## 快速检查清单

- [ ] 重试次数是否合理？ (建议 3-5 次)
- [ ] Sleep 间隔是否合理？ (建议 ≤ 300秒)
- [ ] 是否有总超时控制？
- [ ] 是否有信号处理？
- [ ] 是否记录了重试日志？
- [ ] 是否考虑了 cron 调度间隔？
- [ ] 是否有告警机制？ ✅ (您已实现)
- [ ] 是否在最后一次重试后避免 sleep？

---

## 监控建议

```bash
# 添加执行时间监控
SCRIPT_START=$(date +%s)

# ... 脚本主逻辑 ...

SCRIPT_END=$(date +%s)
DURATION=$((SCRIPT_END - SCRIPT_START))

echo "Script completed in ${DURATION} seconds" >> ${logf}

# 如果执行时间异常长，发送告警
if [ ${DURATION} -gt 1800 ]; then
    trigger_email_alert "Script execution time (${DURATION}s) exceeded threshold" $MAILLIST
fi
```

