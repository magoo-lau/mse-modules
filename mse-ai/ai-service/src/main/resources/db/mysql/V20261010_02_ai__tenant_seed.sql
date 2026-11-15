-- mse-target: master
-- mse-per-tenant: true
SET NAMES utf8mb4;
-- =====================================================================
-- 本文件为 ai 模块最终态基线，由 V20261001_01~V20261005_01 合并而来，中间演进（加列/种子退役）已内联为最终形态
-- 智能体/模型/工具真租户化·播种篇：平台公共行按租户播种自有副本
-- （原 V20261001_03 播种 + V20261002_02 每租户段合并；biz_analyst/biz_query 专属副本段
--   已被「全量平台行播种」覆盖，不再单列）
-- 背景：AI 全面下放租户自管（平台不再维护公共智能体/公共模型）——
--       ① 智能体：平台公共行（builtin 种子 general + 业务积木声明登记的 platform 行），
--          按原 agent_code 复制为各租户 scope=tenant 自有行（租户可直接编辑/发布/删除；
--          source/rpc_group 随源行携带）；已退役的六业务智能体种子（oa_approve_assist/
--          oa_process_designer/oa_form_fill/oa_flow_bot/data_analyst/biz_analyst）不再存在
--          平台种子行，各租户副本经可见性/对话入口懒补齐自登记行重建；OA/MES 场景映射与
--          开放 API 白名单按编码引用，不受影响
--       ② 模型：平台公共模型行（含端点/AES 密文）复制为各租户自有行（副本化后各租户可自行改配），
--          圈定(ai_model_tenant)/覆盖(ai_model_tenant_config) 机制代码退役
--       ③ 工具：平台工具行（builtin/local/rpc 自注册 + 平台侧登记）按原 tool_code 复制为
--          各租户副本行（source/rpc_group/config_json 原样，租户可自行启停/编辑）；
--          启动登记/推送登记已收敛为只治理平台世界行
-- id 约定（确定性生成，共享库多租户不冲突；租户编号取低 8 位参与运算防 BIGINT 溢出）：
--       副本 id = 源行 id + ${tenant_id} % 100000000 * 1000
-- 引用改写：复制后统一把本租户世界内指向平台模型行（tenant_id=0）的绑定改指副本——
--       ai_agent.model_id、ai_model.failover_model_id（备用模型）、
--       ai_knowledge_base.embedding_model_id / rerank_model_id。
--       四条 UPDATE 为条件幂等改写：以「仍指向 tenant_id=0 行」为 JOIN 条件，改写完成后
--       条件不再命中，重复应用无害（非无条件覆盖，属播种机制本体，非演进残留）
-- 每租户副本 kb_ids 置空：知识库为租户隔离资源（独立库路由），跨世界引用无意义
-- 平台世界行（tenant_id=0）保留不动：单世界原则下即平台世界自有数据，租户页面不可见
-- 幂等：ai_agent/ai_tool 按唯一键 (tenant_id, agent_code/tool_code, deleted=0) 判重——平台行经运行期
--       登记重建后 id 为雪花值，与旧种子固定 id 时代留下的租户副本 id 对不上，按副本 id 判重会漏判
--       既有同码租户行而撞 uk_agent_tenant/uk_tool_world_code；ai_model 无业务唯一键（名称可重），
--       按副本 id 判重防主键冲突即可；引用改写以「仍指向 tenant_id=0 行」为条件，重复应用无害
-- =====================================================================

-- ---------------- 模型副本（ai_model，源=平台行；无业务唯一键，按副本 id 判重防主键冲突） ----------------
INSERT INTO ai_model (id, tenant_id, name, provider, protocol, model_type, is_default, base_url, api_key_enc,
    chat_model, embedding_model, embedding_base_url, embedding_api_key_enc, temperature, top_p, max_tokens,
    failover_model_id, status, last_test_time, last_test_ok, last_test_msg, remark, create_by, create_time, deleted)
