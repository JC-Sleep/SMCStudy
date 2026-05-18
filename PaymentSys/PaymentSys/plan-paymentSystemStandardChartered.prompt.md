# Payment System with Standard Chartered Bank Integration

## Overview

Design and implement a SpringBoot payment gateway system integrating with Standard Chartered Bank, focusing on robust callback handling, transaction reconciliation, and preventing the "customer paid but order failed" scenario where timeout leads to payment inconsistency between bank and internal system.

## Problem Analysis

### Current Issue

Based on the log analysis from the case:
- **Date**: 2026-02-03 20:01:50
- **Order Reference**: CSP001xxxxx
- **User ID**: 33xxxx
- **Amount**: 176
- **Payment Status**: TIMEOUT
- **SID**: cs_acq

**The Dead-Lock Scenario:**
1. Customer sees "Payment Successful" on their end
2. System shows "Payment Failed" due to TIMEOUT
3. Bank confirms money was transferred to the company
4. Finance department says they haven't received the payment
5. Customer cannot get refund from bank, company has no record of successful payment

### Root Causes

1. **Callback Never Arrived**: Network issues between Standard Chartered and the payment system prevented the callback notification
2. **Callback Processing Failed**: Callback arrived but system failed to process it (exception, database lock, etc.)
3. **Timeout Threshold Too Short**: System marked transaction as TIMEOUT before bank could send confirmation
4. **No Reconciliation Mechanism**: System lacks automated way to verify payment status with the bank
5. **Missing Idempotency**: Duplicate callback handling could cause inconsistent states
6. **Lack of Transaction Audit Trail**: Insufficient logging to trace what happened

## Architecture Design

### Core Components

1. **Payment Gateway Client** - Interface with Standard Chartered Bank API
2. **Payment Service** - Business logic for payment processing
3. **Callback Handler** - Process bank callbacks with retry mechanism
4. **Reconciliation Job** - Scheduled task to sync payment status with bank
5. **Transaction State Machine** - Manage payment lifecycle
6. **Notification Service** - Alert on payment anomalies

### Payment Flow

```
Customer → Payment Request → Gateway → Standard Chartered Bank
                ↓                              ↓
          Store Transaction              Process Payment
                ↓                              ↓
          Return Pending                 Send Callback
                ↓                              ↓
        Poll Status (if needed) ← Callback Handler
                ↓
          Update Transaction
                ↓
        Notify Customer/Order System
```

## Steps

### Step 1: Setup Project Structure and Dependencies

**File**: `PaymentSys/pom.xml`

Add dependencies for:
- Spring Boot Starter Web
- Spring Boot Starter Data JPA
- MyBatis-Plus
- Oracle JDBC Driver
- Spring Boot Starter Validation
- Spring Boot Starter AOP (for logging)
- Spring Retry
- Apache HttpClient (for API calls)
- Jackson (JSON processing)
- Lombok
- Spring Boot Starter Test

### Step 2: Design Database Schema

**File**: `PaymentSys/src/main/resources/db/schema.sql`

Create tables:

#### 2.1 `PAYMENT_TRANSACTION` Table
```sql
-- Main transaction table with complete lifecycle tracking
CREATE TABLE PAYMENT_TRANSACTION (
    ID NUMBER(19) PRIMARY KEY,
    TRANSACTION_ID VARCHAR2(100) NOT NULL UNIQUE,      -- Our internal transaction ID
    IDEMPOTENCY_KEY VARCHAR2(200) NOT NULL UNIQUE,     -- order_ref + timestamp for dedup
    ORDER_REFERENCE VARCHAR2(100) NOT NULL,             -- Customer order reference (e.g., CSP001xxxxx)
    
    -- Customer Info
    USER_ID VARCHAR2(50),
    SUBR_NUM VARCHAR2(50),
    CUSTOMER_NAME VARCHAR2(200),
    
    -- Payment Details
    AMOUNT NUMBER(10,2) NOT NULL,
    CURRENCY VARCHAR2(3) DEFAULT 'HKD',
    PAYMENT_METHOD VARCHAR2(50),                        -- CARD, ALIPAY, etc.
    
    -- Gateway Info
    GATEWAY_NAME VARCHAR2(50) DEFAULT 'STANDARD_CHARTERED',
    GATEWAY_TRANSACTION_ID VARCHAR2(200),               -- Bank's transaction ID
    GATEWAY_ORDER_NO VARCHAR2(200),                     -- Bank's order number
    
    -- Status Management
    PAYMENT_STATUS VARCHAR2(20) NOT NULL,               -- INIT, PENDING, SUCCESS, FAILED, TIMEOUT, REFUNDED
    PREVIOUS_STATUS VARCHAR2(20),                       -- For audit trail
    STATUS_UPDATE_TIME DATE,
    
    -- ECR and Environment
    ECR_ID VARCHAR2(100),                               -- Point of Sale ID (e.g., W30000000718xxxx)
    SID VARCHAR2(50),                                   -- Session ID (e.g., cs_acq)
    ENVIRONMENT VARCHAR2(20) DEFAULT 'PROD',            -- PROD, UAT, DEV
    
    -- Callback Info
    CALLBACK_RECEIVED NUMBER(1) DEFAULT 0,              -- 0=No, 1=Yes
    CALLBACK_COUNT NUMBER(5) DEFAULT 0,                 -- Number of callbacks received
    LAST_CALLBACK_TIME DATE,
    
    -- Reconciliation
    RECONCILIATION_STATUS VARCHAR2(20),                 -- MATCHED, MISMATCH, PENDING
    RECONCILIATION_TIME DATE,
    LAST_QUERY_TIME DATE,                               -- Last time we queried bank status
    
    -- Audit Trail
    VERSION NUMBER(10) DEFAULT 0,                       -- Optimistic locking
    CREATE_TIME DATE DEFAULT SYSDATE NOT NULL,
    UPDATE_TIME DATE DEFAULT SYSDATE NOT NULL,
    CREATE_USER VARCHAR2(100),
    UPDATE_USER VARCHAR2(100),
    
    -- Additional Info
    REMARKS VARCHAR2(2000),
    ERROR_MESSAGE VARCHAR2(2000),
    
    CONSTRAINT CHK_PAYMENT_STATUS CHECK (PAYMENT_STATUS IN ('INIT', 'PENDING', 'SUCCESS', 'FAILED', 'TIMEOUT', 'REFUNDED', 'RECONCILING'))
);

-- Indexes for performance
CREATE INDEX IDX_PT_ORDER_REF ON PAYMENT_TRANSACTION(ORDER_REFERENCE);
CREATE INDEX IDX_PT_GATEWAY_TXN ON PAYMENT_TRANSACTION(GATEWAY_TRANSACTION_ID);
CREATE INDEX IDX_PT_STATUS ON PAYMENT_TRANSACTION(PAYMENT_STATUS);
CREATE INDEX IDX_PT_CREATE_TIME ON PAYMENT_TRANSACTION(CREATE_TIME);
CREATE INDEX IDX_PT_USER_ID ON PAYMENT_TRANSACTION(USER_ID);
```

