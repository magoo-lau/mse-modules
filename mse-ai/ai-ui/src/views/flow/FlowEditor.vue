<template>
  <div class="flow-editor">
    <!-- 顶栏 -->
    <div class="topbar">
      <el-button link @click="backToAgent">← 返回</el-button>
      <el-breadcrumb separator="/" class="crumb">
        <el-breadcrumb-item>
          <a @click.prevent="gotoRoot">{{ agent.name }}（编排画布）</a>
        </el-breadcrumb-item>
        <el-breadcrumb-item v-for="(lv, i) in levels" :key="i" v-show="i > 0">
          <a @click.prevent="gotoLevel(i)">{{ lv.title }}</a>
        </el-breadcrumb-item>
      </el-breadcrumb>
      <div class="ops">
          <el-button size="small" @click="openGlobals">全局参数</el-button>
          <el-button size="small" @click="importVisible = true">导入 DSL</el-button>
        <el-button size="small" @click="exportDsl">导出 DSL</el-button>
        <el-button size="small" type="warning" @click="openDebug">试运行</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="save">保存草稿</el-button>
        <el-button size="small" type="success" :loading="publishing" @click="publish">
          发布
        </el-button>
      </div>
    </div>

    <div class="canvas-wrap">
      <!-- 节点面板 -->
      <div class="palette">
        <div v-for="(group, name) in paletteGroups" :key="name" class="palette-group">
          <div class="group-name">{{ name }}</div>
          <div v-for="m in group" :key="m.type" class="palette-item" @click="addNode(m.type)">
            <span class="icon" :style="{ background: m.color + '1a', color: m.color }">{{ m.icon }}</span>
            <span>{{ m.label }}</span>
          </div>
        </div>
      </div>

      <!-- 画布 -->
      <div class="canvas">
        <VueFlow :nodes="initialNodes" :edges="initialEdges" :default-edge-options="{ animated: true }"
          :min-zoom="0.2" :max-zoom="2" :nodes-draggable="true"
          :delete-key-code="['Delete', 'Backspace']" fit-view-on-init
          @node-click="onNodeClick" @pane-click="selectedId = ''" @connect="onConnect">
          <template #node-flow="nodeProps">
            <FlowCard v-bind="nodeProps" />
          </template>
          <Background :gap="16" />
          <Controls>
            <ControlButton title="撤回（Ctrl+Z）" :disabled="!canUndo" @click="undo">
              <svg viewBox="0 0 24 24" class="ctrl-svg">
                <path d="M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88 3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z" />
              </svg>
            </ControlButton>
            <ControlButton title="重做（Ctrl+Shift+Z）" :disabled="!canRedo" @click="redo">
              <svg viewBox="0 0 24 24" class="ctrl-svg">
                <path d="M18.4 10.6C16.55 8.99 14.15 8 11.5 8c-4.65 0-8.58 3.03-9.96 7.22L3.9 16c1.05-3.19 4.06-5.5 7.6-5.5 1.95 0 3.73.72 5.12 1.88L13 16h9V7l-3.6 3.6z" />
              </svg>
            </ControlButton>
            <ControlButton title="使用说明" @click="helpVisible = !helpVisible">
              <span class="ctrl-icon help-icon">?</span>
            </ControlButton>
          </Controls>
          <MiniMap pannable zoomable />
        </VueFlow>
        <!-- 使用说明（? 按钮展开；含锁定的用途解释） -->
        <div v-if="helpVisible" class="canvas-help">
          <div class="help-head">
            <span>画布使用说明</span>
            <el-button link size="small" @click="helpVisible = false">收起</el-button>
          </div>
          <ul>
            <li><b>添加节点</b>：点击左侧节点面板；点击画布节点选中，在右侧属性面板配置。</li>
            <li><b>连线</b>：拖动节点边缘的连接点到目标节点；条件/分类节点按分支出口分别连线。</li>
            <li><b>删除</b>：选中节点或连线后按 Delete / Backspace，或在右侧属性面板点「删除节点」。</li>
            <li><b>撤回 / 重做</b>：Ctrl+Z / Ctrl+Shift+Z（或左下角撤回/重做按钮），覆盖增删节点、连线、移动与属性修改。</li>
            <li><b>锁定</b>：工具栏的锁图标用于锁定画布交互——锁定后节点不可拖动/选中、不可改连线（只读浏览，防误拖），再点一次解锁恢复编辑。图标显示「锁」= 已锁定，显示「开锁」= 可编辑。</li>
            <li><b>缩放与平移</b>：滚轮缩放，空白处按住拖动平移；+/−/矩形按钮为放大、缩小、适配视图。</li>
            <li><b>迭代子流程</b>：迭代节点在右侧属性面板点「打开子流程」进入子画布，点面包屑返回上级。</li>
          </ul>
        </div>
      </div>

      <!-- 属性面板 -->
      <div class="prop-panel">
        <template v-if="selected">
          <div class="prop-head">
            <span>{{ meta.icon }} {{ meta.label }}</span>
            <el-button link type="danger" size="small" @click="removeSelected">删除节点</el-button>
          </div>
          <el-form label-position="top" size="small" class="prop-form">
            <el-form-item label="节点标题">
              <el-input v-model="selected.title" />
            </el-form-item>

            <!-- 开始 -->
            <template v-if="selected.type === 'start'">
              <el-form-item v-for="(v, i) in selected.data.variables" :key="i"
                :label="`入参 ${i + 1}`">
                <div class="row">
                  <el-input v-model="v.name" placeholder="变量名" />
                  <el-checkbox v-model="v.required">必填</el-checkbox>
                  <el-button link type="danger" @click="selected.data.variables.splice(i, 1)">删</el-button>
                </div>
              </el-form-item>
              <el-button size="small" @click="addStartVar">添加入参</el-button>
              <div class="hint" style="margin-top: 6px">
                入参在试运行时填值；下游节点经「插入变量」引用 <span v-pre>{{开始节点id.入参名}}</span>，
                或直接引用 <span v-pre>{{sys.query}}</span> 取用户输入。
              </div>
            </template>

            <!-- LLM -->
            <template v-if="selected.type === 'llm'">
              <el-form-item label="模型（空=智能体默认）">
                <el-select v-model="selected.data.modelId" clearable filterable>
                  <el-option v-for="m in models" :key="m.id" :label="`${m.name}（${m.chatModel}）`" :value="m.id" />
                </el-select>
              </el-form-item>
              <TemplateField v-model="selected.data.systemPrompt" :groups="upstreamVarGroups" label="系统提示词" :rows="3" />
              <TemplateField v-model="selected.data.prompt" :groups="upstreamVarGroups" label="提示词" :rows="5" />
              <el-form-item label="温度">
                <el-slider v-model="selected.data.temperature" :min="0" :max="2" :step="0.1" />
              </el-form-item>
              <div class="row">
                <el-checkbox v-model="selected.data.memoryEnabled">多轮记忆</el-checkbox>
                <el-input-number v-if="selected.data.memoryEnabled" v-model="selected.data.windowSize" :min="1"
                  :max="40" size="small" />
              </div>
            </template>

            <!-- 知识检索 -->
            <template v-if="selected.type === 'knowledge'">
              <el-form-item label="知识库">
                <el-select v-model="kbSelection" multiple collapse-tags style="width: 100%">
                  <el-option v-for="kb in kbs" :key="kb.id" :label="kb.name" :value="kb.id" />
                </el-select>
              </el-form-item>
              <TemplateField v-model="selected.data.query" :groups="upstreamVarGroups" label="检索问题" :rows="2" />
              <el-form-item label="topK">
                <el-input-number v-model="selected.data.topK" :min="1" :max="20" />
              </el-form-item>
            </template>

            <!-- 工具 -->
            <template v-if="selected.type === 'tool'">
              <el-form-item label="工具">
                <el-select v-model="selected.data.toolCode" filterable>
                  <el-option v-for="t in tools" :key="t.toolCode" :value="t.toolCode"
                    :label="`${t.name || t.toolCode}（${t.toolCode}）`">
                    <div class="tool-option">
                      <span>{{ t.name || t.toolCode }}（{{ t.toolCode }}）</span>
                      <span v-if="t.description" class="tool-option-desc">{{ t.description }}</span>
                    </div>
                  </el-option>
                </el-select>
              </el-form-item>
              <div v-if="selectedToolDesc" class="flow-hint">{{ selectedToolDesc }}</div>
              <SchemaArgEditor v-if="toolSchemaProps.length" v-model="selected.data.args"
                               :schema-props="toolSchemaProps" :groups="upstreamVarGroups" />
              <KvEditor v-model="selected.data.args" :groups="upstreamVarGroups" key-label="其他参数"
                        :exclude-keys="toolSchemaProps.map((p) => p.name)" />
            </template>

            <!-- 数据查询 -->
            <template v-if="selected.type === 'dataset'">
              <TemplateField v-model="selected.data.sql" :groups="upstreamVarGroups" label="查询 SQL（只读 SELECT）" :rows="5" />
              <el-form-item label="数据源">
                <el-select v-model="selected.data.datasourceId" filterable style="width: 100%">
                  <el-option v-for="ds in datasourceOptions" :key="String(ds.id)" :value="ds.id"
                    :label="`#${ds.id} ${ds.name}`" />
                </el-select>
                <div class="hint" style="margin-top: 4px">
                  {{ datasourceHint }}
                </div>
              </el-form-item>
              <el-form-item label="行数上限">
                <el-input-number v-model="selected.data.rowLimit" :min="1" :max="1000" />
              </el-form-item>
              <el-form-item label="查询工具（空=默认 data_query）">
                <el-select v-model="selected.data.toolCode" clearable filterable>
                  <el-option v-for="t in tools" :key="t.toolCode" :value="t.toolCode"
                    :label="`${t.name || t.toolCode}`">
                    <div class="tool-option">
                      <span>{{ t.name || t.toolCode }}</span>
                      <span v-if="t.description" class="tool-option-desc">{{ t.description }}</span>
                    </div>
                  </el-option>
                </el-select>
              </el-form-item>
            </template>

            <!-- 条件分支 -->
            <template v-if="selected.type === 'condition'">
              <div v-for="(c, ci) in selected.data.cases" :key="ci" class="case-block">
                <div class="row">
                  <b>{{ ci === 0 ? 'IF' : 'ELIF' }} {{ ci + 1 }}</b>
                  <el-select v-model="c.logic" size="small" style="width: 80px">
                    <el-option value="and" label="且" />
                    <el-option value="or" label="或" />
                  </el-select>
                  <el-button link type="danger" size="small" style="margin-left: auto"
                    @click="removeCase(ci)">删</el-button>
                </div>
                <div v-for="(cond, di) in c.conditions" :key="di" class="cond-item">
                  <div class="cond-line">
                    <TemplateField v-model="cond.left" :groups="upstreamVarGroups" :inline="true" />
                    <el-button link type="danger" size="small" @click="c.conditions.splice(di, 1)">删</el-button>
                  </div>
                  <div class="cond-line">
                    <el-select v-model="cond.op" size="small" style="flex: 1; min-width: 88px">
                      <el-option v-for="op in CONDITION_OPS" :key="op.value" :value="op.value" :label="op.label" />
                    </el-select>
                  </div>
                  <div class="cond-line">
                    <TemplateField v-model="cond.right" :groups="upstreamVarGroups" :inline="true"
                      placeholder="比较值（可含变量）" />
                  </div>
                </div>
                <el-button size="small" text @click="c.conditions.push({ left: '', op: 'not_empty', right: '' })">
                  + 条件
                </el-button>
              </div>
              <el-button size="small" @click="addCase">添加分支</el-button>
              <div class="hint">ELSE 分支为「全部不命中」的默认出口</div>
            </template>

            <!-- 问题分类 -->
            <template v-if="selected.type === 'classifier'">
              <TemplateField v-model="selected.data.query" :groups="upstreamVarGroups" label="分类文本" :rows="2" />
              <el-form-item label="模型（空=智能体默认）">
                <el-select v-model="selected.data.modelId" clearable filterable>
                  <el-option v-for="m in models" :key="m.id" :label="`${m.name}（${m.chatModel}）`" :value="m.id" />
                </el-select>
              </el-form-item>
              <div v-for="(c, i) in selected.data.classes" :key="i" class="row">
                <el-input v-model="c.name" placeholder="分类名" style="width: 110px" />
                <el-input v-model="c.description" placeholder="分类描述" />
                <el-button link type="danger" @click="selected.data.classes.splice(i, 1)">删</el-button>
              </div>
              <el-button size="small" @click="selected.data.classes.push({ name: '', description: '' })">添加分类
              </el-button>
            </template>

            <!-- 参数提取 -->
            <template v-if="selected.type === 'extractor'">
              <TemplateField v-model="selected.data.query" :groups="upstreamVarGroups" label="抽取来源" :rows="2" />
              <el-form-item label="模型（空=智能体默认）">
                <el-select v-model="selected.data.modelId" clearable filterable>
                  <el-option v-for="m in models" :key="m.id" :label="`${m.name}（${m.chatModel}）`" :value="m.id" />
                </el-select>
              </el-form-item>
              <div v-for="(f, i) in selected.data.fields" :key="i" class="row">
                <el-input v-model="f.name" placeholder="字段名" style="width: 100px" />
                <el-input v-model="f.description" placeholder="字段说明" />
                <el-button link type="danger" @click="selected.data.fields.splice(i, 1)">删</el-button>
              </div>
              <el-button size="small"
                @click="selected.data.fields.push({ name: '', type: 'string', description: '' })">添加字段</el-button>
            </template>

            <!-- 代码执行 -->
            <template v-if="selected.type === 'code'">
              <KvEditor v-model="selected.data.args" :groups="upstreamVarGroups" key-label="入参名" />
              <el-form-item label="JavaScript（定义 function main(args){return {...}}）">
                <el-input v-model="selected.data.code" type="textarea" :rows="10" class="mono" />
              </el-form-item>
            </template>

            <!-- HTTP -->
            <template v-if="selected.type === 'http'">
              <div class="row">
                <el-select v-model="selected.data.method" style="width: 90px">
                  <el-option v-for="m in ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']" :key="m" :value="m" :label="m" />
                </el-select>
                <el-input v-model="selected.data.url" placeholder="https://..." />
              </div>
              <KvEditor v-model="selected.data.headers" :groups="upstreamVarGroups" key-label="请求头" />
              <TemplateField v-if="selected.data.method !== 'GET'" v-model="selected.data.body" :groups="upstreamVarGroups"
                label="请求体" :rows="3" />
              <el-form-item label="超时（毫秒）">
                <el-input-number v-model="selected.data.timeout" :min="1000" :max="60000" :step="1000" />
              </el-form-item>
              <div class="hint">
                出参 status_code / headers / body / json 均可在下游引用；响应为 JSON 时支持按
                <span v-pre>{{节点id.json.子字段}}</span> 深层取值。
              </div>
            </template>

            <!-- 模板转换 -->
            <template v-if="selected.type === 'template'">
              <TemplateField v-model="selected.data.template" :groups="upstreamVarGroups" label="Jinja2 模板" :rows="6" />
              <div class="hint">
                用途：把多个上游变量按模板拼接/排版为一段文本（如 <span v-pre>{{llm1.text}}</span> 与
                <span v-pre>{{query.markdown}}</span> 组装成报告），输出 output 供下游引用；只做文本组装，不执行逻辑。
              </div>
            </template>

            <!-- 变量赋值 -->
            <template v-if="selected.type === 'assign'">
              <div v-if="!globalParams.length" class="flow-hint">
                尚未定义全局参数：请点顶栏「全局参数」先创建，赋值目标即全局参数（env.参数名）。
              </div>
              <template v-for="(a, i) in selected.data.assignments" :key="i">
                <div class="assign-line">
                  <el-select v-model="a.name" filterable allow-create default-first-option placeholder="全局参数"
                    style="width: 140px">
                    <el-option v-for="g in globalParams" :key="g.name" :value="g.name"
                      :label="g.type === 'string' ? g.name : `${g.name}（${globalTypeLabel(g.type)}）`" />
                  </el-select>
                  <el-select v-model="a.op" placeholder="操作" style="width: 78px">
                    <el-option v-for="op in assignOpsForType(globalTypeOf(a.name))" :key="op.value" :value="op.value"
                      :label="op.label" />
                  </el-select>
                  <el-button link type="danger" @click="selected.data.assignments.splice(i, 1)">删</el-button>
                </div>
                <div v-if="a.op === 'reset'" class="hint" style="padding-left: 4px">执行时恢复为该参数的默认值</div>
                <div v-else class="assign-line">
                  <TemplateField v-model="a.value" :groups="upstreamVarGroups" :inline="true"
                    :placeholder="assignValuePlaceholder(a)" />
                </div>
              </template>
              <el-button size="small" @click="selected.data.assignments.push({ name: '', value: '', op: 'set' })">添加赋值
              </el-button>
              <div class="hint" style="margin-top: 6px">
                执行到本节点时按操作写全局参数（覆盖/重置/追加/累加/累减/合并），
                后续节点引用 <span v-pre>{{env.参数名}}</span> 即得新值。
              </div>
            </template>

            <!-- 聚合 -->
            <template v-if="selected.type === 'aggregator'">
              <el-form-item label="候选变量（按序取首个非空）">
                <el-select v-model="selected.data.variables" multiple filterable allow-create
                  default-first-option style="width: 100%">
                  <el-option-group v-for="g in varGroupOptions" :key="g.key" :label="`${g.icon || ''}${g.label}`">
                    <el-option v-for="v in g.vars" :key="v.ref" :value="v.ref" :label="`{{${v.ref}}}`">
                      <span style="margin-right: 8px">{{ wrapRef(v.ref) }}</span>
                      <span style="float: right; color: var(--el-text-color-secondary); font-size: 11px">{{ v.label }}</span>
                    </el-option>
                  </el-option-group>
                </el-select>
              </el-form-item>
            </template>

            <!-- 迭代 -->
            <template v-if="selected.type === 'iteration'">
              <el-form-item label="数组变量">
                <el-select v-model="selected.data.items" filterable allow-create default-first-option>
                  <el-option-group v-for="g in varGroupOptions" :key="g.key" :label="`${g.icon || ''}${g.label}`">
                    <el-option v-for="v in g.vars" :key="v.ref" :value="v.ref" :label="`{{${v.ref}}}`">
                      <span style="margin-right: 8px">{{ wrapRef(v.ref) }}</span>
                      <span style="float: right; color: var(--el-text-color-secondary); font-size: 11px">{{ v.label }}</span>
                    </el-option>
                  </el-option-group>
                </el-select>
              </el-form-item>
              <el-form-item label="输出变量（每项收集值，空=首个回复）">
                <el-select v-model="selected.data.outputSelector" clearable filterable allow-create
                  default-first-option>
                  <el-option value="" label="（自动：子图首个回复）" />
                  <el-option v-for="o in iterationOutputOptions" :key="o.ref" :value="o.ref"
                    :label="`${o.ref}（${o.label}）`" />
                </el-select>
                <div v-if="!selected.data.graph" class="hint" style="margin-top: 4px">
                  先打开子流程编排循环体，输出变量取循环体内节点的产出。
                </div>
              </el-form-item>
              <el-button size="small" type="primary" plain @click="openSubgraph">打开子流程 →</el-button>
            </template>

            <!-- 回复 -->
            <template v-if="selected.type === 'answer'">
              <TemplateField v-model="selected.data.content" :groups="upstreamVarGroups" label="回复内容" :rows="5" />
            </template>

            <!-- 结束 -->
            <template v-if="selected.type === 'end'">
              <KvEditor v-model="selected.data.outputs" :groups="upstreamVarGroups" key-label="输出名" value-is-ref />
            </template>
          </el-form>
        </template>
        <el-empty v-else-if="selected" description="公共画布只读浏览，复制为自有智能体后可编辑" :image-size="60" />
        <el-empty v-else description="点击节点配置属性" :image-size="60" />
      </div>
    </div>

    <!-- 试运行抽屉 -->
    <el-drawer v-model="debugVisible" title="试运行" size="480px">
      <el-form label-position="top" size="small">
        <el-form-item label="用户输入">
          <el-input v-model="debugQuery" type="textarea" :rows="2" placeholder="作为 sys.query 参与执行" />
        </el-form-item>
        <el-form-item v-for="v in debugVars" :key="v.name" :label="v.name">
          <el-input v-model="v.value" />
        </el-form-item>
        <el-button type="primary" :loading="debugRunning" @click="runDebug">运行</el-button>
      </el-form>
      <div class="trace-list">
        <div v-for="t in traceList" :key="t.nodeId" class="trace-item" :class="t.status">
          <span class="trace-status">
            {{ t.status === 'success' ? '✓' : t.status === 'failed' ? '✕' : '◌' }}
          </span>
          <span class="trace-title">{{ t.title }}</span>
          <span v-if="t.elapsedMs != null" class="trace-ms">{{ t.elapsedMs }}ms</span>
          <div v-if="t.output" class="trace-output">{{ t.output }}</div>
          <div v-if="t.error" class="trace-error">{{ t.error }}</div>
        </div>
      </div>
      <div v-if="debugAnswer" class="debug-answer">{{ debugAnswer }}</div>
    </el-drawer>

    <!-- DSL 导入 -->
    <el-dialog v-model="importVisible" title="导入 DSL" width="520px">
      <el-radio-group v-model="importSource">
        <el-radio value="auto">自动识别</el-radio>
        <el-radio value="mse">内置 mse-flow/v1</el-radio>
        <el-radio value="dify">Dify DSL（尽力映射）</el-radio>
      </el-radio-group>
      <div class="hint" style="margin: 8px 0">
        选择 YAML 文件导入；导入不直接保存，确认画布无误后点「保存」。
      </div>
      <input ref="fileInput" type="file" accept=".yaml,.yml,.txt" @change="onImportFile" />
    </el-dialog>

    <!-- 全局参数 -->
    <el-dialog v-model="globalsVisible" title="全局参数" width="680px">
      <div class="hint" style="margin-bottom: 8px">
        画布级参数：运行时以 <b>env.参数名</b> 预置（任意节点可引用）；「变量赋值」节点可在执行期按操作改写其值，
        改写对后续节点生效。集合/对象默认值填 JSON（如 [1,2] 与 {"a":1}）；参数名建议用字母/数字/下划线。
      </div>
      <div v-for="(g, i) in editingGlobals" :key="i" class="row" style="margin-bottom: 6px">
        <el-input v-model="g.name" placeholder="参数名" style="width: 118px" />
        <el-select v-model="g.type" placeholder="类型" style="width: 84px">
          <el-option v-for="t in GLOBAL_TYPES" :key="t.value" :value="t.value" :label="t.label" />
        </el-select>
        <el-select v-if="g.type === 'boolean'" v-model="g.value" style="flex: 1">
          <el-option value="true" label="true" />
          <el-option value="false" label="false" />
        </el-select>
        <el-input v-else v-model="g.value" :placeholder="globalValuePlaceholder(g.type)" />
        <el-input v-model="g.remark" placeholder="备注（可选）" style="width: 110px" />
        <el-button link type="danger" @click="editingGlobals.splice(i, 1)">删</el-button>
      </div>
      <el-button size="small" @click="editingGlobals.push({ name: '', value: '', type: 'string', remark: '' })">添加参数</el-button>
      <template #footer>
        <el-button @click="globalsVisible = false">取消</el-button>
        <el-button type="primary" @click="saveGlobals">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, nextTick, onMounted, onUnmounted, ref, resolveComponent, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { VueFlow, useVueFlow, type Connection } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { ControlButton, Controls } from '@vue-flow/controls'
