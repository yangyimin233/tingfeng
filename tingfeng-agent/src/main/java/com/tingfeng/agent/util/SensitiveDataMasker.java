package com.tingfeng.agent.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 敏感数据脱敏工具 — mysql_query / redis 结果 + 探针上报 共享逻辑。
 * 规则: 字段名黑名单 + 值正则模式，保留结构只换值。
 */
public class SensitiveDataMasker {

    private static final String MASK = "***";

    // ── 内置规则 ──
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
            "phone",    Pattern.compile("1[3-9]\\d{9}"),
            "idCard",   Pattern.compile("\\d{17}[\\dXx]"),
            "email",    Pattern.compile("[\\w.-]+@[\\w.-]+\\.\\w{2,}")
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

    // ── 单值脱敏 ──

    public String mask(String text) {
        if (!enabled || text == null || text.isBlank()) return text;
        String result = text;
        for (var entry : patternRules.entrySet()) {
            result = entry.getValue().matcher(result).replaceAll(MASK);
        }
        return result;
    }

    // ── JSON 字符串脱敏 (key-value 推断) ──

    public String maskJson(String json) {
        if (!enabled || json == null || json.isBlank()) return json;
        String result = json;
        for (var entry : patternRules.entrySet()) {
            result = entry.getValue().matcher(result).replaceAll(MASK);
        }
        // 简单 key 匹配: "key":"value" 或 "key":数字
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

    // ── JDBC 二维表脱敏 ──

    public List<Map<String, Object>> maskQueryResult(List<Map<String, Object>> rows) {
        if (!enabled || rows == null || rows.isEmpty()) return rows;
        List<Map<String, Object>> masked = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> newRow = new LinkedHashMap<>();
            for (var entry : row.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (isSensitiveField(key)) {
                    newRow.put(key, MASK);
                } else if (value instanceof String) {
                    newRow.put(key, mask((String) value));
                } else {
                    newRow.put(key, value);
                }
            }
            masked.add(newRow);
        }
        return masked;
    }

    // ── Redis 键值对脱敏 ──

    public String maskRedisValue(String key, String value) {
        if (!enabled) return value;
        if (isSensitiveField(key)) return MASK;
        return mask(value);
    }

    private boolean isSensitiveField(String fieldName) {
        if (fieldName == null) return false;
        return fieldRules.contains(fieldName.toLowerCase().replace("_", ""));
    }
}