#### 2.2 `PAYMENT_CALLBACK_LOG` Table
```sql
-- Store ALL callback attempts for audit and debugging
CREATE TABLE PAYMENT_CALLBACK_LOG (
    ID NUMBER(19) PRIMARY KEY,
    TRANSACTION_ID VARCHAR2(100) NOT NULL,
    CALLBACK_TIME DATE DEFAULT SYSDATE NOT NULL,
    
    -- Request Info
    CALLBACK_URL VARCHAR2(500),
    REQUEST_METHOD VARCHAR2(10),
    REQUEST_HEADERS CLOB,
    REQUEST_BODY CLOB,
    REQUEST_IP VARCHAR2(50),
    
    -- Response Info
    PROCESSING_STATUS VARCHAR2(20),                     -- SUCCESS, FAILED, DUPLICATE
    PROCESSING_TIME_MS NUMBER(10),
    ERROR_MESSAGE VARCHAR2(2000),
    
    -- Signature Verification
    SIGNATURE_VALID NUMBER(1),                          -- 0=No, 1=Yes
    SIGNATURE_VALUE VARCHAR2(500),
    
    -- Gateway Info
    GATEWAY_STATUS VARCHAR2(50),
    GATEWAY_MESSAGE VARCHAR2(1000),
    GATEWAY_TRANSACTION_ID VARCHAR2(200),
    
    -- Audit
    CREATE_TIME DATE DEFAULT SYSDATE NOT NULL,
    
    CONSTRAINT FK_PCL_TRANSACTION FOREIGN KEY (TRANSACTION_ID) 
        REFERENCES PAYMENT_TRANSACTION(TRANSACTION_ID)
);

CREATE INDEX IDX_PCL_TXN_ID ON PAYMENT_CALLBACK_LOG(TRANSACTION_ID);
CREATE INDEX IDX_PCL_CALLBACK_TIME ON PAYMENT_CALLBACK_LOG(CALLBACK_TIME);
```

#### 2.3 `PAYMENT_RECONCILIATION` Table
```sql
-- Daily reconciliation records
CREATE TABLE PAYMENT_RECONCILIATION (
    ID NUMBER(19) PRIMARY KEY,
    RECONCILIATION_DATE DATE NOT NULL,
    RECONCILIATION_TYPE VARCHAR2(50),                   -- DAILY, MANUAL, AUTO_RETRY
    
    -- Statistics
    TOTAL_TRANSACTIONS NUMBER(10),
    MATCHED_COUNT NUMBER(10),
    MISMATCH_COUNT NUMBER(10),
    TIMEOUT_COUNT NUMBER(10),
    PENDING_COUNT NUMBER(10),
    
    -- Status
    RECONCILIATION_STATUS VARCHAR2(20),                 -- IN_PROGRESS, COMPLETED, FAILED
    START_TIME DATE,
    END_TIME DATE,
    
    -- Details
    MISMATCH_TRANSACTION_IDS CLOB,                      -- JSON array of mismatched IDs
    ERROR_MESSAGE VARCHAR2(2000),
    
    -- Audit
    CREATE_TIME DATE DEFAULT SYSDATE NOT NULL,
    CREATE_USER VARCHAR2(100),
    
    CONSTRAINT CHK_RECON_STATUS CHECK (RECONCILIATION_STATUS IN ('IN_PROGRESS', 'COMPLETED', 'FAILED'))
);

CREATE INDEX IDX_PR_DATE ON PAYMENT_RECONCILIATION(RECONCILIATION_DATE);
```