import { MiniMap } from '@vue-flow/minimap'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import '@vue-flow/minimap/dist/style.css'
import FlowCard from './FlowCard.vue'
import {
  assignOpsForType, CONDITION_OPS, defaultData, GLOBAL_TYPES, globalTypeLabel,
  metaOf, NODE_METAS, nodeOutputVars
} from './nodeMeta'
import { agentApi, kbApi, modelApi, toolApi } from '../../api/ai'
import type {
  AiAgent, AiKnowledgeBase, AiModel, DatasourceOption, DslImportResult, FlowGlobal,
  FlowGraph, FlowNodeTrace, ToolOption
} from '../../api/ai'

/** VueFlow 初始元素（实际内容由 setNodes/setEdges 装载） */
const initialNodes: any[] = []
const initialEdges: any[] = []

// ==================== 轻量子组件：模板字段（带变量插入） ====================
/** 变量分组（系统变量/全局参数/各上游节点），供变量选择下拉分组展示 */
interface VarItem { ref: string; name: string; label: string }
interface VarGroup { key: string; label: string; icon?: string; vars: VarItem[] }

const TemplateField = defineComponent({
  props: {
    modelValue: { type: String, default: '' },
    groups: { type: Array as () => VarGroup[], default: () => [] },
    label: { type: String, default: '' },
    rows: { type: Number, default: 3 },
    inline: { type: Boolean, default: false },
    placeholder: { type: String, default: '' }
  },
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    const ElInput = resolveComponent('ElInput') as any
    const ElFormItem = resolveComponent('ElFormItem') as any
    const ElSelect = resolveComponent('ElSelect') as any
    const ElOption = resolveComponent('ElOption') as any
    const ElOptionGroup = resolveComponent('ElOptionGroup') as any
    const inputRef = ref()
    const hasVars = () => props.groups.some((g) => g.vars.length)
    /** 取 EP Input 暴露的原生输入元素（textarea/ref 兼容不同类型与版本） */
    const nativeEl = () => (inputRef.value?.resizableTextArea?.textarea || inputRef.value?.ref
      || inputRef.value?.textarea || inputRef.value?.input) as HTMLTextAreaElement | HTMLInputElement | undefined
    /** 点下拉选项时输入框已失焦，失焦瞬间记录光标位置供插入使用 */
    let lastCaret: { start: number; end: number } | null = null
    const recordCaret = () => {
      const el = nativeEl()
      lastCaret = el?.selectionStart != null
        ? { start: el.selectionStart, end: el.selectionEnd ?? el.selectionStart }
        : null
    }
    /** 优先在最后光标处插入引用；从未聚焦过则追加到末尾 */
    const insertRef = (refValue: string) => {
      if (!refValue) return
      const token = `{{${refValue}}}`
      const cur = props.modelValue || ''
      const pos = lastCaret && lastCaret.start <= cur.length ? lastCaret : null
      lastCaret = null
      if (pos) {
        emit('update:modelValue', cur.slice(0, pos.start) + token + cur.slice(pos.end))
        nextTick(() => {
          const el = nativeEl()
          if (el) {
            el.focus()
            el.setSelectionRange(pos.start + token.length, pos.start + token.length)
          }
        })
      } else {
        emit('update:modelValue', cur + token)
      }
    }
    const selectNode = () =>
      hasVars()
        ? h(ElSelect, {
          size: 'small',
          filterable: true,
          placeholder: '插入变量',
          style: props.inline ? 'width: 86px; flex: none' : 'width: 100%; margin-top: 2px',
          modelValue: '',
          'onUpdate:modelValue': insertRef
        }, () => props.groups.filter((g) => g.vars.length).map((g) =>
          h(ElOptionGroup, { key: g.key, label: `${g.icon || ''}${g.label}` }, () =>
            g.vars.map((v) => h(ElOption, { key: v.ref, value: v.ref, label: `{{${v.ref}}}` }, () => [
              h('span', { style: 'margin-right: 8px' }, `{{${v.ref}}}`),
              h('span', { style: 'float: right; color: var(--el-text-color-secondary); font-size: 11px' }, v.label)
            ]))
          )))
        : null
    return () =>
      props.inline
        ? h('div', { style: 'display: flex; align-items: center; gap: 4px; flex: 1; min-width: 0' }, [
          h(ElInput, {
            ref: inputRef,
            modelValue: props.modelValue,
            'onUpdate:modelValue': (v: string) => emit('update:modelValue', v),
            onBlur: recordCaret,
            size: 'small',
            placeholder: props.placeholder || '变量/条件左值',
            style: 'flex: 1; min-width: 0'
          }),
          selectNode()
        ])
        : h(ElFormItem, { label: props.label || undefined }, () => [
          h(ElInput, {
            ref: inputRef,
            modelValue: props.modelValue,
            'onUpdate:modelValue': (v: string) => emit('update:modelValue', v),
            onBlur: recordCaret,
            type: 'textarea',
            rows: props.rows,
            placeholder: props.placeholder || undefined
          }),
          selectNode()
        ])
  }
})

