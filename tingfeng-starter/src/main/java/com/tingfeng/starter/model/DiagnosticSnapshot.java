package com.tingfeng.starter.model;

import java.util.UUID;

public class DiagnosticSnapshot {

    /** 调用链追踪 ID */
    private String traceId;

    /** 方法全名，格式：类名#方法名 */
    private String methodName;

    /** 方法入参，JSON 数组格式 */
    private String args;

    /** 方法返回值，JSON 格式 (成功时有值) */
    private String returnValue;

    /** 执行耗时（毫秒） */
    private long rt;

    /** 是否成功 */
    private boolean success;

    /** 异常类型+消息（仅 success=false 时有值） */
    private String errorMsg;

    /** 异常完整堆栈（仅 success=false 时有值） */
    private String errorStack;

    /** 接口请求到达时间戳 (方法进入时) */
    private long requestTime;


    /** 捕获时间戳 (finally 块执行时) */
    private long timestamp;

    public DiagnosticSnapshot() {
        this.traceId = UUID.randomUUID().toString().replace("-", "");
    }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public String getMethodName() { return methodName; }
    public void setMethodName(String methodName) { this.methodName = methodName; }

    public String getArgs() { return args; }
    public void setArgs(String args) { this.args = args; }

    public String getReturnValue() { return returnValue; }
    public void setReturnValue(String returnValue) { this.returnValue = returnValue; }

    public long getRequestTime() { return requestTime; }
    public void setRequestTime(long requestTime) { this.requestTime = requestTime; }

    public long getRt() { return rt; }
    public void setRt(long rt) { this.rt = rt; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }

    public String getErrorStack() { return errorStack; }
    public void setErrorStack(String errorStack) { this.errorStack = errorStack; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    /** 服务器标识 */
    private String serverHost;

    public String getServerHost() { return serverHost; }
    public void setServerHost(String serverHost) { this.serverHost = serverHost; }

    /** 本次请求中 MyBatis 拦截的 SQL 列表 (JSON 数组) */
    private String sqlStatements;

    public String getSqlStatements() { return sqlStatements; }
    public void setSqlStatements(String sqlStatements) { this.sqlStatements = sqlStatements; }
}
