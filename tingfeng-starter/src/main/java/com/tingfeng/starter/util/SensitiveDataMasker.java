package com.tingfeng.starter.util;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 敏感数据脱敏工具 (Starter 版 — AOP 采集参数/返回值使用)。
 * 规则: 字段名黑名单 + 值正则模式，保留结构只换值。
 */
public class SensitiveDataMasker {

    private static final String MASK = "***";

    static final Set<String> DEFAULT_FIELD_RULES = Set.of(
            "phone", "mobile", "telephone", "tel",
            "idcard", "idno", "id_number", "idnumber", "identity",
            "password", "passwd", "pwd", "secret", "pin",
            "token", "accesstoken", "refreshtoken", "jwt",
            "email", "mail", "realname", "real_name",
            "bankaccount", "bankcard", "accountno",
            "id_card", "id_no"
    );

    static final Map<String, Pattern> DEFAULT_PATTERN_RULES = Map.of(
            "phone",  Pattern.compile("1[3-9]\\d{9}"),
            "idCard", Pattern.compile("\\d{17}[\\dXx]"),
            "email",  Pattern.compile("[\\w.-]+@[\\w.-]+\\.\\w{2,}")
    );

    private final boolean enabled;
    private final Set<String> fieldRules;
    private final Map<String, Pattern> patternRules;

    public SensitiveDataMasker(boolean enabled, Set<String> fieldRules,
                               Map<String, Pattern> patternRules) {
        this.enabled = enabled;
        this.fieldRules = fieldRules != null && !fieldRules.isEmpty()
                ? fieldRules : DEFAULT_FIELD_RULES;
        this.patternRules = patternRules != null && !patternRules.isEmpty()
                ? patternRules : DEFAULT_PATTERN_RULES;
    }

    public static SensitiveDataMasker defaultMasker() {
        return new SensitiveDataMasker(true, DEFAULT_FIELD_RULES, DEFAULT_PATTERN_RULES);
    }

    public String mask(String text) {
        if (!enabled || text == null || text.isBlank()) return text;
        String result = text;
        for (Map.Entry<String, Pattern> entry : patternRules.entrySet()) {
            result = entry.getValue().matcher(result).replaceAll(MASK);
        }
        return result;
    }

    /** 脱敏 key-value 风格的 JSON 字符串 */
    public String maskJson(String json) {
        if (!enabled || json == null || json.isBlank()) return json;
        String result = json;
        for (Map.Entry<String, Pattern> entry : patternRules.entrySet()) {
            result = entry.getValue().matcher(result).replaceAll(MASK);
        }
        for (String field : fieldRules) {
            result = result.replaceAll(
                    "(?i)\"" + Pattern.quote(field) + "\"\\s*:\\s*\"[^\"]*\"",
                    "\"" + field + "\":\"" + MASK + "\"");
            result = result.replaceAll(
                    "(?i)\"" + Pattern.quote(field) + "\"\\s*:\\s*\\d+",
                    "\"" + field + "\":" + MASK);
        }
        return result;
    }
}