// ==================== 轻量子组件：键值编辑 ====================
const KvEditor = defineComponent({
  props: {
    modelValue: { type: Object as () => Record<string, any>, default: () => ({}) },
    groups: { type: Array as () => VarGroup[], default: () => [] },
    keyLabel: { type: String, default: '键' },
    valueIsRef: { type: Boolean, default: false },
    /** 排除的键（由 Schema 编辑器接管，本编辑器只兜底额外键） */
    excludeKeys: { type: Array as () => string[], default: () => [] }
  },
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    const ElInput = resolveComponent('ElInput') as any
    const ElFormItem = resolveComponent('ElFormItem') as any
    const ElButton = resolveComponent('ElButton') as any
    const ElSelect = resolveComponent('ElSelect') as any
    const ElOption = resolveComponent('ElOption') as any
    const ElOptionGroup = resolveComponent('ElOptionGroup') as any
    const set = (next: Record<string, any>) => emit('update:modelValue', next)
    return () => {
      const exclude = props.excludeKeys || []
      const entries = Object.entries(props.modelValue || {}).filter(([key]) => !exclude.includes(key))
      const children = entries.map(([key, value], idx) =>
        h('div', { class: 'row', key: key + idx }, [
          h(ElInput, {
            modelValue: key,
            size: 'small',
            style: 'width: 110px',
            placeholder: props.keyLabel,
            'onUpdate:modelValue': (v: string) => {
              if (!v || v === key) return
              const next: Record<string, any> = {}
              Object.entries(props.modelValue).forEach(([k, val]) => (next[k === key ? v : k] = val))
              set(next)
            }
          }),
          props.valueIsRef
            ? h(ElSelect, {
              modelValue: String(value ?? ''),
              size: 'small',
              filterable: true,
              allowCreate: true,
              defaultFirstOption: true,
              style: 'flex: 1',
              'onUpdate:modelValue': (v: string) => set({ ...props.modelValue, [key]: v })
            }, () => props.groups.filter((g) => g.vars.length).map((g) =>
              h(ElOptionGroup, { key: g.key, label: `${g.icon || ''}${g.label}` }, () =>
                g.vars.map((v) => h(ElOption, { key: v.ref, value: v.ref, label: `{{${v.ref}}}` })))))
            : h(ElInput, {
              modelValue: String(value ?? ''),
              size: 'small',
              style: 'flex: 1',
              'onUpdate:modelValue': (v: string) => set({ ...props.modelValue, [key]: v })
            }),
          h(ElButton, {
            link: true, type: 'danger', size: 'small',
            onClick: () => {
              const next = { ...props.modelValue }
              delete next[key]
              set(next)
            }
          }, () => '删')
        ])
      )
      return h(ElFormItem, { label: props.keyLabel }, () => [
        ...children,
        h(ElButton, { size: 'small', onClick: () => set({ ...props.modelValue, '': '' }) }, () => '添加')
      ])
    }
  }
})

