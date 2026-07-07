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
 * 统计每次诊断的 Token 消耗 (仅单用户测试用, 默认关闭)。
 * 开启后注意：并发请求会互相污染计数，不适合生产环境。
 */
public class TokenUsageTracker implements ChatModelListener {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageTracker.class);

    private volatile boolean enabled;
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicInteger callCount = new AtomicInteger();

    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    @Override
    public void onRequest(ChatModelRequestContext ctx) {
    }

    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        if (!enabled) return;
        TokenUsage usage = ctx.chatResponse().tokenUsage();
        if (usage != null) {
            inputTokens.addAndGet(usage.inputTokenCount());
            outputTokens.addAndGet(usage.outputTokenCount());
            callCount.incrementAndGet();
        }
    }

    @Override
    public void onError(ChatModelErrorContext ctx) {
    }

    public void reset() {
        if (!enabled) return;
        inputTokens.set(0);
        outputTokens.set(0);
        callCount.set(0);
    }

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