#### 2.4 `PAYMENT_ORDER_MAPPING` Table
```sql
-- Link payment transactions to business orders
CREATE TABLE PAYMENT_ORDER_MAPPING (
    ID NUMBER(19) PRIMARY KEY,
    TRANSACTION_ID VARCHAR2(100) NOT NULL,
    ORDER_REFERENCE VARCHAR2(100) NOT NULL,
    
    -- Order Info
    ORDER_TYPE VARCHAR2(50),                            -- ACQUISITION, RENEWAL, TOP_UP
    ORDER_STATUS VARCHAR2(20),                          -- CREATED, CONFIRMED, FAILED
    ORDER_AMOUNT NUMBER(10,2),
    
    -- Confirmation
    CONFIRMED NUMBER(1) DEFAULT 0,                      -- 0=No, 1=Yes
    CONFIRM_TIME DATE,
    CONFIRM_USER VARCHAR2(100),
    
    -- Audit
    CREATE_TIME DATE DEFAULT SYSDATE NOT NULL,
    UPDATE_TIME DATE DEFAULT SYSDATE NOT NULL,
    
    CONSTRAINT FK_POM_TRANSACTION FOREIGN KEY (TRANSACTION_ID) 
        REFERENCES PAYMENT_TRANSACTION(TRANSACTION_ID),
    CONSTRAINT UK_POM_ORDER UNIQUE (ORDER_REFERENCE, TRANSACTION_ID)
);

CREATE INDEX IDX_POM_TXN_ID ON PAYMENT_ORDER_MAPPING(TRANSACTION_ID);
CREATE INDEX IDX_POM_ORDER_REF ON PAYMENT_ORDER_MAPPING(ORDER_REFERENCE);
```

#### 2.5 `PAYMENT_CONFIG` Table
```sql
-- Configuration for payment gateway
CREATE TABLE PAYMENT_CONFIG (
    ID NUMBER(19) PRIMARY KEY,
    CONFIG_KEY VARCHAR2(100) NOT NULL UNIQUE,
    CONFIG_VALUE VARCHAR2(2000),
    CONFIG_TYPE VARCHAR2(50),                           -- STRING, NUMBER, JSON, ENCRYPTED
    DESCRIPTION VARCHAR2(500),
    ENVIRONMENT VARCHAR2(20),                           -- PROD, UAT, DEV
    ENABLED NUMBER(1) DEFAULT 1,
    
    -- Audit
    CREATE_TIME DATE DEFAULT SYSDATE NOT NULL,
    UPDATE_TIME DATE DEFAULT SYSDATE NOT NULL,
    UPDATE_USER VARCHAR2(100)
);

-- Insert default configurations
INSERT INTO PAYMENT_CONFIG VALUES (1, 'scb.api.endpoint', 'https://api.standardchartered.com/payment', 'STRING', 'Standard Chartered API Endpoint', 'PROD', 1, SYSDATE, SYSDATE, 'SYSTEM');
INSERT INTO PAYMENT_CONFIG VALUES (2, 'scb.timeout.seconds', '120', 'NUMBER', 'Payment timeout threshold in seconds', 'PROD', 1, SYSDATE, SYSDATE, 'SYSTEM');
INSERT INTO PAYMENT_CONFIG VALUES (3, 'scb.callback.retry.max', '5', 'NUMBER', 'Maximum callback retry attempts', 'PROD', 1, SYSDATE, SYSDATE, 'SYSTEM');
INSERT INTO PAYMENT_CONFIG VALUES (4, 'reconciliation.cron', '0 */30 * * * ?', 'STRING', 'Reconciliation job cron expression', 'PROD', 1, SYSDATE, SYSDATE, 'SYSTEM');
COMMIT;
```

#### 2.6 Create Sequences
```sql
CREATE SEQUENCE SEQ_PAYMENT_TRANSACTION START WITH 1 INCREMENT BY 1 NOCACHE;
CREATE SEQUENCE SEQ_PAYMENT_CALLBACK_LOG START WITH 1 INCREMENT BY 1 NOCACHE;
CREATE SEQUENCE SEQ_PAYMENT_RECONCILIATION START WITH 1 INCREMENT BY 1 NOCACHE;
CREATE SEQUENCE SEQ_PAYMENT_ORDER_MAPPING START WITH 1 INCREMENT BY 1 NOCACHE;
CREATE SEQUENCE SEQ_PAYMENT_CONFIG START WITH 1 INCREMENT BY 1 NOCACHE;
```

### Step 3: Create Entity Classes with MyBatis-Plus

**Directory**: `PaymentSys/src/main/java/sys/smc/payment/entity`

#### 3.1 Base Entity
```java
@Data
@MappedSuperclass
public abstract class BaseEntity implements Serializable {
    @TableField(value = "CREATE_TIME", fill = FieldFill.INSERT)
    private Date createTime;
    
    @TableField(value = "UPDATE_TIME", fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;
    
    @Version
    @TableField(value = "VERSION")
    private Integer version;
}
```

#### 3.2 PaymentTransaction Entity
```java
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("PAYMENT_TRANSACTION")
public class PaymentTransaction extends BaseEntity {
    @TableId(value = "ID", type = IdType.INPUT)
    private Long id;
    
    @TableField("TRANSACTION_ID")
    private String transactionId;
    
    @TableField("IDEMPOTENCY_KEY")
    private String idempotencyKey;
    
    @TableField("ORDER_REFERENCE")
    private String orderReference;
    
    @TableField("USER_ID")
    private String userId;
    
    @TableField("SUBR_NUM")
    private String subrNum;
    
    @TableField("AMOUNT")
    private BigDecimal amount;
    
    @TableField("PAYMENT_STATUS")
    private String paymentStatus;
    
    @TableField("GATEWAY_TRANSACTION_ID")
    private String gatewayTransactionId;
    
    // ... other fields
}
```

