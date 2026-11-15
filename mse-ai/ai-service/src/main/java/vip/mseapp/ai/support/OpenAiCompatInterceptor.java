package vip.mseapp.ai.support;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import okio.Source;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

/**
 * OpenAI 兼容端点响应归一化垫片（讯飞星火等）。
 *
 * <p>Spring AI 2.x 底层的 openai-java 客户端严格按 OpenAI 规范访问成功响应的必填字段：
 * 星火非流式响应不返回 id/object/created/model/choices[].finish_reason、流式帧不返回 model，
 * 会在端点成功应答时抛 OpenAIInvalidDataException("`id` is not set") 之类异常。
 * 本拦截器仅对 2xx 响应按需补齐缺失字段的默认值（不覆盖端点已有字段，流式帧不补
 * finish_reason 以免干扰逐帧聚合语义），报文解析失败原样放行，对标准 OpenAI 端点零影响。
 */
public final class OpenAiCompatInterceptor implements Interceptor {

    @Override
    public Response intercept(Chain chain) throws IOException {
        Response response = chain.proceed(chain.request());
        if (!response.isSuccessful() || response.body() == null) {
            return response;
        }
        MediaType contentType = response.body().contentType();
        if (contentType == null) {
            return response;
        }
        String type = contentType.toString().toLowerCase();
        if (type.startsWith("application/json")) {
            return patchJson(response, contentType);
        }
        if (type.startsWith("text/event-stream")) {
            return patchSse(response, contentType);
        }
        return response;
    }

    // ==================== 非流式：整包补齐 ====================

    private Response patchJson(Response response, MediaType contentType) throws IOException {
        String raw = response.body().string();
        try {
            JsonNode root = AiJson.MAPPER.readTree(raw);
            if (root instanceof ObjectNode node) {
                patchCompletion(node);
                raw = AiJson.MAPPER.writeValueAsString(node);
            }
        } catch (RuntimeException ignored) {
            // 非模型响应（网关 JSON 等）：原样放行（Jackson 3 解析失败为 unchecked JacksonException）
        }
        return response.newBuilder()
                .body(ResponseBody.create(raw, contentType))
                .build();
    }

    // ==================== 流式：逐 data 帧补齐 ====================

    private Response patchSse(Response response, MediaType contentType) throws IOException {
        BufferedSource upstream = response.body().source();
        Buffer out = new Buffer();
        Source transformed = new ForwardingSource(upstream) {
            private boolean eof = false;

            @Override
            public long read(Buffer sink, long byteCount) throws IOException {
                while (out.size() < byteCount && !eof) {
                    String line = upstream.readUtf8Line();
                    if (line == null) {
                        eof = true;
                        break;
                    }
                    out.writeUtf8(transformSseLine(line)).writeUtf8("\n");
                }
                if (out.size() == 0) {
                    return -1;
                }
                return out.read(sink, byteCount);
            }
        };
        return response.newBuilder()
                .body(ResponseBody.create(contentType, -1L, Okio.buffer(transformed)))
                .build();
    }

    private String transformSseLine(String line) {
        if (line == null || !line.startsWith("data:")) {
            return line;
        }
        String payload = line.substring("data:".length()).trim();
        if (payload.isEmpty() || "[DONE]".equals(payload)) {
            return line;
        }
        try {
            JsonNode root = AiJson.MAPPER.readTree(payload);
            if (root instanceof ObjectNode node) {
                putIfAbsentText(node, "id", "chatcmpl-" + UUID.randomUUID());
                putIfAbsentNumber(node, "created", Instant.now().getEpochSecond());
                putIfAbsentText(node, "model", "unknown");
                return "data: " + AiJson.MAPPER.writeValueAsString(node);
            }
        } catch (RuntimeException ignored) {
            // 非 JSON 帧：原样放行
        }
        return line;
    }

    /** 成功响应缺省字段补齐（星火非流式全部不返回；choices 存在才补 object/finish_reason，兼容向量等其他形态） */
    private void patchCompletion(ObjectNode node) {
        putIfAbsentText(node, "id", "chatcmpl-" + UUID.randomUUID());
        putIfAbsentNumber(node, "created", Instant.now().getEpochSecond());
        putIfAbsentText(node, "model", "unknown");
        JsonNode choices = node.get("choices");
        if (choices != null && choices.isArray()) {
            putIfAbsentText(node, "object", "chat.completion");
            for (JsonNode choice : choices) {
                if (choice instanceof ObjectNode choiceNode) {
                    putIfAbsentText(choiceNode, "finish_reason", "stop");
                }
            }
        }
    }

    private void putIfAbsentText(ObjectNode node, String field, String value) {
        if (!node.hasNonNull(field)) {
            node.put(field, value);
        }
    }

    private void putIfAbsentNumber(ObjectNode node, String field, long value) {
        if (!node.hasNonNull(field)) {
            node.put(field, value);
        }
    }
}
