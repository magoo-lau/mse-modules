<template>
  <div class="json-code-editor">
    <div class="editor-bar">
      <el-button v-if="!readonly" link type="primary" size="small" @click="format(view)">格式化</el-button>
      <el-button link type="primary" size="small" @click="openZoom">放大查看</el-button>
    </div>
    <div ref="hostRef" class="cm-host" :style="{ height }"></div>

    <el-dialog v-model="zoomVisible" :title="`放大查看 · ${title}`" width="880px" top="6vh" append-to-body>
      <div ref="zoomHostRef" class="cm-host" style="height: 62vh"></div>
      <template #footer>
        <el-button v-if="!readonly" @click="format(zoomView)">格式化</el-button>
        <el-button type="primary" @click="zoomVisible = false">关 闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Compartment, EditorState } from '@codemirror/state'
import { EditorView } from '@codemirror/view'
import { basicSetup } from 'codemirror'
import { json } from '@codemirror/lang-json'
import { oneDark } from '@codemirror/theme-one-dark'

/** JSON 查看/编辑器（CodeMirror 6）：格式化展示 + 语法高亮 + 节点折叠 + 放大查看（弹窗双视图同步） */
const props = withDefaults(defineProps<{
  /** JSON 文本（v-model） */
  modelValue: string
  /** 只读态：禁编辑（登记工具的 Schema 由提供方代码维护），折叠/放大查看保留 */
  readonly?: boolean
  /** 内嵌编辑器高度 */
  height?: string
  /** 放大弹窗标题 */
  title?: string
}>(), { readonly: false, height: '200px', title: 'JSON' })

const emit = defineEmits<{ (e: 'update:modelValue', v: string): void }>()

const hostRef = ref<HTMLElement>()
const zoomHostRef = ref<HTMLElement>()
const zoomVisible = ref(false)

let view: EditorView | null = null
let zoomView: EditorView | null = null
let themeObserver: MutationObserver | null = null
const viewThemeComp = new Compartment()
const zoomThemeComp = new Compartment()

/** 合法 JSON 转两空格缩进展示（非法原样保留，交由保存校验提示） */
function pretty(text: string): string {
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    return text
  }
}

/** 明暗主题跟随基座（html.dark）：暗色 oneDark，亮色 Element 变量定制 */
const lightTheme = EditorView.theme({
  '&': { backgroundColor: 'var(--el-bg-color)', color: 'var(--el-text-color-primary)' },
  '.cm-gutters': { backgroundColor: 'var(--el-fill-color-light)', color: 'var(--el-text-color-secondary)', border: 'none' },
  '.cm-activeLine': { backgroundColor: 'var(--el-fill-color)' },
  '.cm-activeLineGutter': { backgroundColor: 'var(--el-fill-color)' },
})

function themeExtension() {
  return document.documentElement.classList.contains('dark') ? oneDark : lightTheme
}

function refreshThemes() {
  const ext = themeExtension()
  view?.dispatch({ effects: viewThemeComp.reconfigure(ext) })
  zoomView?.dispatch({ effects: zoomThemeComp.reconfigure(ext) })
}

function createView(parent: HTMLElement, doc: string, themeComp: Compartment, onDocChange: (text: string) => void) {
  return new EditorView({
    state: EditorState.create({
      doc,
      extensions: [
        basicSetup,
        json(),
        themeComp.of(themeExtension()),
        EditorView.lineWrapping,
        ...(props.readonly ? [EditorView.editable.of(false), EditorState.readOnly.of(true)] : []),
        EditorView.updateListener.of((u) => {
          if (u.docChanged) onDocChange(u.state.doc.toString())
        }),
      ],
    }),
    parent,
  })
}

/** 双视图同步 + v-model 回写（相等即跳过，防递归） */
function setDoc(target: EditorView | null, text: string) {
  if (!target || target.state.doc.toString() === text) return
  target.dispatch({ changes: { from: 0, to: target.state.doc.length, insert: text } })
}

onMounted(() => {
  const initial = pretty(props.modelValue || '')
  view = createView(hostRef.value!, initial, viewThemeComp, (text) => {
    emit('update:modelValue', text)
    setDoc(zoomView, text)
  })
  if (initial !== (props.modelValue || '')) {
    emit('update:modelValue', initial)
  }
  themeObserver = new MutationObserver(refreshThemes)
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
})

onBeforeUnmount(() => {
  themeObserver?.disconnect()
  view?.destroy()
  zoomView?.destroy()
})

watch(() => props.modelValue, (v) => {
  const text = v ?? ''
  // 组件自身写回的 prop echo（两视图已同步），避免打断编辑中的光标
  if (view && text === view.state.doc.toString()) {
    setDoc(zoomView, text)
    return
  }
  // 外部写入（切换编辑行/抽屉应用）：格式化后展示，并回写归一化值
  const prettyText = pretty(text)
  setDoc(view, prettyText)
  setDoc(zoomView, prettyText)
  if (prettyText !== text) {
    emit('update:modelValue', prettyText)
  }
})

async function openZoom() {
  zoomVisible.value = true
  await nextTick()
  if (!zoomView && zoomHostRef.value) {
    zoomView = createView(zoomHostRef.value, view?.state.doc.toString() ?? props.modelValue ?? '', zoomThemeComp, (text) => {
      emit('update:modelValue', text)
      setDoc(view, text)
    })
  } else {
    setDoc(zoomView, view?.state.doc.toString() ?? '')
    zoomView?.requestMeasure()
  }
}

function format(target: EditorView | null) {
  if (!target) return
  let formatted: string
  try {
    formatted = JSON.stringify(JSON.parse(target.state.doc.toString() || '{}'), null, 2)
  } catch {
    ElMessage.warning('JSON 格式有误，无法格式化')
    return
  }
  setDoc(target, formatted)
}
</script>

<style scoped>
.json-code-editor {
  width: 100%;
}
.editor-bar {
  display: flex;
  justify-content: flex-end;
  gap: 4px;
  margin-bottom: 2px;
}
.cm-host {
  width: 100%;
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  overflow: hidden;
  text-align: left;
}
.cm-host :deep(.cm-editor) {
  height: 100%;
  font-size: 12px;
}
.cm-host :deep(.cm-editor.cm-focused) {
  outline: none;
}
.cm-host :deep(.cm-scroller) {
  overflow: auto;
  font-family: Consolas, Menlo, 'Courier New', monospace;
  line-height: 1.55;
}
</style>