// ==================== 轻量子组件：按工具 Schema 的参数编辑（值支持变量插入） ====================
const SchemaArgEditor = defineComponent({
  props: {
    schemaProps: {
      type: Array as () => { name: string; type?: string; description?: string; required?: boolean; enum?: any[] }[],
      default: () => []
    },
    modelValue: { type: Object as () => Record<string, any>, default: () => ({}) },
    groups: { type: Array as () => VarGroup[], default: () => [] }
  },
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    const ElInput = resolveComponent('ElInput') as any
    const ElFormItem = resolveComponent('ElFormItem') as any
    const ElTooltip = resolveComponent('ElTooltip') as any
    const ElSelect = resolveComponent('ElSelect') as any
    const ElOption = resolveComponent('ElOption') as any
    const ElOptionGroup = resolveComponent('ElOptionGroup') as any
    const set = (key: string, value: any) => emit('update:modelValue', { ...props.modelValue, [key]: value })
    return () =>
      h('div', { style: 'width: 100%' }, props.schemaProps.map((p) => {
        const labelNode = h('span', null, (p.required ? '* ' : '') + p.name)
        return h(ElFormItem, { key: p.name }, () => [
          h('div', { class: 'row', style: 'display: flex; align-items: center; gap: 4px; width: 100%' }, [
            h('div', {
              title: p.description || p.name,
              style: 'width: 110px; font-size: 12px; color: var(--el-text-color-regular); overflow: hidden; text-overflow: ellipsis; white-space: nowrap'
            }, [p.description ? h(ElTooltip, { content: p.description, placement: 'top' }, () => labelNode) : labelNode]),
            Array.isArray(p.enum)
              ? h(ElSelect, {
                modelValue: props.modelValue?.[p.name] ?? '',
                size: 'small',
                filterable: true,
                clearable: true,
                placeholder: p.type ? `类型: ${p.type}` : '取值',
                style: 'flex: 1',
                'onUpdate:modelValue': (v: any) => set(p.name, v)
              }, () => p.enum!.map((opt: any) =>
                h(ElOption, { key: String(opt), label: String(opt), value: opt })))
              : h(ElInput, {
                modelValue: String(props.modelValue?.[p.name] ?? ''),
                size: 'small',
                style: 'flex: 1',
                placeholder: p.type ? `类型: ${p.type}` : '取值',
                'onUpdate:modelValue': (v: string) => set(p.name, v)
              }),
            props.groups.some((g) => g.vars.length)
              ? h(ElSelect, {
                modelValue: '',
                size: 'small',
                filterable: true,
                placeholder: '插入变量',
                style: 'width: 92px',
                'onUpdate:modelValue': (v: string) => {
                  if (v) set(p.name, `${props.modelValue?.[p.name] || ''}{{${v}}}`)
                }
              }, () => props.groups.filter((g) => g.vars.length).map((g) =>
                h(ElOptionGroup, { key: g.key, label: `${g.icon || ''}${g.label}` }, () =>
                  g.vars.map((v) => h(ElOption, { key: v.ref, value: v.ref, label: `{{${v.ref}}}` })))))
              : null
          ])
        ])
      }))
  }
})