### Step 4: Implement Payment Service Layer

**Directory**: `PaymentSys/src/main/java/sys/smc/payment/service`

#### 4.1 Payment Initiation Service
```java
@Service
@Slf4j
public class PaymentService {
    
    @Autowired
    private PaymentTransactionMapper transactionMapper;
    
    @Autowired
    private StandardCharteredGatewayClient gatewayClient;
    
    @Transactional(rollbackFor = Exception.class)
    public PaymentInitResponse initiatePayment(PaymentInitRequest request) {
        // 1. Generate idempotency key
        String idempotencyKey = generateIdempotencyKey(request);
        
        // 2. Check if transaction already exists (idempotency)
        PaymentTransaction existing = transactionMapper.selectByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            log.warn("Duplicate payment request detected: {}", idempotencyKey);
            return buildResponseFromTransaction(existing);
        }
        
        // 3. Create transaction record with INIT status
        PaymentTransaction transaction = buildTransaction(request, idempotencyKey);
        transaction.setPaymentStatus(PaymentStatus.INIT.name());
        transactionMapper.insert(transaction);
        
        try {
            // 4. Call Standard Chartered API
            GatewayPaymentResponse gatewayResponse = gatewayClient.createPayment(request);
            
            // 5. Update transaction with gateway info
            transaction.setGatewayTransactionId(gatewayResponse.getTransactionId());
            transaction.setPaymentStatus(PaymentStatus.PENDING.name());
            transaction.setStatusUpdateTime(new Date());
            transactionMapper.updateById(transaction);
            
            // 6. Return response to client
            return PaymentInitResponse.builder()
                .transactionId(transaction.getTransactionId())
                .paymentUrl(gatewayResponse.getPaymentUrl())
                .status(PaymentStatus.PENDING.name())
                .build();
                
        } catch (Exception e) {
            log.error("Failed to initiate payment: {}", request, e);
            transaction.setPaymentStatus(PaymentStatus.FAILED.name());
            transaction.setErrorMessage(e.getMessage());
            transactionMapper.updateById(transaction);
            throw new PaymentException("Payment initiation failed", e);
        }
    }
    
    private String generateIdempotencyKey(PaymentInitRequest request) {
        return request.getOrderReference() + "_" + 
               request.getAmount() + "_" + 
               System.currentTimeMillis();
    }
}
```

### Step 5: Build Callback Webhook Handler

**Directory**: `PaymentSys/src/main/java/sys/smc/payment/controller`

#### 5.1 Callback Controller
```java
@RestController
@RequestMapping("/api/payment/callback")
@Slf4j
public class PaymentCallbackController {
    
    @Autowired
    private PaymentCallbackService callbackService;
    
    @PostMapping("/standard-chartered")
    public ResponseEntity<String> handleStandardCharteredCallback(
            @RequestBody String rawBody,
            @RequestHeader Map<String, String> headers,
            HttpServletRequest request) {
        
        long startTime = System.currentTimeMillis();
        String clientIp = getClientIp(request);
        
        log.info("Received callback from IP: {}, Headers: {}", clientIp, headers);
        
        try {
            // Process callback asynchronously
            callbackService.processCallback(rawBody, headers, clientIp);
            
            // Return success immediately (async processing)
            return ResponseEntity.ok("SUCCESS");
            
        } catch (Exception e) {
            log.error("Failed to process callback", e);
            // Still return success to prevent bank retry storms
            return ResponseEntity.ok("RECEIVED");
        } finally {
            long processingTime = System.currentTimeMillis() - startTime;
            log.info("Callback processing time: {}ms", processingTime);
        }
    }
}
```

