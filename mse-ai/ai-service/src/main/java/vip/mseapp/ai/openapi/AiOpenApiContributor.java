package vip.mseapp.ai.openapi;

import vip.mseapp.ai.api.RemoteAiChatService;
import vip.mseapp.open.api.OpenApiContributor;
import vip.mseapp.open.api.OpenApiDescriptor;

import java.util.List;

/**
 * AI 平台开放能力声明（开放平台自注册：应用就绪后由 open-api 推送器推送至 mse-open 目录）。
 *
 * <p>复用既有契约 {@link RemoteAiChatService}——业务无需新写任何接口，登记一条描述即开放。
 */
public class AiOpenApiContributor implements OpenApiContributor {

    @Override
    public List<OpenApiDescriptor> descriptors() {
        OpenApiDescriptor item = new OpenApiDescriptor();
        item.setCode("open:ai:chat");
        item.setName("AI 对话");
        item.setHttpMethod("POST");
        item.setPath("/open/api/ai/chat");
        item.setTarget(RemoteAiChatService.class.getName() + "#chat");
        item.setDescription("body: agentCode/message/conversationId/variables；"
                + "调用已发布智能体（按应用绑定租户校验可见范围并记录用量）");
        return List.of(item);
    }
}
