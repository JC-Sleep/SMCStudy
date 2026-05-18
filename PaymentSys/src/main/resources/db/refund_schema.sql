-- =============================================
-- 退款审批系统数据库设计
-- v1.0  2026-05-08
-- =============================================

-- ─────────────────────────────────────────────
-- 1. 为 PAYMENT_TRANSACTION 增加部分退款支持
-- ─────────────────────────────────────────────

-- 新增累计已退款金额字段（多次部分退款时累加）
ALTER TABLE PAYMENT_TRANSACTION
    ADD TOTAL_REFUNDED_AMOUNT NUMBER(10,2) DEFAULT 0;

COMMENT ON COLUMN PAYMENT_TRANSACTION.TOTAL_REFUNDED_AMOUNT
    IS '累计已退款金额（多次部分退款逐次累加），用于判断是否可继续申请退款';

-- 新增 PARTIALLY_REFUNDED 状态（需重建约束）
ALTER TABLE PAYMENT_TRANSACTION
    DROP CONSTRAINT CHK_PAYMENT_STATUS;

ALTER TABLE PAYMENT_TRANSACTION
    ADD CONSTRAINT CHK_PAYMENT_STATUS CHECK (PAYMENT_STATUS IN (
        'INIT', 'PENDING', 'SUCCESS', 'FAILED', 'TIMEOUT',
        'REFUNDING', 'PARTIALLY_REFUNDED', 'REFUNDED', 'REFUND_FAILED',
        'RECONCILING'
    ));

-- ─────────────────────────────────────────────
-- 2. 退款申请主表
-- ─────────────────────────────────────────────
CREATE TABLE REFUND_APPLICATION (
    ID              NUMBER(19)      NOT NULL PRIMARY KEY,
    TRANSACTION_ID  VARCHAR2(100)   NOT NULL,           -- 关联 PAYMENT_TRANSACTION.TRANSACTION_ID
    ORDER_REFERENCE VARCHAR2(100),                      -- 冗余：订单号，方便财务查找
    GATEWAY_CODE    VARCHAR2(20),                       -- 支付渠道代码（SCB/ALIPAY/CCB...）

    -- 金额
    REFUND_AMOUNT   NUMBER(10,2)    NOT NULL,           -- 本次申请退款金额
    ORIGINAL_AMOUNT NUMBER(10,2)    NOT NULL,           -- 原始支付金额（冗余）

    -- 申请人
    APPLICANT_USER_ID VARCHAR2(100) NOT NULL,           -- 用户ID

    -- 申请内容
    REFUND_REASON   VARCHAR2(500)   NOT NULL,           -- 退款原因

    -- 状态
    STATUS          VARCHAR2(20)    NOT NULL,           -- 见 RefundApplicationStatus 枚举
    CONSTRAINT CHK_REFUND_STATUS CHECK (STATUS IN (
        'PENDING_REVIEW', 'APPROVED', 'REJECTED',
        'EXECUTING', 'COMPLETED', 'FAILED'
    )),

    -- 财务审批信息
    REVIEWED_BY     VARCHAR2(100),                      -- 审批财务的用户ID
    REVIEWED_AT     DATE,                               -- 审批时间
    REVIEW_REMARK   VARCHAR2(500),                      -- 审批备注

    -- 执行结果
    REFUND_NO       VARCHAR2(100),                      -- 银行退款单号（成功后填入）
    COMPLETED_AT    DATE,                               -- 退款完成时间
    FAIL_REASON     VARCHAR2(500),                      -- 失败原因

    -- 审计基础字段
    CREATE_USER     VARCHAR2(100),
    UPDATE_USER     VARCHAR2(100),
    CREATE_TIME     DATE DEFAULT SYSDATE NOT NULL,
    UPDATE_TIME     DATE DEFAULT SYSDATE NOT NULL,
    VERSION         NUMBER(10) DEFAULT 0 NOT NULL,      -- 乐观锁

    CONSTRAINT FK_RA_TRANSACTION FOREIGN KEY (TRANSACTION_ID)
        REFERENCES PAYMENT_TRANSACTION(TRANSACTION_ID)
);