#### 5.2 Callback Service
```java
@Service
@Slf4j
public class PaymentCallbackService {
    
    @Autowired
    private PaymentTransactionMapper transactionMapper;
    
    @Autowired
    private PaymentCallbackLogMapper callbackLogMapper;
    
    @Autowired
    private SignatureVerifier signatureVerifier;
    
    @Async("callbackExecutor")
    @Transactional(rollbackFor = Exception.class, 
                   isolation = Isolation.READ_COMMITTED,
                   propagation = Propagation.REQUIRES_NEW)
    public void processCallback(String rawBody, Map<String, String> headers, String clientIp) {
        long startTime = System.currentTimeMillis();
        PaymentCallbackLog callbackLog = new PaymentCallbackLog();
        
        try {
            // 1. Parse callback data
            PaymentCallbackData callbackData = parseCallbackData(rawBody);
            
            // 2. Verify signature
            boolean signatureValid = signatureVerifier.verify(rawBody, headers.get("X-Signature"));
            callbackLog.setSignatureValid(signatureValid ? 1 : 0);
            
            if (!signatureValid) {
                log.error("Invalid signature for callback: {}", rawBody);
                callbackLog.setProcessingStatus("FAILED");
                callbackLog.setErrorMessage("Invalid signature");
                return;
            }
            
            // 3. Find transaction
            PaymentTransaction transaction = transactionMapper.selectByGatewayTransactionId(
                callbackData.getGatewayTransactionId()
            );
            
            if (transaction == null) {
                log.error("Transaction not found: {}", callbackData.getGatewayTransactionId());
                callbackLog.setProcessingStatus("FAILED");
                callbackLog.setErrorMessage("Transaction not found");
                return;
            }
            
            callbackLog.setTransactionId(transaction.getTransactionId());
            
            // 4. Check if already processed (idempotency)
            if (isTerminalStatus(transaction.getPaymentStatus())) {
                log.warn("Transaction already in terminal status: {}", transaction.getPaymentStatus());
                callbackLog.setProcessingStatus("DUPLICATE");
                return;
            }
            
            // 5. Update transaction status using optimistic locking
            boolean updated = updateTransactionStatus(transaction, callbackData);
            
            if (!updated) {
                log.warn("Failed to update transaction due to version conflict, will retry");
                callbackLog.setProcessingStatus("RETRY");
                throw new OptimisticLockException("Version conflict");
            }
            
            // 6. Trigger downstream actions (order confirmation, notification, etc.)
            if (PaymentStatus.SUCCESS.name().equals(callbackData.getPaymentStatus())) {
                notifyOrderSystem(transaction);
            }
            
            callbackLog.setProcessingStatus("SUCCESS");
            
        } catch (Exception e) {
            log.error("Error processing callback", e);
            callbackLog.setProcessingStatus("FAILED");
            callbackLog.setErrorMessage(e.getMessage());
            throw e;
            
        } finally {
            // Always log callback attempt
            long processingTime = System.currentTimeMillis() - startTime;
            callbackLog.setProcessingTimeMs((int) processingTime);
            callbackLog.setRequestBody(rawBody);
            callbackLog.setRequestHeaders(JSON.toJSONString(headers));
            callbackLog.setRequestIp(clientIp);
            callbackLog.setCallbackTime(new Date());
            
            callbackLogMapper.insert(callbackLog);
        }
    }
    
    private boolean updateTransactionStatus(PaymentTransaction transaction, PaymentCallbackData callbackData) {
        // Use optimistic locking
        PaymentTransaction update = new PaymentTransaction();
        update.setId(transaction.getId());
        update.setVersion(transaction.getVersion()); // Important for optimistic locking
        update.setPreviousStatus(transaction.getPaymentStatus());
        update.setPaymentStatus(callbackData.getPaymentStatus());
        update.setStatusUpdateTime(new Date());
        update.setCallbackReceived(1);
        update.setCallbackCount(transaction.getCallbackCount() + 1);
        update.setLastCallbackTime(new Date());
        update.setGatewayTransactionId(callbackData.getGatewayTransactionId());
        
        int rows = transactionMapper.updateById(update);
        return rows > 0;
    }
    
    private boolean isTerminalStatus(String status) {
        return PaymentStatus.SUCCESS.name().equals(status) ||
               PaymentStatus.FAILED.name().equals(status) ||
               PaymentStatus.REFUNDED.name().equals(status);
    }
}
```

### Step 6: Create Scheduled Reconciliation Job

**Directory**: `PaymentSys/src/main/java/sys/smc/payment/job`

#### 6.1 Reconciliation Job
```java
@Component
@Slf4j
public class PaymentReconciliationJob {
    
    @Autowired
    private PaymentTransactionMapper transactionMapper;
    
    @Autowired
    private StandardCharteredGatewayClient gatewayClient;
    
    @Autowired
    private PaymentReconciliationMapper reconciliationMapper;
    
    /**
     * Auto-reconcile TIMEOUT and PENDING transactions every 30 minutes
     * This prevents the "customer paid but system shows timeout" issue
     */
    @Scheduled(cron = "${payment.reconciliation.cron:0 */30 * * * ?}")
    @Transactional(rollbackFor = Exception.class)
    public void reconcileTimeoutTransactions() {
        log.info("Starting auto-reconciliation for TIMEOUT/PENDING transactions");
        
        // Find transactions that are TIMEOUT or PENDING for more than 30 minutes
        Date cutoffTime = DateUtils.addMinutes(new Date(), -30);
        
        List<PaymentTransaction> timeoutTransactions = transactionMapper.selectList(
            new LambdaQueryWrapper<PaymentTransaction>()
                .in(PaymentTransaction::getPaymentStatus, 
                    PaymentStatus.TIMEOUT.name(), 
                    PaymentStatus.PENDING.name())
                .lt(PaymentTransaction::getStatusUpdateTime, cutoffTime)
                .orderByAsc(PaymentTransaction::getCreateTime)
        );
        
        log.info("Found {} transactions to reconcile", timeoutTransactions.size());
        
        int matched = 0;
        int mismatch = 0;
        List<String> mismatchIds = new ArrayList<>();
        
        for (PaymentTransaction transaction : timeoutTransactions) {
            try {
                // Query Standard Chartered for actual status
                GatewayTransactionStatus gatewayStatus = gatewayClient.queryTransactionStatus(
                    transaction.getGatewayTransactionId()
                );
                
                transaction.setLastQueryTime(new Date());
                
                // Compare statuses
                if (!transaction.getPaymentStatus().equals(gatewayStatus.getStatus())) {
                    log.warn("Status mismatch found - Transaction: {}, Local: {}, Gateway: {}", 
                        transaction.getTransactionId(),
                        transaction.getPaymentStatus(),
                        gatewayStatus.getStatus());
                    
                    // Update to gateway status (gateway is source of truth)
                    transaction.setPreviousStatus(transaction.getPaymentStatus());
                    transaction.setPaymentStatus(gatewayStatus.getStatus());
                    transaction.setReconciliationStatus("MISMATCH");
                    transaction.setReconciliationTime(new Date());
                    
                    // If gateway says SUCCESS but we had TIMEOUT, notify immediately
                    if (PaymentStatus.SUCCESS.name().equals(gatewayStatus.getStatus())) {
                        sendAlertEmail(transaction, "CRITICAL: Payment was successful but marked as TIMEOUT");
                        notifyOrderSystem(transaction);
                    }
                    
                    mismatch++;
                    mismatchIds.add(transaction.getTransactionId());
                } else {
                    transaction.setReconciliationStatus("MATCHED");
                    matched++;
                }
                
                transactionMapper.updateById(transaction);
                
            } catch (Exception e) {
                log.error("Failed to reconcile transaction: {}", transaction.getTransactionId(), e);
            }
        }
        
        // Save reconciliation record
        PaymentReconciliation reconciliation = new PaymentReconciliation();
        reconciliation.setReconciliationDate(new Date());
        reconciliation.setReconciliationType("AUTO_TIMEOUT");
        reconciliation.setTotalTransactions(timeoutTransactions.size());
        reconciliation.setMatchedCount(matched);
        reconciliation.setMismatchCount(mismatch);
        reconciliation.setMismatchTransactionIds(JSON.toJSONString(mismatchIds));
        reconciliation.setReconciliationStatus("COMPLETED");
        reconciliation.setStartTime(new Date());
        reconciliation.setEndTime(new Date());
        
        reconciliationMapper.insert(reconciliation);
        
        log.info("Reconciliation completed - Matched: {}, Mismatch: {}", matched, mismatch);
    }
    
    /**
     * Daily full reconciliation at 3 AM
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void dailyFullReconciliation() {
        log.info("Starting daily full reconciliation");
        // Query all transactions from yesterday and compare with bank records
        // Generate report for finance team
    }
}
```

