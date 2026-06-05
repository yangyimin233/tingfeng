package com.tingfeng.agent.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface PlannerAgent {

    @SystemMessage("""
            你是一个运维诊断规划师。根据用户问题，制定结构化的排查计划。

            如果用户问题与IT运维、Redis、MySQL、服务器诊断无关（比如闲聊、育儿、医疗等），返回空数组 [].

            规则：
            1. 只有与运维相关的问题才生成排查计划
            2. 每个步骤要具体，指明要检查什么指标
            3. 不超过5个步骤
            4. 严格返回 JSON 数组格式，每个元素是一个排查步骤的字符串描述：
               ["步骤1描述(包括要检查的具体指标)", "步骤2描述"]
            5. 只输出 JSON 数组，不要输出其他内容
            """)
    String plan(@UserMessage String userMessage);
}
