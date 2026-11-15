<template>
  <div class="ep">
    <div class="ep-tabs">
      <button v-for="(g, gi) in groups" :key="g.name" class="ep-tab" :class="{ active: gi === cur }" @click="cur = gi">{{ g.name }}</button>
    </div>
    <div class="ep-grid">
      <button v-for="e in groups[cur].items" :key="e" class="ep-item" @click="$emit('pick', e)">{{ e }}</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'

defineEmits<{ pick: [emoji: string] }>()

/** unicode emoji 分组（不使用 faceElem 115，直接进文本） */
const groups = [
  { name: '😊', items: '😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 🥲 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🥵 🥶 😵 🤯 🤠 🥳 😎 🤓 🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿 💀 ☠️ 💩 🤡 👹 👺 👻 👽 🤖'.split(' ') },
  { name: '👋', items: '👋 🤚 🖐 ✋ 🖖 👌 🤌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 🖕 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🤝 🙏 💪 🦾 ✍️ 💅 👏 👂 👃 🧠 🫀 👀 👁 👅 👄 💋 🩸'.split(' ') },
  { name: '❤️', items: '❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ☮️ ✝️ ☪️ 🕉 ☸️ ✡️ 🔯 🕎 ☯️ ☦️ ⛎ ♈ ♉ ♊ ♋ ♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓ 🆔 ⚛️ 🉑 ☢️ ☣️'.split(' ') },
  { name: '🎉', items: '🎉 🎊 🎈 🎁 🎂 🍰 🧨 🎆 🎇 ✨ ⭐ 🌟 💫 ⚡ ☄️ 💥 🔥 🌈 ☀️ 🌤 ⛅ 🌧 ⛈ 🌩 ❄️ ☃️ ⛄ 🌊 💧 ☔ 🍀 🌹 🌷 🌸 💐 🌻 🌞 🌝 🌛 🌜 🌚 🌕'.split(' ') },
  { name: '🍕', items: '🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥬 🥒 🌶 🌽 🥕 🧄 🧅 🥔 🍠 🥐 🥯 🍞 🥖 🧇 🧀 🍗 🍖 🍔 🍟 🍕 🌭 🥪 🌮 🌯 🥗 🍜 🍲 🍣 🍱 🍚 🍛 🍤 🍙 🍘 🍥 🥟 🍦 🍧 🍹 🧋 ☕ 🍵 🍺 🍻 🥂 🍷 🥃'.split(' ') },
  { name: '⚽', items: '⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🥏 🎱 🪀 🏓 🏸 🏒 🏑 🥍 🏏 🥊 🥋 ⛳ 🛹 🛼 🎿 ⛷ 🏂 🏋️ 🤼 🤸 ⛹️ 🤺 🤾 🏌️ 🏇 🧘 🏄 🏊 🤽 🚣 🧗 🚴 🚵 🎖 🏅 🏆 🏵 🎗 🎫 🎪 🤹 🎭 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🪕 🎻'.split(' ') },
]
const cur = ref(0)
</script>

<style scoped>
.ep { width: 300px; background: var(--im-panel-bg); border: 1px solid var(--im-border); border-radius: 10px; box-shadow: 0 4px 20px var(--im-shadow); overflow: hidden; }
.ep-tabs { display: flex; border-bottom: 1px solid var(--im-border); }
.ep-tab { flex: 1; border: none; background: transparent; padding: 6px 0; cursor: pointer; font-size: 14px; border-radius: 0; }
.ep-tab.active { background: var(--im-active); }
.ep-grid { display: grid; grid-template-columns: repeat(9, 1fr); gap: 2px; padding: 8px; max-height: 180px; overflow: auto; }
.ep-item { border: none; background: transparent; font-size: 19px; padding: 3px 0; cursor: pointer; border-radius: 6px; }
.ep-item:hover { background: var(--im-hover); }
</style>