SELECT m.id + ${tenant_id} % 100000000 * 1000, ${tenant_id}, m.name, m.provider, m.protocol, m.model_type,
    m.is_default, m.base_url, m.api_key_enc,
    m.chat_model, m.embedding_model, m.embedding_base_url, m.embedding_api_key_enc, m.temperature, m.top_p,
    m.max_tokens, m.failover_model_id, m.status, m.last_test_time, m.last_test_ok, m.last_test_msg,
    m.remark, m.create_by, NOW(), 0
FROM ai_model m
WHERE m.tenant_id = 0 AND m.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM ai_model x WHERE x.id = m.id + ${tenant_id} % 100000000 * 1000);

-- ---------------- 智能体副本（ai_agent，源=平台公共行；kb_ids 置空；source/rpc_group 随源携带） ----------------
INSERT INTO ai_agent (id, agent_code, name, avatar, description, system_prompt, model_id, temperature,
    welcome_message, tool_codes, kb_ids, orchestration_type, graph_json, draft_graph_json,
    source, rpc_group, scope, tenant_id, status, remark, create_by, create_time, deleted)
SELECT a.id + ${tenant_id} % 100000000 * 1000, a.agent_code, a.name, a.avatar, a.description, a.system_prompt,
    a.model_id, a.temperature, a.welcome_message, a.tool_codes, NULL, a.orchestration_type,
    a.graph_json, a.draft_graph_json, a.source, a.rpc_group, 'tenant', ${tenant_id}, a.status, a.remark, a.create_by, NOW(), 0
FROM ai_agent a
WHERE a.scope = 'platform' AND a.tenant_id = 0 AND a.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM ai_agent x
                  WHERE x.tenant_id = ${tenant_id} AND x.agent_code = a.agent_code AND x.deleted = 0);

-- ---------------- 工具副本（ai_tool，源=平台世界行） ----------------
INSERT INTO ai_tool (id, tenant_id, tool_code, name, description, parameters_json_schema,
    source, rpc_group, config_json, status, remark, create_by, create_time, deleted)
SELECT t.id + ${tenant_id} % 100000000 * 1000, ${tenant_id}, t.tool_code, t.name, t.description,
    t.parameters_json_schema, t.source, t.rpc_group, t.config_json, t.status, t.remark,
    t.create_by, NOW(), 0
FROM ai_tool t
WHERE t.tenant_id = 0 AND t.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM ai_tool x
                  WHERE x.tenant_id = ${tenant_id} AND x.tool_code = t.tool_code AND x.deleted = 0);

-- ---------------- 引用改写：本租户世界内指向平台模型行的绑定 → 副本行 ----------------
-- 智能体绑定对话模型（含刚插入的副本与既有租户自建行；副本 id 已偏移，不会再匹配 tenant_id=0 行）
UPDATE ai_agent a
JOIN ai_model m ON a.model_id = m.id AND m.tenant_id = 0
SET a.model_id = a.model_id + ${tenant_id} % 100000000 * 1000
WHERE a.tenant_id = ${tenant_id};

-- 模型备用模型（副本行内的 failover 引用）
UPDATE ai_model c
JOIN ai_model m ON c.failover_model_id = m.id AND m.tenant_id = 0
SET c.failover_model_id = c.failover_model_id + ${tenant_id} % 100000000 * 1000
WHERE c.tenant_id = ${tenant_id};

-- 知识库绑定向量/重排模型
UPDATE ai_knowledge_base k
JOIN ai_model m ON k.embedding_model_id = m.id AND m.tenant_id = 0
SET k.embedding_model_id = k.embedding_model_id + ${tenant_id} % 100000000 * 1000
WHERE k.tenant_id = ${tenant_id};

UPDATE ai_knowledge_base k
JOIN ai_model m ON k.rerank_model_id = m.id AND m.tenant_id = 0
SET k.rerank_model_id = k.rerank_model_id + ${tenant_id} % 100000000 * 1000
WHERE k.tenant_id = ${tenant_id};
