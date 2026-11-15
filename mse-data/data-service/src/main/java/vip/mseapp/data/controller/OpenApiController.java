package vip.mseapp.data.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.data.open.OpenApiService;

import java.util.Map;

/**
 * 数据服务开放端点（无登录态，appKey+签名鉴权）：
 *
 * <pre>
 * POST/GET /openapi/data/{path}
 * Header: X-App-Key / X-Timestamp（秒级）/ X-Nonce / X-Sign = HMAC-SHA256(secret, key\ntime\nnonce)
 * 参数：GET 走查询串，POST 走 JSON 体（{params:{...}, pageNum, pageSize}）
 * </pre>
 */
@RestController
@RequestMapping("/openapi/data")
public class OpenApiController {

    private final OpenApiService openApiService;

    public OpenApiController(OpenApiService openApiService) {
        this.openApiService = openApiService;
    }

    @PostMapping("/{path}")
    public OpenApiService.OpenResponse invokePost(@PathVariable String path,
                                                  @RequestHeader("X-App-Key") String appKey,
                                                  @RequestHeader("X-Timestamp") String timestamp,
                                                  @RequestHeader("X-Nonce") String nonce,
                                                  @RequestHeader("X-Sign") String sign,
                                                  @RequestBody(required = false) PostBody body,
                                                  HttpServletRequest request) {
        PostBody payload = body == null ? new PostBody(null, null, null) : body;
        OpenApiService.OpenRequest openRequest = new OpenApiService.OpenRequest(
                timestamp, nonce, sign, payload.params(), payload.pageNum(), payload.pageSize());
        return openApiService.invoke(appKey, path, openRequest, clientIp(request));
    }

    @GetMapping("/{path}")
    public OpenApiService.OpenResponse invokeGet(@PathVariable String path,
                                                 @RequestHeader("X-App-Key") String appKey,
                                                 @RequestHeader("X-Timestamp") String timestamp,
                                                 @RequestHeader("X-Nonce") String nonce,
                                                 @RequestHeader("X-Sign") String sign,
                                                 @RequestParam(required = false) Map<String, String> queryParams,
                                                 HttpServletRequest request) {
        // 查询串里剔除分页键后全部作为业务参数
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        queryParams.forEach((k, v) -> {
            if (!"pageNum".equals(k) && !"pageSize".equals(k)) {
                params.put(k, v);
            }
        });
        OpenApiService.OpenRequest openRequest = new OpenApiService.OpenRequest(timestamp, nonce, sign,
                params, parsePage(queryParams.get("pageNum")), parsePage(queryParams.get("pageSize")));
        return openApiService.invoke(appKey, path, openRequest, clientIp(request));
    }

    private Integer parsePage(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    public record PostBody(Map<String, Object> params, Integer pageNum, Integer pageSize) {
    }
}