### Step 7: Implement Gateway Client

**Directory**: `PaymentSys/src/main/java/sys/smc/payment/gateway`

#### 7.1 Standard Chartered Gateway Client
```java
@Component
@Slf4j
public class StandardCharteredGatewayClient {
    
    @Value("${scb.api.endpoint}")
    private String apiEndpoint;
    
    @Value("${scb.api.key}")
    private String apiKey;
    
    @Value("${scb.api.secret}")
    private String apiSecret;
    
    @Autowired
    private RestTemplate restTemplate;
    
    public GatewayPaymentResponse createPayment(PaymentInitRequest request) {
        String url = apiEndpoint + "/create";
        
        // Build request
        Map<String, Object> gatewayRequest = new HashMap<>();
        gatewayRequest.put("merchantId", apiKey);
        gatewayRequest.put("orderReference", request.getOrderReference());
        gatewayRequest.put("amount", request.getAmount());
        gatewayRequest.put("currency", "HKD");
        gatewayRequest.put("callbackUrl", getCallbackUrl());
        gatewayRequest.put("returnUrl", request.getReturnUrl());
        
        // Sign request
        String signature = generateSignature(gatewayRequest);
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-API-Key", apiKey);
        headers.set("X-Signature", signature);
        
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(gatewayRequest, headers);
        
        try {
            ResponseEntity<GatewayPaymentResponse> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                entity,
                GatewayPaymentResponse.class
            );
            
            return response.getBody();
            
        } catch (Exception e) {
            log.error("Failed to create payment at gateway", e);
            throw new GatewayException("Failed to create payment", e);
        }
    }
    
    public GatewayTransactionStatus queryTransactionStatus(String gatewayTransactionId) {
        String url = apiEndpoint + "/query/" + gatewayTransactionId;
        
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", apiKey);
        
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        
        try {
            ResponseEntity<GatewayTransactionStatus> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                GatewayTransactionStatus.class
            );
            
            return response.getBody();
            
        } catch (Exception e) {
            log.error("Failed to query transaction status", e);
            throw new GatewayException("Failed to query status", e);
        }
    }
    
    private String generateSignature(Map<String, Object> data) {
        // Implement HMAC-SHA256 signature
        // Sort keys alphabetically, concatenate values, sign with secret
        return SignatureUtils.sign(data, apiSecret);
    }
}
```

### Step 8: Configuration Files

#### 8.1 Application Configuration
**File**: `PaymentSys/src/main/resources/application.yml`

```yaml
spring:
  application:
    name: payment-system
  datasource:
    driver-class-name: oracle.jdbc.OracleDriver
    url: jdbc:oracle:thin:@localhost:1521:ORCL
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
  
  task:
    execution:
      pool:
        core-size: 10
        max-size: 50
        queue-capacity: 100
    scheduling:
      pool:
        size: 5

mybatis-plus:
  mapper-locations: classpath*:/mapper/**/*.xml
  type-aliases-package: sys.smc.payment.entity
  configuration:
    map-underscore-to-camel-case: true
    log-impl: org.apache.ibatis.logging.slf4j.Slf4jImpl
  global-config:
    db-config:
      id-type: auto
      logic-delete-value: 1
      logic-not-delete-value: 0

# Payment Gateway Configuration
scb:
  api:
    endpoint: ${SCB_API_ENDPOINT:https://api.standardchartered.com/payment}
    key: ${SCB_API_KEY}
    secret: ${SCB_API_SECRET}
  timeout:
    seconds: 120
  callback:
    retry:
      max: 5
      delay: 5000

# Reconciliation
payment:
  reconciliation:
    cron: "0 */30 * * * ?"  # Every 30 minutes
  timeout:
    threshold: 300  # 5 minutes

# Logging
logging:
  level:
    sys.smc.payment: DEBUG
    com.baomidou.mybatisplus: DEBUG
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{50} - %msg%n"
  file:
    name: logs/payment-system.log
    max-size: 100MB
    max-history: 30
```