// ==================== 页面状态 ====================
interface DslNode { id: string; type: string; title?: string; data: any; position?: { x: number; y: number } }
interface Level { nodeId: string | null; title: string; graph: FlowGraph }

const route = useRoute()
const router = useRouter()
const agentId = route.params.id as string

const agent = ref<Partial<AiAgent>>({})
const levels = ref<Level[]>([{ nodeId: null, title: '主流程', graph: { nodes: [], edges: [] } }])
const selectedId = ref('')
const saving = ref(false)
const publishing = ref(false)
const models = ref<AiModel[]>([])
const kbs = ref<AiKnowledgeBase[]>([])
const tools = ref<ToolOption[]>([])

const debugVisible = ref(false)
const debugRunning = ref(false)
const debugQuery = ref('')
const debugVars = ref<{ name: string; value: string }[]>([])
const debugAnswer = ref('')
const traces = ref<Record<string, FlowNodeTrace>>({})

const importVisible = ref(false)
const importSource = ref('auto')
const fileInput = ref<HTMLInputElement>()

const globalsVisible = ref(false)
const editingGlobals = ref<FlowGlobal[]>([])
/** 数据查询节点可选数据源（经 data_query 工具通道取得；空=数据中台未接入） */
const datasources = ref<DatasourceOption[]>([])

const { setNodes, setEdges, addNodes, addEdges, getNodes, getEdges, removeNodes, fitView } = useVueFlow()

const paletteGroups = computed(() => {
  const groups: Record<string, typeof NODE_METAS> = {}
  NODE_METAS.forEach((m) => {
    ;(groups[m.group] ||= []).push(m)
  })
  return groups
})

const selected = computed(() => {
  const node = getNodes.value.find((n) => n.id === selectedId.value)
  return node ? (node.data.dsl as DslNode) : null
})

const meta = computed(() => metaOf(selected.value?.type || 'llm'))

/** 所选工具节点绑定工具的说明与参数 Schema（下拉选项已随 ToolOption 下发） */
const selectedToolDesc = computed(() =>
  tools.value.find((t) => t.toolCode === selected.value?.data?.toolCode)?.description || '')

interface ToolSchemaProp { name: string; type?: string; description?: string; required?: boolean; enum?: any[] }

const toolSchemaProps = computed<ToolSchemaProp[]>(() => {
  const tool = tools.value.find((t) => t.toolCode === selected.value?.data?.toolCode)
  if (!tool?.parametersJsonSchema) return []
  try {
    const schema = JSON.parse(tool.parametersJsonSchema) || {}
    const props = schema.properties || {}
    const required: string[] = Array.isArray(schema.required) ? schema.required : []
    return Object.entries(props).map(([name, p]: [string, any]) => ({
      name,
      type: p?.type,
      description: p?.description,
      required: required.includes(name),
      enum: Array.isArray(p?.enum) ? p.enum : undefined
    }))
  } catch {
    return []
  }
})

/**
 * 上游变量分组：系统变量 → 全局参数（env.*）→ 当前视图内所选节点的全部祖先节点输出
 * （不只直连——链式场景下任意上游输出都可引用，与引擎按 ctx 全量取值一致）。
 * 分组按节点标题展示（附节点 id 与类型图标），供各变量选择下拉分组呈现。
 */
const upstreamVarGroups = computed<VarGroup[]>(() => {
  const groups: VarGroup[] = [{
    key: 'sys', label: '系统变量', vars: [
      { ref: 'sys.query', name: 'query', label: '用户输入' },
      { ref: 'sys.conversation', name: 'conversation', label: '会话历史' },
      { ref: 'sys.now', name: 'now', label: '当前时间' }
    ]
  }]
  const globals = (levels.value[0].graph.globals || []).filter((g) => g.name)
  if (globals.length) {
    groups.push({
      key: 'env', label: '全局参数',
      vars: globals.map((g) => ({
        ref: `env.${g.name}`, name: g.name,
        label: (g.remark || '全局参数') + (g.type && g.type !== 'string' ? `（${globalTypeLabel(g.type)}）` : '')
      }))
    })
  }
  if (!selected.value) return groups
  const dslById = new Map<string, DslNode>()
  getNodes.value.forEach((n) => dslById.set(n.id, n.data.dsl as DslNode))
  const incoming = new Map<string, string[]>()
  getEdges.value.forEach((e) => {
    const list = incoming.get(e.target) || []
    list.push(e.source)
    incoming.set(e.target, list)
  })
  const seen = new Set<string>()
  const stack = [...(incoming.get(selected.value.id) || [])]
  while (stack.length) {
    const id = stack.pop()!
    if (seen.has(id)) continue
    seen.add(id)
    const source = dslById.get(id)
    if (!source) continue
    const vars = nodeOutputVars(source).map((v) => ({ ref: `${source.id}.${v.name}`, name: v.name, label: v.label }))
    if (vars.length) {
      groups.push({
        key: source.id, icon: metaOf(source.type).icon,
        label: `${source.title || metaOf(source.type).label}（${source.id}）`, vars
      })
    }
    ;(incoming.get(id) || []).forEach((parent) => stack.push(parent))
  }
  return groups
})

