<template>
  <RedisBrowser :manageable="true" />
</template>

<script setup lang="ts">
// 缓存监控：浏览 / INFO 概览与缓存管理同属一页（原独立「缓存管理」页已合并），
// 删键按钮经 v-permission('monitor:cache:delete') 按权限显隐，无权限用户看到的是纯只读视图
import RedisBrowser from '../components/RedisBrowser.vue'
</script>