### Step 9: Create Comprehensive Documentation

**Directory**: `PaymentSys/docs`

#### 9.1 API Documentation
**File**: `PaymentSys/docs/API-Specification.md`

```markdown
# Payment System API Specification

## 1. Initiate Payment

**Endpoint**: `POST /api/payment/initiate`

**Request**:
```json
{
  "orderReference": "CSP001234567",
  "userId": "33xxxx",
  "subrNum": "6xxxxx",
  "amount": 176.00,
  "currency": "HKD",
  "paymentMethod": "CARD",
  "ecrId": "W30000000718xxxx",
  "returnUrl": "https://shop.example.com/payment/return",
  "customerName": "John Doe",
  "customerEmail": "john@example.com"
}
```

**Response**:
```json
{
  "code": 0,
  "message": "Success",
  "data": {
    "transactionId": "TXN20260203200150001",
    "paymentUrl": "https://payment.standardchartered.com/pay?token=xxx",
    "status": "PENDING",
    "expiryTime": "2026-02-03T20:11:50Z"
  }
}
```

## 2. Query Payment Status

**Endpoint**: `GET /api/payment/status/{transactionId}`

**Response**:
```json
{
  "code": 0,
  "message": "Success",
  "data": {
    "transactionId": "TXN20260203200150001",
    "orderReference": "CSP001234567",
    "amount": 176.00,
    "status": "SUCCESS",
    "gatewayTransactionId": "SCB738574372Axxxxxxx330E1A39D44",
    "paymentTime": "2026-02-03T20:02:30Z",
    "callbackReceived": true
  }
}
```

## 3. Webhook Callback (from Standard Chartered)

**Endpoint**: `POST /api/payment/callback/standard-chartered`

**Request Headers**:
- `X-Signature`: HMAC-SHA256 signature
- `Content-Type`: application/json

**Request Body**:
```json
{
  "merchantId": "M123456",
  "gatewayTransactionId": "SCB738574372Axxxxxxx330E1A39D44",
  "orderReference": "CSP001234567",
  "amount": 176.00,
  "currency": "HKD",
  "status": "SUCCESS",
  "paymentTime": "2026-02-03T20:02:30Z",
  "paymentMethod": "VISA",
  "cardLast4": "1234"
}
```

**Response**: `200 OK` with body `SUCCESS`
```

#### 9.2 Database ERD
**File**: `PaymentSys/docs/Database-ERD.md`

Include diagrams showing relationships between:
- PAYMENT_TRANSACTION (core)
- PAYMENT_CALLBACK_LOG (1:N with PAYMENT_TRANSACTION)
- PAYMENT_ORDER_MAPPING (1:N with PAYMENT_TRANSACTION)
- PAYMENT_RECONCILIATION (independent)
- PAYMENT_CONFIG (independent)

#### 9.3 Sequence Diagrams
**File**: `PaymentSys/docs/Sequence-Diagrams.md`

Include diagrams for:
1. **Normal Payment Flow** - Customer → System → Gateway → Callback → Order Confirmation
2. **Timeout Scenario** - Payment timeout → Reconciliation job → Status correction
3. **Duplicate Callback** - Multiple callbacks → Idempotency check → Ignore duplicates

#### 9.4 Runbook for Payment Discrepancies
**File**: `PaymentSys/docs/Runbook-Payment-Discrepancy.md`

```markdown
# Runbook: Handling Payment Discrepancies

## Scenario 1: Customer Claims Payment Success but System Shows TIMEOUT

### Symptoms
- Customer shows bank deduction confirmation
- System shows TIMEOUT status
- Finance confirms no payment received

### Investigation Steps

1. **Check PAYMENT_TRANSACTION table**:
```sql
SELECT * FROM PAYMENT_TRANSACTION 
WHERE ORDER_REFERENCE = 'CSP001xxxxx'
ORDER BY CREATE_TIME DESC;
```

2. **Check PAYMENT_CALLBACK_LOG**:
```sql
SELECT * FROM PAYMENT_CALLBACK_LOG 
WHERE TRANSACTION_ID IN (
  SELECT TRANSACTION_ID FROM PAYMENT_TRANSACTION 
  WHERE ORDER_REFERENCE = 'CSP001xxxxx'
)
ORDER BY CALLBACK_TIME DESC;
```

3. **Check if callback was received but failed**:
- Look for PROCESSING_STATUS = 'FAILED' in callback logs
- Check ERROR_MESSAGE for root cause

4. **Manually query Standard Chartered API**:
```bash
curl -X GET "https://api.standardchartered.com/payment/query/{gatewayTransactionId}" \
  -H "X-API-Key: ${API_KEY}"
```

### Resolution Steps

1. **If Gateway Confirms Payment**:
```sql
-- Manually update transaction status
UPDATE PAYMENT_TRANSACTION 
SET PAYMENT_STATUS = 'SUCCESS',
    PREVIOUS_STATUS = 'TIMEOUT',
    RECONCILIATION_STATUS = 'MANUAL_FIX',
    RECONCILIATION_TIME = SYSDATE,
    REMARKS = 'Manually reconciled - Gateway confirmed payment success'
WHERE TRANSACTION_ID = 'TXN20260203200150001';
COMMIT;
```

2. **Trigger order confirmation**:
```bash
curl -X POST "http://localhost:8080/api/payment/manual-confirm" \
  -H "Content-Type: application/json" \
  -d '{"transactionId": "TXN20260203200150001"}'