-- 索引
CREATE INDEX IDX_RA_TXN_ID   ON REFUND_APPLICATION(TRANSACTION_ID);
CREATE INDEX IDX_RA_STATUS    ON REFUND_APPLICATION(STATUS);
CREATE INDEX IDX_RA_APPLICANT ON REFUND_APPLICATION(APPLICANT_USER_ID);
CREATE INDEX IDX_RA_CREATE    ON REFUND_APPLICATION(CREATE_TIME DESC);

COMMENT ON TABLE  REFUND_APPLICATION IS '退款申请主表：用户申请 → 财务审批 → 异步执行';
COMMENT ON COLUMN REFUND_APPLICATION.STATUS  IS 'PENDING_REVIEW待审/APPROVED已批/REJECTED拒绝/EXECUTING执行中/COMPLETED完成/FAILED失败';
COMMENT ON COLUMN REFUND_APPLICATION.VERSION IS '乐观锁：防止并发重复审批/执行';


-- ─────────────────────────────────────────────
-- 3. 退款审计日志（不可变流水，只能 INSERT）
-- ─────────────────────────────────────────────
CREATE TABLE REFUND_AUDIT_LOG (
    ID                       NUMBER(19)   NOT NULL PRIMARY KEY,
    APPLICATION_ID           NUMBER(19)   NOT NULL,     -- 关联 REFUND_APPLICATION.ID
    TRANSACTION_ID           VARCHAR2(100),

    -- 操作信息
    ACTION                   VARCHAR2(30) NOT NULL,     -- APPLY/APPROVE/REJECT/EXECUTE_SUCCESS/EXECUTE_FAILED
    CONSTRAINT CHK_AUDIT_ACTION CHECK (ACTION IN (
        'APPLY','APPROVE','REJECT','EXECUTE_SUCCESS','EXECUTE_FAILED'
    )),

    -- 操作人（完整记录，防抵赖）
    OPERATOR_USER_ID         VARCHAR2(100),
    OPERATOR_GROUP_ID        NUMBER(10),
    OPERATOR_PARENT_GROUP_ID NUMBER(10),                -- 345=普通财务, 59=经理
    OPERATOR_IP              VARCHAR2(50),               -- 客户端 IP

    REMARK                   VARCHAR2(1000),

    -- 只有创建时间，严禁修改
    CREATE_TIME              DATE DEFAULT SYSDATE NOT NULL,

    CONSTRAINT FK_RAL_APPLICATION FOREIGN KEY (APPLICATION_ID)
        REFERENCES REFUND_APPLICATION(ID)
);

CREATE INDEX IDX_RAL_APP_ID ON REFUND_AUDIT_LOG(APPLICATION_ID);
CREATE INDEX IDX_RAL_TXN_ID ON REFUND_AUDIT_LOG(TRANSACTION_ID);
CREATE INDEX IDX_RAL_CREATE  ON REFUND_AUDIT_LOG(CREATE_TIME DESC);

COMMENT ON TABLE  REFUND_AUDIT_LOG IS '退款审计日志（只增不改，记录每次状态变更操作人/时间/IP）';
COMMENT ON COLUMN REFUND_AUDIT_LOG.OPERATOR_PARENT_GROUP_ID IS '操作人父GroupId（345=普通财务,59=经理）供事后追溯';


-- ─────────────────────────────────────────────
-- 4. 序列
-- ─────────────────────────────────────────────
CREATE SEQUENCE SEQ_REFUND_APPLICATION START WITH 1 INCREMENT BY 1 NOCACHE;
CREATE SEQUENCE SEQ_REFUND_AUDIT_LOG   START WITH 1 INCREMENT BY 1 NOCACHE;


-- ─────────────────────────────────────────────
-- 5. 验证
-- ─────────────────────────────────────────────
SELECT 'REFUND_APPLICATION created' AS STATUS FROM DUAL WHERE EXISTS (
    SELECT 1 FROM USER_TABLES WHERE TABLE_NAME = 'REFUND_APPLICATION'
);
SELECT 'REFUND_AUDIT_LOG created' AS STATUS FROM DUAL WHERE EXISTS (
    SELECT 1 FROM USER_TABLES WHERE TABLE_NAME = 'REFUND_AUDIT_LOG'
);
