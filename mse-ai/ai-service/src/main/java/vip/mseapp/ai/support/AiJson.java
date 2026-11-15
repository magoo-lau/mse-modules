package vip.mseapp.ai.support;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vip.mseapp.core.exception.ServiceException;

import java.util.Map;

/**
 * JSON 工具（工具入参/出参、向量元数据的序列化统一入口）。
 */
public final class AiJson {

    /** Jackson 3（tools.jackson）基线，与 Boot 4 / Spring AI 2 同栈；内部 convertValue 保持数值语义，不套用 HTTP 层的 Long→String 定制 */
    public static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private AiJson() {
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new ServiceException(500, "JSON 序列化失败：" + e.getMessage());
        }
    }

    public static <T> T parse(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new ServiceException(500, "JSON 解析失败：" + e.getMessage());
        }
    }

    /** 解析为 JsonNode；非法 JSON 返回 null（工具入参容错由调用方决定） */
    public static JsonNode tryParse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(String json) {
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            throw new ServiceException(500, "JSON 解析失败：" + e.getMessage());
        }
    }
}
