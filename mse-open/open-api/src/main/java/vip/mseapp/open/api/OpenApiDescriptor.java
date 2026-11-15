package vip.mseapp.open.api;

import java.io.Serializable;

/**
 * 开放 API 描述（业务积木向开放平台自注册的目录条目）。
 *
 * <p>业务积木经 {@link OpenApiContributor} 声明本对象清单，推送至开放平台（mse-open）落入
 * open_api 目录；之后第三方应用按 path + httpMethod 调用 {@code /open/api/**}，mse-open 按
 * target 泛化分发到业务契约实现——业务只声明契约与描述，开放平台零改动接入。
 *
 * <p>target 格式：{@code 契约接口全名#方法名}（如
 * {@code vip.mseapp.ai.api.RemoteAiChatService#chatJson}）。契约方法签名统一为
 * {@code String 方法名(String requestJson)}：机器凭证链路无登录态，操作者等身份信息显式
 * 放在请求 JSON 内；租户上下文由开放平台按应用绑定租户注入、经 RPC 隐式透传，契约方法
 * 不携带 tenantId。
 */
public class OpenApiDescriptor implements Serializable {

    private static final long serialVersionUID = 1L;

    /** API 标识（open:&lt;biz&gt;:&lt;resource&gt;-&lt;action&gt;，全局唯一，授权与审计锚点） */
    private String code;

    /** API 名称（管理端展示） */
    private String name;

    /** 对外路径模板（/open/api/&lt;biz&gt;/...，支持 Ant 变量段如 {id}） */
    private String path;

    /** HTTP 方法（大写：GET/POST/PUT/DELETE） */
    private String httpMethod;

    /** 分发目标：契约接口全名#方法名 */
    private String target;

    /** 说明（可选，落目录 remark） */
    private String description;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