/** 非空变量分组（模板里 v-for 直接用，过滤无成员的组） */
const varGroupOptions = computed(() => upstreamVarGroups.value.filter((g) => g.vars.length))

/** 引用包裹为 {{ref}}（模板文本插值内不能直接写字面 {{，经函数输出） */
const wrapRef = (ref: string) => `{{${ref}}}`

/** 迭代输出变量候选：outputSelector 语义取子图内变量（子图 start 的 item/index + 子图各节点输出） */
const iterationOutputOptions = computed(() => {
  const graph = selected.value?.data?.graph as FlowGraph | null | undefined
  if (!graph || !Array.isArray(graph.nodes)) return []
  const opts: { ref: string; label: string }[] = [
    { ref: 'start.item', label: '当前迭代项' },
    { ref: 'start.index', label: '当前迭代序号' }
  ]
  graph.nodes.forEach((n) => {
    nodeOutputVars(n).forEach((v) =>
      opts.push({ ref: `${n.id}.${v.name}`, label: `${n.title || metaOf(n.type).label}·${v.label}` }))
  })
  return opts
})

/** 已定义的全局参数（赋值节点目标下拉用，附声明类型） */
const globalParams = computed(() =>
  (levels.value[0].graph.globals || []).filter((g) => g.name).map((g) => ({ name: g.name, type: g.type || 'string' })))

/** 目标参数声明类型（未登记的参数名返回 undefined，操作下拉放开全部） */
const globalTypeOf = (name: string) => globalParams.value.find((g) => g.name === name)?.type

/** 全局参数默认值占位（按类型提示填写格式） */
function globalValuePlaceholder(type?: string) {
  if (type === 'number') return '如 42 或 3.14'
  if (type === 'array') return 'JSON 数组，如 [1,2] 或 ["a"]'
  if (type === 'object') return 'JSON 对象，如 {"a":1}'
  return '默认值'
}

/** 赋值取值占位（按操作提示填写格式） */
function assignValuePlaceholder(a: { op?: string }) {
  if (a.op === 'add' || a.op === 'subtract') return '数值或数值变量，如 1'
  if (a.op === 'append') return '追加内容；集合参数时为元素或数组'
  if (a.op === 'merge') return '数组或对象，可含变量引用'
  return '取值（可含变量）'
}

/** 数据源下拉选项：中台库固定在首位 + 数据中台返回的本租户数据源；已配置但不在清单的编号保留显示防丢值 */
const datasourceOptions = computed<DatasourceOption[]>(() => {
  const list: DatasourceOption[] = [{ id: 0, name: '中台库（ODS 贴源表，仅 ods_ 前缀）', type: 'internal' }]
  datasources.value.forEach((ds) => {
    if (ds.id !== 0) list.push(ds)
  })
  const current = selected.value?.data?.datasourceId
  if (current != null && !list.some((ds) => ds.id === current)) {
    list.push({ id: current, name: `编号 ${current}（不在可用清单，可能已删除或无权限）`, type: 'unknown' })
  }
  return list
})

const datasourceHint = computed(() =>
  datasources.value.length
      ? '仅列出本租户可用的数据源；运行期校验归属，不能跨租户取数。'
      : '未发现数据中台通道（未部署 mse-data 或未登记 data_query 工具），仅可查中台库（ODS）。')

const kbSelection = computed({
  get: () => (selected.value?.data?.kbIds || '').split(',').map((s: string) => s.trim()).filter(Boolean),
  set: (ids: string[]) => {
    if (selected.value) selected.value.data.kbIds = ids.join(',')
  }
})

const traceList = computed(() => Object.values(traces.value))
const debugStartNode = computed(() => levels.value[0].graph.nodes.find((n) => n.type === 'start'))

// ==================== 画布与 DSL 互转 ====================
/** 旧图归一化：全局参数缺 type 补 string、赋值项缺 op 补 set（与旧数据语义一致） */
function normalizeGraph(graph: FlowGraph) {
  ;(graph.globals || []).forEach((g) => {
    if (!g.type) g.type = 'string'
  })
  graph.nodes.forEach((n) => {
    if (n.type === 'assign' && Array.isArray(n.data?.assignments)) {
      n.data.assignments.forEach((a: any) => {
        if (!a.op) a.op = 'set'
      })
    }
  })
}

/** 将图数据装载进画布（不清历史、不适配视图；撤回/重做复用） */
function loadGraph(graph: FlowGraph) {
  setNodes(
    graph.nodes.map((dsl) => ({
      id: dsl.id,
      type: 'flow',
      position: { x: dsl.position?.x ?? 100, y: dsl.position?.y ?? 100 },
      data: { dsl }
    }))
  )
  setEdges(
    graph.edges.map((e, i) => ({
      id: `e-${e.source}-${e.sourceHandle || 'o'}-${e.target}-${i}`,
      source: e.source,
      target: e.target,
      sourceHandle: e.sourceHandle ?? undefined,
      animated: true
    }))
  )
  selectedId.value = ''
}

/** 切换层级视图：重置历史（历史按当前层级记），并适配视图 */
function loadLevel(level: Level) {
  loadGraph(level.graph)
  history.length = 0
  historyIndex.value = -1
  setTimeout(() => fitView?.({ padding: 0.15 }), 60)
}

function writeBack(): Level {
  const level = levels.value[levels.value.length - 1]
  const byId = new Map<string, DslNode>()
  level.graph.nodes.forEach((n) => byId.set(n.id, n))
  level.graph.nodes = getNodes.value.map((n) => {
    const dsl = n.data.dsl as DslNode
    dsl.position = { x: Math.round(n.position.x), y: Math.round(n.position.y) }
    return dsl
  })
  level.graph.edges = getEdges.value.map((e) => ({
    source: e.source,
    target: e.target,
    sourceHandle: e.sourceHandle ?? null
  }))
  return level
}

// ==================== 撤回 / 重做（快照式历史：增删节点/连线/移动/属性修改全覆盖） ====================
/** 图快照栈（JSON 串），historyIndex 指向当前态；切换层级时清空重记 */
const history: string[] = []
const historyIndex = ref(-1)
/** 应用快照期间暂停记历史，避免装载动作被当作新修改 */
const restoring = ref(false)
const helpVisible = ref(false)
let pushTimer: ReturnType<typeof setTimeout> | undefined

const canUndo = computed(() => historyIndex.value > 0)
const canRedo = computed(() => historyIndex.value < history.length - 1)

/** 当前画布序列化为图快照（经 writeBack 回写位置等，仅读 VueFlow 状态，不触发本轮 watch） */
function currentGraphJson(): string {
  const { graph } = writeBack()
  return JSON.stringify({ nodes: graph.nodes, edges: graph.edges })
}

function pushHistory() {
  pushTimer = undefined
  if (restoring.value) return
  const json = currentGraphJson()
  if (history[historyIndex.value] === json) return
  history.splice(historyIndex.value + 1)
  history.push(json)
  if (history.length > 100) history.shift()
  historyIndex.value = history.length - 1
}

/** 画布任何变化（拖动结束/增删/连线/属性编辑）防抖 400ms 记一次快照；选中态不在快照内，不会误记 */
watch([getNodes, getEdges], () => {
  if (restoring.value) return
  if (pushTimer) clearTimeout(pushTimer)
  pushTimer = setTimeout(pushHistory, 400)
}, { deep: true })

/** 撤回/重做前把挂起的防抖快照落盘，保证刚发生的修改可被撤回 */
function flushPendingPush() {
  if (pushTimer) {
    clearTimeout(pushTimer)
    pushHistory()
  }
}

