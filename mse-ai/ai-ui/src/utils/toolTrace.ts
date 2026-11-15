import type { ToolCallStep, ToolEvent } from '../api/ai'

/**
 * 工具调用轨迹聚合：把 SSE tool 事件按 seq 合并为逐步轨迹
 * （对话工作台内联面板与智能体管理调试台共用）。
 *
 * start 开新步（running，记录所属轮次）、result 回填成败/耗时/失败原因/返回内容、
 * limit 补录超限拦截步骤。seq 由后端逐事件递增，缺失时回退「最近一个 running 步骤」配对。
 * phase=turn/turn-end（模型轮次边界）不进 steps——由调试台时间线单独消费。
 */
export function applyToolEvent(steps: ToolCallStep[], e: ToolEvent) {
  if (e.phase === 'start') {
    steps.push({
      seq: e.seq ?? steps.length + 1,
      tool: e.tool || 'unknown',
      arguments: e.arguments == null ? null : JSON.stringify(e.arguments),
      status: 'running',
      turn: e.turn ?? null
    })
  } else if (e.phase === 'result') {
    const step = findStep(steps, e.seq)
    if (step) {
      step.status = e.success ? 'success' : 'fail'
      step.elapsedMs = e.elapsedMs
      step.error = e.error || null
      step.result = e.result || null
      step.dataset = e.dataset || null
    }
  } else if (e.phase === 'limit') {
    steps.push({
      seq: e.seq ?? steps.length + 1,
      tool: e.tool || 'unknown',
      status: 'limit',
      error: e.message || e.error || '单轮调用上限已用尽，系统拦截了后续调用',
      turn: e.turn ?? null
    })
  }
}

function findStep(steps: ToolCallStep[], seq?: number): ToolCallStep | undefined {
  if (seq != null) {
    const hit = steps.find((s) => s.seq === seq)
    if (hit) return hit
  }
  for (let i = steps.length - 1; i >= 0; i--) {
    if (steps[i].status === 'running') return steps[i]
  }
  return undefined
}
