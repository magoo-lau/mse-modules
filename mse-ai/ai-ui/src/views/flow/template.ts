/**
 * 预置编排模板：数据分析助手。
 * 链路：开始 → 参数提取（时间范围/维度/指标）→ 数据查询（受控只读 SQL）→
 *       条件分支（有数据?）→ LLM 分析（结合系统功能数据）→ 回复；空数据 → 回复提示。
 * 数据查询默认走 data_query 工具通道（mse-data 数据中台，SQL 防护 + 租户谓词 + 行数上限）。
 */

export interface FlowTemplate {
  key: string
  name: string
  description: string
  graph: { nodes: any[]; edges: any[] }
}

export const DATA_ANALYSIS_TEMPLATE: FlowTemplate = {
  key: 'data-analysis',
  name: '数据分析助手',
  description: '参数提取 → 数据中台取数 → LLM 趋势/异常分析，空数据分支兜底',
  graph: {
    nodes: [
      {
        id: 'start', type: 'start', title: '开始',
        position: { x: 60, y: 220 },
        data: { variables: [] }
      },
      {
        id: 'extract', type: 'extractor', title: '提取分析参数',
        position: { x: 340, y: 200 },
        data: {
          query: '{{sys.query}}',
          modelId: null,
          fields: [
            { name: 'metric', type: 'string', description: '用户想分析的指标，如销量/数量/金额' },
            { name: 'start_date', type: 'string', description: '起始日期（yyyy-MM-dd），未指明留空' },
            { name: 'end_date', type: 'string', description: '截止日期（yyyy-MM-dd），未指明留空' }
          ]
        }
      },
      {
        id: 'query', type: 'dataset', title: '数据中台取数',
        position: { x: 640, y: 200 },
        data: {
          datasourceId: 0,
          rowLimit: 200,
          toolCode: '',
          sql: "SELECT * FROM ods_order_daily WHERE stat_date >= '{{extract.start_date}}' AND stat_date <= '{{extract.end_date}}'"
        }
      },
      {
        id: 'hasData', type: 'condition', title: '有数据？',
        position: { x: 940, y: 210 },
        data: {
          cases: [
            { logic: 'and', conditions: [{ left: '{{query.rowCount}}', op: 'gt', right: '0' }] }
          ]
        }
      },
      {
        id: 'analyze', type: 'llm', title: 'LLM 数据分析',
        position: { x: 1240, y: 120 },
        data: {
          modelId: null,
          memoryEnabled: false,
          passthrough: false,
          systemPrompt:
            '你是数据分析助手，基于系统数据做趋势、异常与归因分析。回答用中文，先给结论再列依据，数字保留原文精度。',
          prompt:
            '用户问题：{{sys.query}}\n\n查询数据（{{query.rowCount}} 行，列：{{query.columns}}）：\n{{query.markdown}}\n\n请给出：1) 直接回答；2) 关键数据依据；3) 趋势或异常点；4) 建议后续动作。'
        }
      },
      {
        id: 'answerData', type: 'answer', title: '回复分析',
        position: { x: 1540, y: 120 },
        data: { content: '{{analyze.text}}' }
      },
      {
        id: 'answerEmpty', type: 'answer', title: '无数据提示',
        position: { x: 1240, y: 340 },
        data: {
          content:
            '未查询到相关数据。请确认：1) 数据中台是否已接入并完成同步（ods_ 贴源表）；2) 时间范围是否正确。'
        }
      }
    ],
    edges: [
      { source: 'start', target: 'extract' },
      { source: 'extract', target: 'query' },
      { source: 'query', target: 'hasData' },
      { source: 'hasData', target: 'analyze', sourceHandle: 'case0' },
      { source: 'hasData', target: 'answerEmpty', sourceHandle: 'default' },
      { source: 'analyze', target: 'answerData' }
    ]
  }
}

export const FLOW_TEMPLATES: FlowTemplate[] = [DATA_ANALYSIS_TEMPLATE]
