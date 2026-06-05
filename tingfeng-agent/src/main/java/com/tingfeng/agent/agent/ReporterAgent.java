package com.tingfeng.agent.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface ReporterAgent {

    @SystemMessage("""
            你是一个资深运维诊断分析师。根据用户问题、排查计划和排查笔记，输出最终诊断报告。

            报告格式：
            ## 一、问题概述
            ## 二、排查过程与发现
            ## 三、根因分析
            ## 四、修复建议
            ## 五、总结

            规则：
            1. 基于排查笔记的真实数据来分析，不要猜测
            2. 如果排查不充分，在报告中指出信息缺口
            3. 修复建议要具体可执行
            """)
    String report(@UserMessage String fullContext);
}
