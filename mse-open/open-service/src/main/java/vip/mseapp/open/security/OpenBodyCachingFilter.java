package vip.mseapp.open.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 开放 API 请求体缓存过滤器（/open/api/**）：把请求体读入内存并以可重复读包装，
 * 供签名验签计算 body 摘要（{@link OpenSignatureService}）与下游 @RequestBody 各取所需。
 * 仅缓存有请求体的调用（GET 无体直接放行）；请求体大小上限沿用容器/Servlet 配置，此处不另设。
 */
public class OpenBodyCachingFilter extends OncePerRequestFilter {

    /** request attribute：缓存的请求体（字节数组；无体请求不设置） */
    public static final String ATTR_CACHED_BODY = "open.body.cached";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (request.getContentLengthLong() <= 0) {
            chain.doFilter(request, response);
            return;
        }
        byte[] body = request.getInputStream().readAllBytes();
        request.setAttribute(ATTR_CACHED_BODY, body);
        chain.doFilter(new CachedBodyRequest(request, body), response);
    }

    /** 可重复读请求包装：签名与控制器共享同一份缓存体 */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream buffer = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return buffer.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    // 同步读，不支持异步读监听
                }

                @Override
                public int read() {
                    return buffer.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
