package vip.mseapp.ai.avatar;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 智能体头像存储契约：上传即返回可直接写入 {@code ai_agent.avatar} 的站内地址
 * （emoji 字面量之外的图片值；前端按 http(s)/站内路径前缀识别为图片渲染）。
 *
 * <p>实现二选一，由装配按 mse-fs 可用性自动选择（{@code mse.ai.avatar.storage-mode} 可显式覆盖）：
 * <ul>
 *   <li>{@link FsAgentAvatarStorage}：mse-fs 公开素材通道（accessLevel=public，bizType=agent-avatar），
 *       返回 {@code /fs/asset/{tenantId}/{fileId}.{后缀}}（免登录端点输出，IM 聊天客户端亦可寻址）；</li>
 *   <li>{@link LocalAgentAvatarStorage}：单机目录兜底，返回 {@code /ai/agent/asset/{name}}
 *       （免登录读取端点 {@code GET /ai/agent/asset/{name}}，微服务形态需加入网关 ignore-urls）。</li>
 * </ul>
 * 声明同类型 Bean 即可整体替换（{@code @ConditionalOnMissingBean(AgentAvatarStorage.class)}）。
 */
public interface AgentAvatarStorage {

    /** 上传头像，返回站内地址（落库到 ai_agent.avatar） */
    String upload(MultipartFile file);

    /** 输出本地落盘素材（仅本地实现有效；mse-fs 模式素材由 /fs/asset/** 免登录端点输出，不经此端点） */
    void write(String name, HttpServletResponse response) throws IOException;
}
