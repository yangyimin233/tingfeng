package com.tingfeng.agent.config;

import com.tingfeng.agent.agent.PlannerAgent;

/**
 * volatile 可替换的 PlannerAgent 代理。
 * 热插拔改变工具集后，重建 PlannerAgent 以注入最新的标签映射。
 */
public class DynamicPlannerHolder implements PlannerAgent {

    private volatile PlannerAgent delegate;

    public DynamicPlannerHolder(PlannerAgent delegate) {
        this.delegate = delegate;
    }

    @Override
    public String plan(String userMessage) {
        return delegate.plan(userMessage);
    }

    public void swap(PlannerAgent newAgent) {
        this.delegate = newAgent;
    }

    public int delegateHash() {
        return System.identityHashCode(delegate);
    }
}
