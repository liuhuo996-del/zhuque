package com.zhuque.m4_closure;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * M4 · 字段名归一化——闭包检查比对前必须先过这里。
 *
 * 归一化规则（顺序执行）：
 * 1. 路径取叶：orders[].id → id（但保留父段用于同义判断：order + id）
 * 2. snake/camel 互转统一为 snake：orderNo → order_no
 * 3. 单复数归一：orders → order
 * 4. id/code/no 同义：order_no ≡ order_id ≡ order_code
 * 5. 业务同义词典（可配置）：如 customer ≡ user ≡ member
 *
 * 精确规则命中 = 置信度 1.0；词典/语义命中 < 1.0，
 * 低置信度的匹配要进 ClosureResult.fuzzyMatches 让人确认，不能悄悄当等价。
 */
@Component
public class FieldNormalizer {

    private static final Map<String, String> BUSINESS_SYNONYMS = new LinkedHashMap<>();

    static {
        BUSINESS_SYNONYMS.put("customer", "party");
        BUSINESS_SYNONYMS.put("user", "party");
        BUSINESS_SYNONYMS.put("member", "party");
        BUSINESS_SYNONYMS.put("client", "party");
        BUSINESS_SYNONYMS.put("mobile", "phone");
        BUSINESS_SYNONYMS.put("telephone", "phone");
    }

    public record NormMatch(boolean matched, double confidence, String rule) {}

    /** 功能：把参数名/字段路径规范化成可比对的 key。 */
    public String normalize(String paramOrFieldPath) {
        if (paramOrFieldPath == null || paramOrFieldPath.isBlank()) {
            return "";
        }
        String value = paramOrFieldPath.trim()
                .replaceAll("\\[\\]", "")
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("^_+|_+$", "")
                .toLowerCase(Locale.ROOT);
        String[] segments = value.split("_+");
        StringBuilder normalized = new StringBuilder();
        String previous = null;
        for (String segment : segments) {
            String singular = singular(segment);
            if (!singular.isBlank() && !singular.equals(previous)) {
                if (normalized.length() > 0) {
                    normalized.append('_');
                }
                normalized.append(singular);
                previous = singular;
            }
        }
        return normalized.toString();
    }

    /** 功能：判断「必填参数 requiredParam」能否由「输出字段 outputFieldPath」满足，带置信度和命中规则名。 */
    public NormMatch matches(String requiredParam, String outputFieldPath) {
        String required = normalize(requiredParam);
        String output = normalize(outputFieldPath);
        if (required.isBlank() || output.isBlank()) {
            return new NormMatch(false, 0, "empty");
        }
        if (required.equals(output) || output.endsWith("_" + required)) {
            return new NormMatch(true, 1, "normalized-exact");
        }
        String requiredAlias = identifierAlias(required);
        String outputAlias = identifierAlias(output);
        if (requiredAlias.equals(outputAlias) || outputAlias.endsWith("_" + requiredAlias)) {
            return new NormMatch(true, 0.82, "identifier-alias");
        }
        String requiredBusiness = businessAlias(requiredAlias);
        String outputBusiness = businessAlias(outputAlias);
        if (requiredBusiness.equals(outputBusiness) || outputBusiness.endsWith("_" + requiredBusiness)) {
            return new NormMatch(true, 0.72, "business-synonym");
        }
        return new NormMatch(false, 0, "none");
    }

    private static String singular(String token) {
        if (token.length() > 3 && token.endsWith("ies")) {
            return token.substring(0, token.length() - 3) + "y";
        }
        if (token.length() > 3 && token.endsWith("ses")) {
            return token.substring(0, token.length() - 2);
        }
        if (token.length() > 2 && token.endsWith("s") && !token.endsWith("ss")) {
            return token.substring(0, token.length() - 1);
        }
        return token;
    }

    private static String identifierAlias(String value) {
        return value.replaceAll("_(id|code|no)$", "_identifier");
    }

    private static String businessAlias(String value) {
        String result = value;
        for (Map.Entry<String, String> synonym : BUSINESS_SYNONYMS.entrySet()) {
            result = result.replaceAll("(^|_)" + synonym.getKey() + "(?=_|$)", "$1" + synonym.getValue());
        }
        return result;
    }
}