function applySnapshot() {
  const graph = JSON.parse(history[historyIndex.value]) as FlowGraph
  restoring.value = true
  loadGraph(graph)
  // 立即回写层级图，避免撤回后立刻保存拿到旧数据
  writeBack()
  setTimeout(() => (restoring.value = false), 60)
}

function undo() {
  flushPendingPush()
  if (historyIndex.value <= 0) return
  historyIndex.value--
  applySnapshot()
}

function redo() {
  flushPendingPush()
  if (historyIndex.value >= history.length - 1) return
  historyIndex.value++
  applySnapshot()
}

/** Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y（输入框内不劫持，保留文本编辑原生的撤回） */
function isEditableTarget(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null
  if (!el || !(el instanceof HTMLElement)) return false
  return ['INPUT', 'TEXTAREA', 'SELECT'].includes(el.tagName) || el.isContentEditable
}

function onKeydown(e: KeyboardEvent) {
  if (!(e.ctrlKey || e.metaKey)) return
  if (isEditableTarget(e.target)) return
  const key = e.key.toLowerCase()
  if (key === 'z') {
    e.preventDefault()
    e.shiftKey ? redo() : undo()
  } else if (key === 'y') {
    e.preventDefault()
    redo()
  }
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onUnmounted(() => window.removeEventListener('keydown', onKeydown))

function addNode(type: string) {
  const idBase = type
  let seq = 1
  const exist = new Set(getNodes.value.map((n) => n.id))
  while (exist.has(`${idBase}${seq}`)) seq++
  const id = `${idBase}${seq}`
  const dsl: DslNode = {
    id,
    type,
    title: `${metaOf(type).label}${seq}`,
    data: defaultData(type),
    position: { x: 160 + Math.round(Math.random() * 240), y: 80 + Math.round(Math.random() * 200) }
  }
  addNodes([{ id, type: 'flow', position: { ...dsl.position }, data: { dsl } }])
  selectedId.value = id
}

function onNodeClick({ node }: any) {
  selectedId.value = node.id
}

function removeSelected() {
  if (!selectedId.value) return
  removeNodes([selectedId.value])
  selectedId.value = ''
}

function onConnect(connection: Connection) {
  addEdges([{ ...connection, id: `e-${Date.now()}`, animated: true }])
}

// ==================== 子流程（迭代） ====================
function openSubgraph() {
  const parent = writeBack()
  const node = parent.graph.nodes.find((n) => n.id === selectedId.value)
  if (!node) return
  if (!node.data.graph) {
    const answer: DslNode = {
      id: 'answer1', type: 'answer', title: '单项回复', position: { x: 420, y: 120 },
      data: { content: '{{start.item}}' }
    }
    node.data.graph = {
      nodes: [
        { id: 'start', type: 'start', title: '迭代项', position: { x: 100, y: 120 }, data: { variables: [] } },
        answer
      ],
      edges: [{ source: 'start', target: 'answer1' }]
    }
  }
  levels.value.push({ nodeId: node.id, title: `子流程 · ${node.title || node.id}`, graph: node.data.graph })
  loadLevel(levels.value[levels.value.length - 1])
}

function gotoLevel(index: number) {
  if (index === levels.value.length - 1) return
  closeTo(index)
}

function gotoRoot() {
  closeTo(0)
}

function closeTo(index: number) {
  while (levels.value.length - 1 > index) {
    const child = writeBack()
    levels.value.pop()
    const parent = levels.value[levels.value.length - 1]
    if (child.nodeId) {
      const owner = parent.graph.nodes.find((n) => n.id === child.nodeId)
      if (owner) owner.data.graph = child.graph
    }
  }
  loadLevel(levels.value[levels.value.length - 1])
}

// ==================== 加载 / 保存 ====================
onMounted(async () => {
  const body: any = await agentApi.get(agentId)
  agent.value = body
  if (body.draftGraphJson || body.graphJson) {
    try {
      // 编辑器所见 = 草稿优先（调试同口径）；无草稿的存量智能体回退运行版
      levels.value[0].graph = JSON.parse(body.draftGraphJson || body.graphJson)
    } catch {
      ElMessage.warning('已存画布解析失败，已加载空白画布')
    }
  }
  normalizeGraph(levels.value[0].graph)
  loadLevel(levels.value[0])
  // 仅对话模型：LLM/分类/提取节点均为对话能力（平台登录返回全部启用模型）
  models.value = ((await modelApi.available('chat').catch(() => [])) as any) || []
  // 知识库为租户侧数据
  const kbBody: any = await kbApi.page({ pageNum: 1, pageSize: 100 }).catch(() => null)
  kbs.value = kbBody?.records ?? []
  // 数据查询节点数据源下拉：经 data_query 工具通道取本租户可用数据源（未接入数据中台时空）
  datasources.value =
    ((await agentApi.datasourceOptions().catch(() => [])) as any) || []
  // 工具下拉：本地 Bean（含内置）+ 已启用登记工具，服务端已合并判重（租户/平台均可用）
  tools.value = ((await toolApi.options().catch(() => [])) as any) || []
})

function backToAgent() {
  router.push('/agent')
}

async function save() {
  closeTo(0)
  const graph = levels.value[0].graph
  if (!graph.nodes.length) {
    ElMessage.warning('画布为空')
    return
  }
  const body = {
    id: agent.value.id,
    agentCode: agent.value.agentCode,
    name: agent.value.name,
    avatar: agent.value.avatar,
    description: agent.value.description,
    systemPrompt: agent.value.systemPrompt,
    modelId: agent.value.modelId,
    temperature: agent.value.temperature,
    welcomeMessage: agent.value.welcomeMessage,
    toolCodes: agent.value.toolCodes,
    kbIds: agent.value.kbIds,
    orchestrationType: 'flow',
    graphJson: JSON.stringify(graph),
    status: agent.value.status,
    remark: agent.value.remark
  }
  saving.value = true
  try {
    await agentApi.save(body)
    ElMessage.success('草稿已保存（试运行按草稿执行；点「发布」更新线上版本）')
  } finally {
    saving.value = false
  }
}

/** 发布：草稿校验后拷贝为运行版（对话/RPC 即刻生效） */
async function publish() {
  if (!agent.value.id) {
    ElMessage.warning('请先保存智能体')
    return
  }
  publishing.value = true
  try {
    await agentApi.publish(agent.value.id)
    ElMessage.success('已发布，线上版本已更新')
  } finally {
    publishing.value = false
  }
}

// ==================== 全局参数 ====================
function openGlobals() {
  editingGlobals.value = (levels.value[0].graph.globals || []).map((g) => ({ type: 'string', ...g }))
  globalsVisible.value = true
}

function saveGlobals() {
  const seen = new Set<string>()
  for (const g of editingGlobals.value) {
    const name = (g.name || '').trim()
    if (!name) {
      ElMessage.warning('全局参数名不能为空')
      return
    }
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name)) {
      ElMessage.warning(`参数名不合法: ${name}（用字母开头，字母/数字/下划线）`)
      return
    }
    if (seen.has(name)) {
      ElMessage.warning(`参数名重复: ${name}`)
      return
    }
    seen.add(name)
    g.name = name
    // 按类型校验默认值（与后端 VarTypes.coerce 一致；集合/对象须为合法 JSON）
    const type = g.type || 'string'
    const value = String(g.value ?? '').trim()
    if (type === 'number' && value !== '' && Number.isNaN(Number(value))) {
      ElMessage.warning(`全局参数 ${name} 的默认值不是合法数值: ${g.value}`)
      return
    }
    if ((type === 'array' || type === 'object') && value !== '') {
      let parsed: any
      try {
        parsed = JSON.parse(value)
      } catch {
        ElMessage.warning(`全局参数 ${name} 的默认值不是合法 JSON: ${g.value}`)
        return
      }
      const shapeOk = type === 'array' ? Array.isArray(parsed)
        : typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed)
      if (!shapeOk) {
        ElMessage.warning(`全局参数 ${name} 的默认值需要是${type === 'array' ? ' JSON 数组' : ' JSON 对象'}: ${g.value}`)
        return
      }
    }
    g.type = type
  }
  levels.value[0].graph.globals = editingGlobals.value
  globalsVisible.value = false
}

