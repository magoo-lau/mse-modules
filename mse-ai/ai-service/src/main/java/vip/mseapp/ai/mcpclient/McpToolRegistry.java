package vip.mseapp.ai.mcpclient;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import vip.mseapp.ai.entity.AiMcpServer;
import vip.mseapp.ai.mapper.AiMcpServerMapper;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部 MCP Server 动态客户端注册表：把启用的 ai_mcp_server 建为
 * {@link McpSyncClient}，其工具经 Spring AI {@link SyncMcpToolCallback} 暴露。
 *
 * <p>注册行按会话世界单边读写（租户自管，租户会话=本租户行、平台会话=平台世界行 tenant_id=0，
 * {@link TenantContext#runAs} 随世界路由 + 显式 tenant 条件读取——一切皆租户：独立库租户落专属库、
 * 共享租户落绑定共享库、平台世界 0 落平台库/降级主库）；客户端与摘要缓存按 世界:服务名 键隔离，
 * 配置变更经 invalidate 重建。智能体以 toolCode = mcp:{serverName}:{toolName} 引用
 * （见 AiToolService 解析分支）。
 */
@Service
public class McpToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistry.class);

    private final AiMcpServerMapper serverMapper;
    /** key：world:serverName（各世界自有行，同名互不串线） */
    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();
    /** 各服务上次成功的工具摘要（listToolSummaries 失败时回退）；key 同 clients */
    private final Map<String, List<McpToolSummary>> summaryCache = new ConcurrentHashMap<>();

    public McpToolRegistry(AiMcpServerMapper serverMapper) {
        this.serverMapper = serverMapper;
    }

    /** 当前会话世界编号（无上下文按平台世界 0 处理，供异步/RPC 兜底） */
    private static long worldId() {
        Long tenantId = TenantContext.getTenantId();
        return tenantId == null ? MseConstants.PLATFORM_TENANT_ID : tenantId;
    }

    private static String cacheKey(String serverName) {
        return worldId() + ":" + serverName;
    }

    /** 某外部服务的全部工具回调（连接失败抛业务异常，由调用方降级） */
    public List<ToolCallback> callbacks(String serverName) {
        McpSyncClient client = client(serverName);
        List<McpSchema.Tool> tools = client.listTools().tools();
        return tools.stream()
                .map(tool -> (ToolCallback) new SyncMcpToolCallback(client, tool))
                .toList();
    }

    /** 按工具名取单个回调（工具不存在抛 404） */
    public ToolCallback callback(String serverName, String toolName) {
        return callbacks(serverName).stream()
                .filter(cb -> toolName.equals(cb.getToolDefinition().name()))
                .findFirst()
                .orElseThrow(() -> new ServiceException(404, "MCP 工具不存在: " + serverName + ":" + toolName));
    }

    /** 连通性测试：返回远端工具名列表 */
    public List<String> listToolNames(String serverName) {
        return client(serverName).listTools().tools().stream()
                .map(McpSchema.Tool::name)
                .toList();
    }

    /** MCP 工具摘要（绑定下拉用）：serverName/toolName/description/inputSchema */
    public record McpToolSummary(String serverName, String toolName, String description, String inputSchema) {
    }

    /**
     * 当前世界全部启用服务的工具摘要（智能体绑定下拉合并用）。
     *
     * <p>best-effort：单服务失败回退上次成功结果（无缓存则跳过），不影响其余服务；
     * 客户端按 世界:服务名 缓存，连接成功后的常规调用不产生建连开销。
     */
    public List<McpToolSummary> listToolSummaries() {
        // 本世界注册行随世界路由读取（一切皆租户）
        List<AiMcpServer> servers = TenantContext.runAs(worldId(), () ->
                serverMapper.selectList(new LambdaQueryWrapper<AiMcpServer>()
                        .eq(AiMcpServer::getTenantId, worldId())
                        .eq(AiMcpServer::getStatus, 0).orderByAsc(AiMcpServer::getName)));
        List<McpToolSummary> result = new java.util.ArrayList<>();
        for (AiMcpServer server : servers) {
            String key = cacheKey(server.getName());
            try {
                List<McpToolSummary> summaries = callbacks(server.getName()).stream()
                        .map(cb -> {
                            var definition = cb.getToolDefinition();
                            return new McpToolSummary(server.getName(), definition.name(),
                                    definition.description(), definition.inputSchema());
                        })
                        .toList();
                summaryCache.put(key, summaries);
                result.addAll(summaries);
            } catch (Exception e) {
                log.warn("[mse-ai] MCP 工具清单获取失败，回退上次结果: {} - {}", server.getName(), e.getMessage());
                List<McpToolSummary> cached = summaryCache.get(key);
                if (cached != null) {
                    result.addAll(cached);
                }
            }
        }
        return result;
    }

    public void invalidate(String serverName) {
        String key = cacheKey(serverName);
        McpSyncClient client = clients.remove(key);
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.debug("[mse-ai] MCP 客户端关闭失败: {}", serverName);
            }
        }
        summaryCache.remove(key);
    }

    private McpSyncClient client(String serverName) {
        return clients.computeIfAbsent(cacheKey(serverName), name -> {
            // 本世界 MCP 注册随世界路由读取（对话运行时在租户上下文中）
            AiMcpServer server = TenantContext.runAs(worldId(), () ->
                    serverMapper.selectOne(new LambdaQueryWrapper<AiMcpServer>()
                            .eq(AiMcpServer::getTenantId, worldId())
                            .eq(AiMcpServer::getName, serverName).eq(AiMcpServer::getStatus, 0).last("LIMIT 1")));
            if (server == null) {
                throw new ServiceException(404, "MCP Server 未注册或已停用: " + serverName);
            }
            String ssePath = server.getSsePath() == null || server.getSsePath().isBlank()
                    ? "/sse" : server.getSsePath();
            HttpClientSseClientTransport transport = HttpClientSseClientTransport
                    .builder(server.getEndpoint())
                    .sseEndpoint(ssePath)
                    .build();
            McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(30))
                    .build();
            client.initialize();
            log.info("[mse-ai] MCP 客户端已连接: {} -> {}", serverName, server.getEndpoint());
            return client;
        });
    }
}
