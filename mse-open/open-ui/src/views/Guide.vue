<template>
  <div class="page">
    <el-card header="开放平台接入指南">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="当前模式">
          <el-tag :type="authStore.embedded ? 'success' : 'info'">
            {{ authStore.embedded ? '嵌入主应用（wujie）' : '独立运行' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="登录用户">{{ authStore.nickname || '-' }}（{{ authStore.platform ? '平台端' : `租户 ${authStore.tenantId || '-'}` }}）</el-descriptions-item>
      </el-descriptions>

      <el-steps class="steps" :active="4" align-center>
        <el-step title="注册应用" description="应用管理 → 接入应用，保存一次性展示的 AppKey/AppSecret" />
        <el-step title="授权 API" description="应用管理 → 授权，勾选允许调用的开放 API" />
        <el-step title="换取凭证" description="接入方以 appKey/appSecret 调 /open/oauth/token 换 access_token" />
        <el-step title="调用开放 API" description="携带 Bearer token 调用 /open/api/**，数据隔离在绑定租户" />
      </el-steps>
    </el-card>

    <el-card header="接口约定（所有开放 API 通用）" class="mt">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="Base URL">
          直连服务 <code>http://{host}:8092</code>（单体 <code>:8080</code> / 微服务网关 <code>:48080</code>），下文以 <code>{BASE}</code> 指代
        </el-descriptions-item>
        <el-descriptions-item label="认证方式">
          除换 token 外全部请求携带 <code>Authorization: Bearer {accessToken}</code>；与人工登录态完全独立（client credentials 语义）
        </el-descriptions-item>
        <el-descriptions-item label="数据隔离">
          应用绑定租户即数据域：绑定具体租户只能读写该租户数据；绑定平台域（tenantId=0）可访问全部租户
        </el-descriptions-item>
        <el-descriptions-item label="响应结构">
          HTTP 200 + <code>{ "code": 0, "message": "ok", "data": ... }</code>；code=0 成功，非 0 见 message；机器凭证语义下 401/403/404/429 以真实 HTTP 状态码返回
        </el-descriptions-item>
        <el-descriptions-item label="错误语义">
          <code>401</code> 凭证缺失/无效/过期/应用停用 · <code>403</code> 未授权该 API · <code>404</code> API 未开放或路径不存在 · <code>429</code> 触发限流（应用可单独设置 QPS）
        </el-descriptions-item>
        <el-descriptions-item label="审计">
          全部调用（含被拒请求）记录在「调用日志」，可按应用/API/时间检索
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card header="1. 换取 access_token（唯一公开端点，无需鉴权）" class="mt">
      <el-descriptions :column="1" border class="mb">
        <el-descriptions-item label="端点"><code>POST {BASE}/open/oauth/token</code></el-descriptions-item>
        <el-descriptions-item label="请求体">JSON <code>{"appKey":"...","appSecret":"..."}</code>；也支持表单/查询参数提交（便于各语言对接）</el-descriptions-item>
        <el-descriptions-item label="响应 data"><code>{ "accessToken": "...", "expiresIn": 7200, "tokenType": "Bearer" }</code>，有效期默认 2 小时，请缓存复用；泄露时在管理端停用应用即时失效</el-descriptions-item>
      </el-descriptions>
      <pre class="code">curl -X POST http://localhost:8092/open/oauth/token \
  -H "Content-Type: application/json" \
  -d '{"appKey":"你的AppKey","appSecret":"你的AppSecret"}'</pre>
    </el-card>

    <el-card header="2. 开放 API 清单（需在「应用管理 → 授权」勾选后才可调用）" class="mt">
      <el-alert type="info" :closable="false" show-icon class="mb"
                title="按资源分组折叠；「授权码」即授权弹窗里的 API 标识。路径中 {id} 为资源主键。" />
      <el-collapse>
        <el-collapse-item title="用户（/open/api/user/**，12 个）" name="user">
          <el-table :data="userApis" border size="small">
            <el-table-column label="接口" min-width="240">
              <template #default="{ row }"><span class="mono">{{ row.method }} {{ row.path }}</span></template>
            </el-table-column>
            <el-table-column prop="desc" label="说明" min-width="200" />
            <el-table-column label="授权码" min-width="180">
              <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
            </el-table-column>
          </el-table>
          <pre class="code mt">pageNum/pageSize 分页（默认 1/10）；可选过滤 username、status（0 启用 1 停用）、deptId

# 分页查询
curl "{BASE}/open/api/user/page?pageNum=1&pageSize=10" -H "Authorization: Bearer {token}"

# 按用户名精确查询（注册/登录场景）
curl "{BASE}/open/api/user/by-username?username=oa_user01" -H "Authorization: Bearer {token}"

# 查询单个 / 删除
curl "{BASE}/open/api/user/1001" -H "Authorization: Bearer {token}"
curl -X DELETE "{BASE}/open/api/user/1001" -H "Authorization: Bearer {token}"

# 创建用户（username 必填≤30；password 必填 6-32 位）
curl -X POST {BASE}/open/api/user \
  -H "Authorization: Bearer {token}" -H "Content-Type: application/json" \
  -d '{"username":"oa_user01","password":"pass123456","nickname":"OA用户","mobile":"13800000000","email":"a@b.c","deptId":null,"roleIds":[]}'

# 更新用户（id 必填，其余字段选择性更新；status: 0 启用 1 停用）
curl -X PUT {BASE}/open/api/user \
  -H "Authorization: Bearer {token}" -H "Content-Type: application/json" \
  -d '{"id":1001,"nickname":"新名字","mobile":"13900000000","status":0,"deptId":100}'

# 启停 / 重置密码（密码 6-32 位）
curl -X PUT "{BASE}/open/api/user/1001/status" \
  -H "Authorization: Bearer {token}" -H "Content-Type: application/json" -d '{"status":1}'
curl -X PUT "{BASE}/open/api/user/1001/password" \
  -H "Authorization: Bearer {token}" -H "Content-Type: application/json" -d '{"password":"newpass123"}'

# 角色：查询 / 覆盖式分配（传空数组 = 清空角色）
curl "{BASE}/open/api/user/1001/role-ids" -H "Authorization: Bearer {token}"
curl -X PUT "{BASE}/open/api/user/1001/role-ids" \
  -H "Authorization: Bearer {token}" -H "Content-Type: application/json" -d '{"roleIds":[1,2]}'

# 扩展属性：读取 / 全量覆盖写入（JSON 对象 ≤4KB，接入方自有字段如工号、职级）
curl "{BASE}/open/api/user/1001/extend" -H "Authorization: Bearer {token}"
curl -X PUT "{BASE}/open/api/user/1001/extend" \
  -H "Authorization: Bearer {token}" -H "Content-Type: application/json" \
  -d '{"empNo":"E8801","level":"P7"}'</pre>
        </el-collapse-item>

        <el-collapse-item title="部门（/open/api/dept/**，4 个）" name="dept">
          <el-table :data="deptApis" border size="small">
            <el-table-column label="接口" min-width="240">
              <template #default="{ row }"><span class="mono">{{ row.method }} {{ row.path }}</span></template>
            </el-table-column>
            <el-table-column prop="desc" label="说明" min-width="200" />
            <el-table-column label="授权码" min-width="180">
              <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
            </el-table-column>
          </el-table>
          <pre class="code mt"># 部门树（id/parentId/name/sort/status）
curl "{BASE}/open/api/dept/tree" -H "Authorization: Bearer {token}"

# 创建（parentId 空 = 顶级；sort 默认 0）
curl -X POST {BASE}/open/api/dept \
  -H "Authorization: Bearer {token}" -H "Content-Type: application/json" \
  -d '{"parentId":100,"name":"研发部","sort":1}'

# 更新（id 必填）
curl -X PUT {BASE}/open/api/dept \
  -H "Authorization: Bearer {token}" -H "Content-Type: application/json" \
  -d '{"id":101,"parentId":100,"name":"研发一部","sort":2,"status":0}'

# 删除（有子部门/在职用户时后端拒绝）
curl -X DELETE "{BASE}/open/api/dept/101" -H "Authorization: Bearer {token}"</pre>
        </el-collapse-item>

        <el-collapse-item title="角色（/open/api/role/**，2 个）" name="role">
          <el-table :data="roleApis" border size="small">
            <el-table-column label="接口" min-width="240">
              <template #default="{ row }"><span class="mono">{{ row.method }} {{ row.path }}</span></template>
            </el-table-column>
            <el-table-column prop="desc" label="说明" min-width="200" />
            <el-table-column label="授权码" min-width="180">
              <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
            </el-table-column>
          </el-table>
          <pre class="code mt"># 角色列表（id/name/code/sort/dataScope）
curl "{BASE}/open/api/role/list" -H "Authorization: Bearer {token}"

# 创建角色（name/code 必填；dataScope: 1 全部 2 自定义 3 本部门 4 本部门及以下 5 仅本人，默认 5）
# 开放链路不提供菜单/权限点授权，权限矩阵仍由管理员在管理端维护
curl -X POST {BASE}/open/api/role \
  -H "Authorization: Bearer {token}" -H "Content-Type: application/json" \
  -d '{"name":"外部系统角色","code":"ext_role","sort":9,"dataScope":5}'</pre>
        </el-collapse-item>

        <el-collapse-item title="身份绑定（/open/api/bind/**，4 个）" name="bind">
          <el-table :data="bindApis" border size="small">
            <el-table-column label="接口" min-width="240">
              <template #default="{ row }"><span class="mono">{{ row.method }} {{ row.path }}</span></template>
            </el-table-column>
            <el-table-column prop="desc" label="说明" min-width="200" />
            <el-table-column label="授权码" min-width="180">
              <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
            </el-table-column>
          </el-table>
          <pre class="code mt"># 创建绑定：外部系统用户 ↔ 中台账号（同一外部标识租户内唯一）
curl -X POST {BASE}/open/api/bind \
  -H "Authorization: Bearer {token}" -H "Content-Type: application/json" \
  -d '{"userId":1001,"sourceSystem":"oa","externalUserId":"emp-8801","remark":"OA 同步"}'

# 用外部标识反查中台账号（未绑定返回 data=null）
curl "{BASE}/open/api/bind/resolve?sourceSystem=oa&externalUserId=emp-8801" \
  -H "Authorization: Bearer {token}"

# 一个中台账号的全部外部身份
curl "{BASE}/open/api/bind/by-user/1001" -H "Authorization: Bearer {token}"

# 解绑（bindId 为创建绑定时返回的主键）
curl -X DELETE "{BASE}/open/api/bind/1" -H "Authorization: Bearer {token}"</pre>
        </el-collapse-item>

        <el-collapse-item title="业务能力（业务积木自注册，实时清单见「API 目录」页）" name="biz">
          <el-alert type="info" :closable="false" show-icon class="mb"
                    title="以下 API 由各业务积木经 open-api 契约在启动时自动注册（source=rpc），授权与内置 API 相同；业务积木不部署时调用返回 503/500。" />
          <el-table :data="bizApis" border size="small">
            <el-table-column label="接口" min-width="240">
              <template #default="{ row }"><span class="mono">{{ row.method }} {{ row.path }}</span></template>
            </el-table-column>
            <el-table-column prop="desc" label="说明" min-width="200" />
            <el-table-column label="授权码" min-width="180">
              <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
            </el-table-column>
          </el-table>
          <pre class="code mt"># 发起 OA 流程（operatorUserId 为租户内发起人）
curl -X POST {BASE}/open/api/oa/process   -H "Authorization: Bearer {token}" -H "Content-Type: application/json"   -d '{"modelId":1,"businessKey":"ERP-2026-0001","dataValues":"{}","summary":"对账单审批","operatorUserId":1001}'

# 查询待办（外部系统拉待办提醒）
curl "{BASE}/open/api/oa/todo?operatorUserId=1001&amp;pageNum=1&amp;pageSize=10"   -H "Authorization: Bearer {token}"

# 调用数据中台已发布 API（受控通道：SQL 防护 + 租户谓词 + 行数上限）
curl -X POST {BASE}/open/api/data/api/invoke   -H "Authorization: Bearer {token}" -H "Content-Type: application/json"   -d '{"apiPath":"/cust/orders","params":{"status":"PAID"},"pageNum":1,"pageSize":20}'

# AI 对话（调用已发布智能体）
curl -X POST {BASE}/open/api/ai/chat   -H "Authorization: Bearer {token}" -H "Content-Type: application/json"   -d '{"agentCode":"assistant","message":"汇总本月订单"}'</pre>
        </el-collapse-item>
      </el-collapse>
    </el-card>

    <el-card header="3. 端到端示例（完整接入流）" class="mt">
      <pre class="code"># ① 换取凭证
TOKEN=$(curl -s -X POST http://localhost:8092/open/oauth/token \
  -H "Content-Type: application/json" \
  -d '{"appKey":"你的AppKey","appSecret":"你的AppSecret"}' | jq -r '.data.accessToken')

# ② 分页查询用户
curl "http://localhost:8092/open/api/user/page?pageNum=1&amp;pageSize=10" \
  -H "Authorization: Bearer $TOKEN"

# ③ 创建用户（全量纳管场景）
curl -X POST http://localhost:8092/open/api/user \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"username":"oa_user01","password":"pass123456","nickname":"OA用户","roleIds":[]}'

# ④ 身份绑定 + 反查
curl -X POST http://localhost:8092/open/api/bind \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"userId":1001,"sourceSystem":"oa","externalUserId":"emp-8801"}'
curl "http://localhost:8092/open/api/bind/resolve?sourceSystem=oa&amp;externalUserId=emp-8801" \
  -H "Authorization: Bearer $TOKEN"</pre>
      <el-alert class="mt" type="warning" :closable="false" show-icon
                title="错误语义：401 凭证缺失/无效/过期/应用停用 · 403 未授权该 API · 404 API 未开放 · 429 限流。全部调用在「调用日志」可审计。" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { useAuthStore } from '../stores/auth'

const authStore = useAuthStore()

interface ApiRow { method: string; path: string; desc: string; code: string }

// 与 open_api 种子清单（open_init.sql 8001-8022）一一对应
const userApis: ApiRow[] = [
  { method: 'GET', path: '/open/api/user/page', desc: '分页查询用户', code: 'open:user:page' },
  { method: 'GET', path: '/open/api/user/{id}', desc: '查询单个用户', code: 'open:user:get' },
  { method: 'GET', path: '/open/api/user/by-username', desc: '按用户名精确查询', code: 'open:user:get-by-username' },
  { method: 'POST', path: '/open/api/user', desc: '创建用户', code: 'open:user:create' },
  { method: 'PUT', path: '/open/api/user', desc: '更新用户', code: 'open:user:update' },
  { method: 'PUT', path: '/open/api/user/{id}/status', desc: '启用/停用用户', code: 'open:user:status' },
  { method: 'PUT', path: '/open/api/user/{id}/password', desc: '重置用户密码', code: 'open:user:reset-password' },
  { method: 'DELETE', path: '/open/api/user/{id}', desc: '删除用户', code: 'open:user:delete' },
  { method: 'GET', path: '/open/api/user/{id}/role-ids', desc: '查询用户角色', code: 'open:user:role-ids' },
  { method: 'PUT', path: '/open/api/user/{id}/role-ids', desc: '覆盖式分配角色', code: 'open:user:assign-roles' },
  { method: 'GET', path: '/open/api/user/{id}/extend', desc: '读用户扩展属性', code: 'open:user:extend-get' },
  { method: 'PUT', path: '/open/api/user/{id}/extend', desc: '全量覆盖写扩展属性（≤4KB）', code: 'open:user:extend-put' }
]
const deptApis: ApiRow[] = [
  { method: 'GET', path: '/open/api/dept/tree', desc: '部门树查询', code: 'open:dept:tree' },
  { method: 'POST', path: '/open/api/dept', desc: '创建部门', code: 'open:dept:create' },
  { method: 'PUT', path: '/open/api/dept', desc: '更新部门', code: 'open:dept:update' },
  { method: 'DELETE', path: '/open/api/dept/{id}', desc: '删除部门', code: 'open:dept:delete' }
]
const roleApis: ApiRow[] = [
  { method: 'GET', path: '/open/api/role/list', desc: '角色列表查询', code: 'open:role:list' },
  { method: 'POST', path: '/open/api/role', desc: '创建角色（不含权限矩阵）', code: 'open:role:create' }
]
const bindApis: ApiRow[] = [
  { method: 'POST', path: '/open/api/bind', desc: '创建身份绑定', code: 'open:bind:create' },
  { method: 'GET', path: '/open/api/bind/resolve', desc: '按外部标识解析中台账号', code: 'open:bind:resolve' },
  { method: 'GET', path: '/open/api/bind/by-user/{userId}', desc: '查询用户全部外部身份', code: 'open:bind:by-user' },
  { method: 'DELETE', path: '/open/api/bind/{bindId}', desc: '解除身份绑定', code: 'open:bind:delete' }
]
// 业务积木经 open-api 契约自注册（mse-oa/mse-mp/mse-data/mse-ai）；以「API 目录」页实时清单为准
const bizApis: ApiRow[] = [
  { method: 'POST', path: '/open/api/oa/process', desc: '发起 OA 流程', code: 'open:oa:process-start' },
  { method: 'GET', path: '/open/api/oa/process/{processInstanceId}', desc: '查询流程详情', code: 'open:oa:process-detail' },
  { method: 'GET', path: '/open/api/oa/todo', desc: '查询待办任务', code: 'open:oa:todo-page' },
  { method: 'GET', path: '/open/api/mp/fans', desc: '查询公众号粉丝', code: 'open:mp:fans-page' },
  { method: 'GET', path: '/open/api/mp/messages', desc: '查询消息流水', code: 'open:mp:messages-page' },
  { method: 'POST', path: '/open/api/data/api/invoke', desc: '调用已发布数据 API', code: 'open:data:api-invoke' },
  { method: 'POST', path: '/open/api/ai/chat', desc: 'AI 对话', code: 'open:ai:chat' }
]
</script>

<style scoped>
.steps {
  margin-top: 20px;
}
.mt {
  margin-top: 16px;
}
.mb {
  margin-bottom: 12px;
}
.code {
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-light);
  border-radius: 4px;
  padding: 10px 12px;
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  overflow: auto;
}
.mono {
  font-family: Consolas, Monaco, monospace;
}
</style>
