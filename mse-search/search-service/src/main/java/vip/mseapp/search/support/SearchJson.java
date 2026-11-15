package vip.mseapp.search.support;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import vip.mseapp.core.exception.ServiceException;

import java.util.List;

/**
 * JSON 工具（增量扫描请求/结果、索引事件载荷的序列化统一入口）。
 *
 * <p>静态自建 Jackson 2 mapper 而非注入容器 Bean：Boot 4 默认自动装配的是 Jackson 3
 * （tools.jackson）的 JsonMapper，Jackson 2 的 ObjectMapper 无 Bean 可注入；Jackson 2 由
 * elasticsearch-java 传递在类路径上，与引擎适配层同栈。未知字段容错关闭（MQ 载荷与 RPC
 * 返回包前向兼容）。
 */
public final class SearchJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private SearchJson() {
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

    /** 解析 JSON 数组为元素清单（字段配置等 List 结构） */
    public static <T> List<T> parseList(String json, Class<T> elementType) {
        try {
            return MAPPER.readValue(json, MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, elementType));
        } catch (Exception e) {
            throw new ServiceException(500, "JSON 数组解析失败：" + e.getMessage());
        }
    }
}
