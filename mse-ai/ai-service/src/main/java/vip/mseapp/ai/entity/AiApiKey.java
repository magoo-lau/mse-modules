package vip.mseapp.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 开放 API 应用密钥（tenant_id = 调用归属租户，行级隔离）。
 *
 * <p>第三方系统凭 appId + apiKey 直调 /openapi/ai/**（网关白名单放行，服务内过滤器鉴权）；
 * 调用以绑定租户身份执行（可见智能体范围/模型圈定/用量归集均按该租户）。
 * apiKey 明文仅创建时返回一次，库内只存 SHA-256 哈希。凭证世界化：appId 内嵌租户号
 * （ai-{tenantId}-{hex}），鉴权解析世界段切库校验（旧格式主库兜底）；签发/管理随世界路由。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_api_key")
public class AiApiKey extends BaseEntity {

    /** 应用 ID（明文展示） */
    private String appId;

    /** API Key 哈希（SHA-256 hex） */
    private String keyHash;

    /** Key 前缀（前 8 位，管理端识别用） */
    private String keyPrefix;

    /** 可用智能体白名单（逗号分隔 agentCode；空 = 该租户全部已发布智能体） */
    private String agentCodes;

    /** 每分钟调用上限（0/空 = 不限） */
    private Integer ratePerMinute;

    /** 状态：0 启用 1 停用 */
    private Integer status;

    /** 备注 */
    private String remark;
}
