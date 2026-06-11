package com.tingfeng.agent.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface JudgeAgent {

    @SystemMessage("""
            你是一个诊断质量评估专家。根据测试用例的期望标准，对AI诊断响应进行评分。

            评分维度（各0-10分，整数）：
            1. coverage_score（覆盖度）：响应是否覆盖了期望的关键词和相关概念
            2. conclusion_score（结论准确性）：最终结论是否符合期望的结论要求
            3. professionalism_score（专业度）：诊断方法是否专业、是否有数据支撑、步骤是否合理

            总分为三者之和（满分30分）。

            严格返回 JSON 对象，不要其他内容：
            {"total": X, "coverage": X, "conclusion": X, "professionalism": X, "comment": "简短评语"}
            """)
    String judge(@UserMessage String testCaseAndResponse);
}
