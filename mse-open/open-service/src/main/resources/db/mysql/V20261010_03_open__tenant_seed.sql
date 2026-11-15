-- mse-target: master
-- mse-per-tenant: true
SET NAMES utf8mb4;
-- =====================================================================
-- 开放 API 目录真租户化·数据篇：平台目录行按租户播种自有副本（最终态基线）
-- 合并说明：承接原 V20261001_03_open__tenant_seed.sql，与 open_init（master 通道）
--       分文件保留各自通道头指令，绝不混通道
-- 背景：目录每租户一份——存量平台目录行（tenant_id=0，含 SQL 种子内置条目与业务自注册
--       条目）复制为各租户自有行；此后业务自注册经租户清单扇出播种持续维护 rpc 条目，
--       各租户管理端调过的启停不被覆盖
-- id 约定（确定性生成，共享库多租户不冲突；租户编号取低 8 位参与运算防 BIGINT 溢出）：
--       副本 id = 源行 id + ${tenant_id} % 100000000 * 1000
-- 引用改写：授权关系 open_app_api 按 (app_id + api_id) 校验，租户应用（tenant_id=本租户）
--       指向平台目录行的授权同步改指副本行，否则目录租户化后按副本 id 匹配不到旧授权。
--       该改写是运行期授权数据的指向修正（非种子修正，无法用 INSERT...SELECT 表达：
--       旧指向行必须改写而非新增副本），故保留 UPDATE 形态；其以「仍指向 tenant_id=0 行」
--       为 JOIN 条件，重复应用为空操作，幂等无害
-- 平台世界行（tenant_id=0）保留不动：平台域应用（tenantId=0 凭证）继续走平台目录
-- 幂等：副本按副本 id 判重、授权改写以「仍指向 tenant_id=0 行」为条件，重复应用无害
-- =====================================================================

-- ---------------- 目录副本（open_api，源=平台行） ----------------
INSERT INTO open_api (id, tenant_id, code, name, path, http_method, target, source, status,
    remark, create_by, create_time, deleted)
SELECT m.id + ${tenant_id} % 100000000 * 1000, ${tenant_id}, m.code, m.name, m.path, m.http_method,
    m.target, m.source, m.status, m.remark, m.create_by, NOW(), 0
FROM open_api m
WHERE m.tenant_id = 0 AND m.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM open_api x WHERE x.id = m.id + ${tenant_id} % 100000000 * 1000);

-- ---------------- 授权改写：本租户应用指向平台目录行的授权 → 副本行 ----------------
UPDATE open_app_api g
JOIN open_api m ON g.api_id = m.id AND m.tenant_id = 0
SET g.api_id = g.api_id + ${tenant_id} % 100000000 * 1000
WHERE g.tenant_id = ${tenant_id};
