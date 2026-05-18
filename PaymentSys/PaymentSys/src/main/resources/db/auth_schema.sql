-- =============================================
-- 用户认证系统数据库表设计
-- v1.0  2026-05-09
-- =============================================

-- ─────────────────────────────────────────────
-- 1. 用户组表（Group 层级结构）
-- ─────────────────────────────────────────────
CREATE TABLE SYS_GROUP (
    GROUP_ID        NUMBER(10)    NOT NULL PRIMARY KEY,
    GROUP_NAME      VARCHAR2(100) NOT NULL,
    PARENT_GROUP_ID NUMBER(10),                     -- 父GroupId（345=普通财务Parent, 59=经理Parent）
    DESCRIPTION     VARCHAR2(500),
    ENABLED         NUMBER(1) DEFAULT 1,            -- 1=启用, 0=禁用
    CREATE_TIME     DATE DEFAULT SYSDATE NOT NULL,
    UPDATE_TIME     DATE DEFAULT SYSDATE NOT NULL,

    CONSTRAINT FK_SG_PARENT FOREIGN KEY (PARENT_GROUP_ID)
        REFERENCES SYS_GROUP(GROUP_ID)
);

CREATE INDEX IDX_SG_PARENT ON SYS_GROUP(PARENT_GROUP_ID);
COMMENT ON TABLE SYS_GROUP IS '用户组表，通过PARENT_GROUP_ID判断角色（345=财务部, 59=经理部）';

-- 预置分组数据
INSERT INTO SYS_GROUP VALUES (1,   '系统根组',    NULL, '所有分组的根节点',     1, SYSDATE, SYSDATE);
INSERT INTO SYS_GROUP VALUES (59,  '财务经理组',  1,    '经理，退款无次数限制', 1, SYSDATE, SYSDATE);
INSERT INTO SYS_GROUP VALUES (345, '普通财务组',  1,    '财务人员，退款上限3次',1, SYSDATE, SYSDATE);
INSERT INTO SYS_GROUP VALUES (100, '客服组',      1,    '客户服务人员',         1, SYSDATE, SYSDATE);
INSERT INTO SYS_GROUP VALUES (200, '普通用户组',  1,    '普通买家',             1, SYSDATE, SYSDATE);

-- 财务员工的组（parent=345），经理的组（parent=59）
INSERT INTO SYS_GROUP VALUES (346, '财务A部门',   345,  '财务A部门员工',        1, SYSDATE, SYSDATE);
INSERT INTO SYS_GROUP VALUES (347, '财务B部门',   345,  '财务B部门员工',        1, SYSDATE, SYSDATE);
INSERT INTO SYS_GROUP VALUES (60,  '财务经理1组', 59,   '经理层成员',           1, SYSDATE, SYSDATE);
COMMIT;


-- ─────────────────────────────────────────────
-- 2. 用户表
-- ─────────────────────────────────────────────
CREATE TABLE SYS_USER (
    USER_ID          VARCHAR2(50)  NOT NULL PRIMARY KEY,
    USERNAME         VARCHAR2(100) NOT NULL UNIQUE,     -- 登录名
    PASSWORD_HASH    VARCHAR2(200) NOT NULL,             -- BCrypt 哈希（明文绝不存库！）
    GROUP_ID         NUMBER(10)    NOT NULL,             -- 所属组（从此组查 PARENT_GROUP_ID 判角色）
    REAL_NAME        VARCHAR2(100),
    EMAIL            VARCHAR2(200),
    ENABLED          NUMBER(1) DEFAULT 1,                -- 1=启用, 0=禁用
    LOCKED           NUMBER(1) DEFAULT 0,                -- 1=锁定（连续失败次数过多）
    FAILED_ATTEMPTS  NUMBER(5)  DEFAULT 0,              -- 连续登录失败次数
    LAST_LOGIN_TIME  DATE,
    LOCK_TIME        DATE,                              -- 锁定时间
    CREATE_TIME      DATE DEFAULT SYSDATE NOT NULL,
    UPDATE_TIME      DATE DEFAULT SYSDATE NOT NULL,

    CONSTRAINT FK_SU_GROUP FOREIGN KEY (GROUP_ID)
        REFERENCES SYS_GROUP(GROUP_ID)
);

CREATE INDEX IDX_SU_USERNAME ON SYS_USER(USERNAME);
CREATE INDEX IDX_SU_GROUP_ID ON SYS_USER(GROUP_ID);
COMMENT ON TABLE SYS_USER IS '系统用户表，PASSWORD_HASH用BCrypt存储，严禁明文';
COMMENT ON COLUMN SYS_USER.GROUP_ID IS '所属组ID，通过SYS_GROUP.PARENT_GROUP_ID判断角色，登录时从DB查，绝不信任客户端';
COMMENT ON COLUMN SYS_USER.LOCKED  IS '连续失败5次自动锁定，防暴力破解';

-- ─────────────────────────────────────────────
-- 3. 登录日志（安全审计）
-- ─────────────────────────────────────────────
CREATE TABLE SYS_LOGIN_LOG (
    ID           NUMBER(19)   NOT NULL PRIMARY KEY,
    USER_ID      VARCHAR2(50),
    USERNAME     VARCHAR2(100),
    LOGIN_IP     VARCHAR2(50) NOT NULL,
    USER_AGENT   VARCHAR2(500),
    SUCCESS      NUMBER(1)    NOT NULL,    -- 1=成功, 0=失败
    FAIL_REASON  VARCHAR2(200),           -- 失败原因
    LOGIN_TIME   DATE DEFAULT SYSDATE NOT NULL
);

CREATE INDEX IDX_SLL_USER_ID   ON SYS_LOGIN_LOG(USER_ID);
CREATE INDEX IDX_SLL_LOGIN_TIME ON SYS_LOGIN_LOG(LOGIN_TIME DESC);
COMMENT ON TABLE SYS_LOGIN_LOG IS '登录日志，记录所有登录尝试（成功+失败），安全审计用';

CREATE SEQUENCE SEQ_SYS_LOGIN_LOG START WITH 1 INCREMENT BY 1 NOCACHE;

