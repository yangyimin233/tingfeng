package com.tingfeng.starter.model;

import java.util.UUID;

public class DiagnosticSnapshot {

    /** 调用链追踪 ID */
    private String traceId;

    /** 方法全名，格式：类名#方法名 */
    private String methodName;

    /** 方法入参，JSON 数组格式 */
    private String args;

    /** 执行耗时（毫秒） */
    private long rt;

    /** 是否成功 */
    private boolean success;

    /** 异常信息（仅 success=false 时有值） */
    private String errorMsg;

    /** 异常发生时的时间戳 */
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

    public long getRt() { return rt; }
    public void setRt(long rt) { this.rt = rt; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
