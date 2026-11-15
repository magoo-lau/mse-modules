/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<object, object, any>
  export default component
}

/** 主应用通过 wujie props 下发的数据（见 mse-ui src/micro/WujieContainer.vue） */
interface MainAppProps {
  /** 子应用在主应用中的路由前缀，router base 依赖它 */
  urlPrefix?: string
  token?: string
  tenantId?: string | number
  platform?: boolean
  nickname?: string
  /** 权限码集合（超管为 ['*']），按钮级显隐依赖它 */
  permissions?: string[]
}

declare global {
  interface Window {
    __POWERED_BY_WUJIE__?: boolean
    __WUJIE_MOUNT?: () => void
    __WUJIE_UNMOUNT?: () => void
    $wujie?: {
      props: MainAppProps
      bus: {
        $on: (event: string, callback: (...args: any[]) => void) => void
        $off: (event: string, callback: (...args: any[]) => void) => void
        $emit: (event: string, ...args: any[]) => void
      }
    }
  }
}

export {}
