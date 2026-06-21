package com.tingfeng.agent.config;

import com.tingfeng.agent.agent.ReporterAgent;

/**
 * volatile 可替换的 ReporterAgent 代理。
 * 热插拔改变执行工具集后，重建 Reporter 以注入最新的可用工具列表。
 */
public class DynamicReporterHolder implements ReporterAgent {

    private volatile ReporterAgent delegate;

    public DynamicReporterHolder(ReporterAgent delegate) {
        this.delegate = delegate;
    }

    @Override
    public String report(String fullContext) {
        return delegate.report(fullContext);
    }

    public void swap(ReporterAgent newAgent) {
        this.delegate = newAgent;
    }

    public int delegateHash() {
        return System.identityHashCode(delegate);
    }
}
