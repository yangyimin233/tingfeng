package com.tingfeng.agent.http;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 统计每次诊断的 Token 消耗。
 * 通过 AgentWorkflowService 在诊断前后调用 reset() / getUsage() 获取单次消耗。
 */
public class TokenUsageTracker implements ChatModelListener {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageTracker.class);

    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicInteger callCount = new AtomicInteger();

    @Override
    public void onRequest(ChatModelRequestContext ctx) {
        // request 不做统计, response 里拿 token 数
    }

    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        TokenUsage usage = ctx.chatResponse().tokenUsage();
        if (usage != null) {
            inputTokens.addAndGet(usage.inputTokenCount());
            outputTokens.addAndGet(usage.outputTokenCount());
            callCount.incrementAndGet();
        }
    }

    @Override
    public void onError(ChatModelErrorContext ctx) {
        // error 不计入 token
    }

    /** 重置计数器 (每次诊断开始时调用) */
    public void reset() {
        inputTokens.set(0);
        outputTokens.set(0);
        callCount.set(0);
    }

    /** 获取当前累计 token 用量 */
    public TokenUsage getUsage() {
        return new TokenUsage((int) inputTokens.get(), (int) outputTokens.get());
    }

    public int getCallCount() { return callCount.get(); }

    public String format() {
        return String.format("[Token] LLM 调用 %d 次, 输入 %d, 输出 %d, 合计 %d",
                callCount.get(), inputTokens.get(), outputTokens.get(),
                inputTokens.get() + outputTokens.get());
    }
}
