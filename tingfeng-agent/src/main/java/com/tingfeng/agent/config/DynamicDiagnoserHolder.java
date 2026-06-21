package com.tingfeng.agent.config;

import com.tingfeng.agent.agent.DiagnoserAgent;

/**
 * volatile 可替换的 DiagnoserAgent 代理。
 * 实现 DiagnoserAgent 接口，内部 delegate 可随时 swap，调用方无感知。
 */
public class DynamicDiagnoserHolder implements DiagnoserAgent {

    private volatile DiagnoserAgent delegate;

    public DynamicDiagnoserHolder(DiagnoserAgent delegate) {
        this.delegate = delegate;
    }

    @Override
    public String execute(String task) {
        return delegate.execute(task);
    }

    /** 热替换内部代理实例, 线程安全 */
    public void swap(DiagnoserAgent newAgent) {
        this.delegate = newAgent;
    }

    /** 调试用 — 查看当前代理的 hashCode */
    public int delegateHash() {
        return System.identityHashCode(delegate);
    }
}