```

3. **Notify finance team** with reconciliation report

### Prevention
- Reconciliation job runs every 30 minutes
- Alert on any TIMEOUT → SUCCESS transitions
- Daily reconciliation report sent to finance team
```

## Step 10: Testing Strategy

**Directory**: `PaymentSys/src/test/java`

Create tests for:
1. **Unit Tests**: Service layer logic, signature verification
2. **Integration Tests**: Database operations, MyBatis-Plus mappers
3. **Callback Tests**: Idempotency, concurrent callbacks, retry logic
4. **Reconciliation Tests**: Mismatch detection, status updates

## Further Considerations

### 1. Why the TIMEOUT Issue Occurs

The dead-lock scenario happens due to:

1. **Network Latency**: Callback from Standard Chartered is delayed or lost
2. **System Timeout Too Short**: System marks transaction as TIMEOUT before bank processes it
3. **Callback Processing Failure**: Callback arrives but fails to update database (exception, lock, etc.)
4. **No Reconciliation**: System has no mechanism to check actual status with bank

**Solutions Implemented**:
- Increase timeout threshold to 5 minutes (configurable)
- Async callback processing to prevent blocking
- Reconciliation job runs every 30 minutes
- All callbacks logged regardless of processing result
- Optimistic locking prevents race conditions

### 2. Idempotency Strategy

**Implementation**:
- `idempotencyKey` = `orderReference` + `amount` + `timestamp`
- Unique constraint on `IDEMPOTENCY_KEY` in database
- Check existing transaction before creating new one
- Duplicate callbacks detected by checking transaction status

**Why This Works**:
- Prevents duplicate charges if user refreshes payment page
- Handles network retry scenarios
- Safe for concurrent requests

### 3. Transaction State Machine

**States**:
```
INIT → PENDING → SUCCESS
                → FAILED
                → TIMEOUT → RECONCILING → SUCCESS/FAILED
                                        → REFUNDED
```

**Terminal States**: SUCCESS, FAILED, REFUNDED (no further transitions)
**Reconcilable States**: TIMEOUT, PENDING (can be updated by reconciliation)

### 4. Optimistic vs Pessimistic Locking

**Choice**: **Optimistic Locking** with `@Version`

**Reasons**:
- Callbacks are rare (1-2 per transaction typically)
- Low contention scenario
- Better performance than pessimistic locking
- MyBatis-Plus native support

**Implementation**:
```java
// Transaction entity has version field
@Version
private Integer version;

// Update will fail if version changed
int rows = transactionMapper.updateById(transaction);
if (rows == 0) {
    throw new OptimisticLockException("Concurrent update detected");
}
```

### 5. Callback Retry Mechanism

**From Bank Side**:
- Standard Chartered typically retries failed callbacks
- Retry intervals: 1min, 5min, 15min, 30min, 1hour

**From Our Side**:
- Return 200 OK immediately to prevent retry storms
- Process callback asynchronously
- Log all attempts for debugging
- If processing fails, reconciliation job will fix it

### 6. Security Considerations

1. **Signature Verification**: Verify HMAC-SHA256 signature on all callbacks
2. **IP Whitelist**: Only accept callbacks from Standard Chartered IPs
3. **HTTPS Only**: All communication over TLS
4. **API Key Encryption**: Store API keys in encrypted format
5. **Rate Limiting**: Prevent callback flooding attacks

### 7. Monitoring and Alerts

**Metrics to Track**:
- Payment success rate
- Average payment processing time
- Callback processing success rate
- Number of TIMEOUT transactions
- Reconciliation mismatch count

**Alerts**:
- TIMEOUT → SUCCESS transition (critical issue)
- Callback processing failure rate > 5%
- Reconciliation mismatch > 10 transactions/day
- Payment gateway API response time > 10s

### 8. Performance Optimization

1. **Database Indexing**: Proper indexes on query columns
2. **Connection Pooling**: HikariCP with optimized settings
3. **Async Processing**: Callbacks processed asynchronously
4. **Batch Reconciliation**: Process multiple transactions in single query
5. **Caching**: Cache gateway configuration

### 9. Disaster Recovery

**Backup Strategy**:
- Daily database backup with 30-day retention
- Transaction logs archived for 1 year
- Callback logs archived for 6 months

**Recovery Procedures**:
1. Restore from latest backup
2. Replay callbacks from archive
3. Run reconciliation to fix any gaps
4. Verify with finance team

### 10. Compliance and Audit

**Requirements**:
- PCI-DSS compliance for card payments
- Data retention policy (7 years)
- Audit trail for all status changes
- Encryption at rest and in transit

**Implementation**:
- Never store full card numbers
- Log all state transitions with timestamps
- Encrypt sensitive data in database
- Regular security audits

## Summary

This payment system design addresses the critical "customer paid but system shows timeout" issue through:

1. **Comprehensive Transaction Tracking**: Every state change is logged
2. **Robust Callback Handling**: Async processing with retry and idempotency
3. **Automated Reconciliation**: Regular checks with bank to detect discrepancies
4. **Optimistic Locking**: Prevents race conditions in concurrent scenarios
5. **Complete Audit Trail**: All callbacks logged regardless of processing result
6. **Manual Intervention Tools**: Runbook for handling edge cases

The key insight is that **the gateway is the source of truth**. When in doubt, query the gateway and update local status accordingly. The reconciliation job acts as a safety net to catch any missed callbacks or processing failures.