// ==================== 试运行 ====================
function openDebug() {
  openDebugPrepare()
  debugVisible.value = true
}

function openDebugPrepare() {
  debugVars.value = (debugStartNode.value?.data?.variables || []).map((v: any) => ({ name: v.name, value: '' }))
}

async function runDebug() {
  if (!debugQuery.value.trim()) {
    ElMessage.warning('请输入调试输入')
    return
  }
  debugRunning.value = true
  debugAnswer.value = ''
  traces.value = {}
  const inputs: Record<string, string> = {}
  debugVars.value.forEach((v) => (inputs[v.name] = v.value))
  try {
    const runStream = agentApi.debugRun
    await runStream(
      agentId,
      { query: debugQuery.value, inputs },
      {
        onNode: (t) => {
          const cardId = t.nodeId.includes('/') ? t.nodeId.split('/').pop()! : t.nodeId
          traces.value[t.nodeId] = t
          const node = getNodes.value.find((n) => n.id === cardId)
          if (node) node.data.lastStatus = t.status
        },
        onMessage: (delta) => (debugAnswer.value += delta),
        onError: (msg) => ElMessage.error(msg)
      }
    )
  } finally {
    debugRunning.value = false
  }
}

// ==================== DSL 导入导出 ====================
async function onImportFile(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  const content = await file.text()
  const source = importSource.value === 'auto'
    ? (/^workflow:|^\s*workflow:/m.test(content) ? 'dify' : 'mse')
    : importSource.value
  try {
    const importDsl = agentApi.dslImport
    const result = (await importDsl(agentId, content, source)) as any as DslImportResult
    levels.value[0].graph = {
      globals: result.graph.globals || [],
      nodes: result.graph.nodes || [],
      edges: result.graph.edges || []
    }
    normalizeGraph(levels.value[0].graph)
    levels.value = levels.value.slice(0, 1)
    loadLevel(levels.value[0])
    importVisible.value = false
    if (result.warnings?.length) {
      await ElMessageBox.alert(result.warnings.map((w, i) => `${i + 1}. ${w}`).join('\n'), '导入完成，待人工确认项', {
        customStyle: { whiteSpace: 'pre-line' } as any
      })
    } else {
      ElMessage.success('导入成功')
    }
  } finally {
    if (fileInput.value) fileInput.value.value = ''
  }
}

async function exportDsl() {
  closeTo(0)
  const exportDslApi = agentApi.dslExport
  const yaml = (await exportDslApi(agentId)) as any as string
  const blob = new Blob([yaml || ''], { type: 'application/x-yaml' })
  const anchor = document.createElement('a')
  anchor.href = URL.createObjectURL(blob)
  anchor.download = `${agent.value.agentCode || 'flow'}.yaml`
  anchor.click()
  URL.revokeObjectURL(anchor.href)
}

// ==================== 条件用例编辑辅助 ====================
function addCase() {
  selected.value?.data.cases.push({ logic: 'and', conditions: [{ left: '', op: 'not_empty', right: '' }] })
}

function removeCase(index: number) {
  selected.value?.data.cases.splice(index, 1)
}

function addStartVar() {
  selected.value?.data.variables.push({ name: '', required: false })
}
</script>

<style scoped>
.flow-editor {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.tool-option {
  display: flex;
  flex-direction: column;
  line-height: 1.4;
}
.tool-option-desc {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: normal;
  word-break: break-all;
}
.flow-hint {
  width: 100%;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
  margin-bottom: 8px;
}
.topbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 12px;
  border-bottom: 1px solid var(--el-border-color-light);
}
.crumb {
  flex: 1;
}
.ops {
  display: flex;
  gap: 8px;
}
.canvas-wrap {
  flex: 1;
  display: flex;
  min-height: 0;
}
.palette {
  width: 170px;
  border-right: 1px solid var(--el-border-color-light);
  overflow: auto;
  padding: 8px;
}
.group-name {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin: 8px 0 4px;
}
.palette-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 6px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
  color: var(--el-text-color-primary);
}
.palette-item:hover {
  background: var(--el-fill-color-light);
}
.palette-item .icon {
  display: inline-flex;
  width: 20px;
  height: 20px;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  font-size: 12px;
}
.canvas {
  flex: 1;
  position: relative;
}
/* 画布左下控制栏：主题化按钮底色与图标色（vue-flow 默认硬编码白底，暗色模式下图标不可见） */
.canvas :deep(.vue-flow__controls-button) {
  background: var(--el-bg-color-overlay);
  border-bottom-color: var(--el-border-color-lighter);
  color: var(--el-text-color-primary);
}
.canvas :deep(.vue-flow__controls-button:hover) {
  background: var(--el-fill-color-light);
}
.canvas :deep(.vue-flow__controls-button svg) {
  fill: currentColor;
}
.canvas :deep(.vue-flow__controls-button:disabled) {
  opacity: 0.4;
  cursor: not-allowed;
}
.ctrl-icon {
  font-size: 15px;
  line-height: 1;
  font-weight: 700;
}
.ctrl-svg {
  width: 12px;
  height: 12px;
}
.canvas-help {
  position: absolute;
  left: 48px;
  bottom: 8px;
  width: 340px;
  max-height: 72%;
  overflow: auto;
  z-index: 6;
  background: var(--el-bg-color-overlay);
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  box-shadow: var(--el-box-shadow-light);
  padding: 10px 12px;
  font-size: 12px;
  color: var(--el-text-color-regular);
}
.help-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
  color: var(--el-text-color-primary);
  margin-bottom: 6px;
}
.canvas-help ul {
  margin: 0;
  padding-left: 16px;
}
.canvas-help li {
  margin-bottom: 4px;
  line-height: 1.5;
}
.prop-panel {
  width: 340px;
  border-left: 1px solid var(--el-border-color-light);
  overflow: auto;
  padding: 10px;
}
.prop-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
  margin-bottom: 8px;
}
.row {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
}
.row.cond {
  margin-bottom: 4px;
}
/* 条件条目：左值一行、运算符+比较值一行，多条件纵向堆叠不挤压 */
.cond-item {
  border: 1px dashed var(--el-border-color-lighter);
  border-radius: 4px;
  padding: 4px;
  margin: 4px 0;
}
.cond-line {
  display: flex;
  align-items: center;
  gap: 4px;
  width: 100%;
}
.cond-line + .cond-line {
  margin-top: 4px;
}
/* 赋值行：全局参数下拉 + 操作下拉 / 取值输入 */
.assign-line {
  display: flex;
  align-items: center;
  gap: 4px;
  width: 100%;
  margin-bottom: 4px;
}
.case-block {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 6px;
  margin-bottom: 6px;
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.mono :deep textarea {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
}
.trace-list {
  margin-top: 12px;
}
.trace-item {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 6px 8px;
  margin-bottom: 6px;
  font-size: 12px;
}
.trace-item.failed {
  border-color: var(--el-color-danger);
}
.trace-status {
  margin-right: 6px;
}
.trace-item.success .trace-status {
  color: var(--el-color-success);
}
.trace-item.failed .trace-status {
  color: var(--el-color-danger);
}
.trace-title {
  font-weight: 600;
}
.trace-ms {
  margin-left: 6px;
  color: var(--el-text-color-secondary);
}
.trace-output {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 90px;
  overflow: auto;
}
.trace-error {
  margin-top: 4px;
  color: var(--el-color-danger);
  white-space: pre-wrap;
}
.debug-answer {
  margin-top: 10px;
  padding: 8px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  white-space: pre-wrap;
  font-size: 13px;
}
</style>
