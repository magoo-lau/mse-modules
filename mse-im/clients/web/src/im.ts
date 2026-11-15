import { getSDK, CbEvents, WasmSdk } from '@openim/wasm-client-sdk'

/** WASM 资产位于 public/sdk/（postinstall 自 SDK assets 拷贝） */
export function createIM(): WasmSdk {
  return getSDK({ coreWasmPath: '/sdk/openIM.wasm', sqlWasmPath: '/sdk/sql-wasm.wasm' })
}

export { CbEvents }

export interface ImSession {
  userID: string; imToken: string; apiAddr: string; wsAddr: string
  expireAt: number; nickname: string; faceURL: string | null
}

export async function imLogin(sdk: WasmSdk, s: ImSession) {
  await sdk.login({ userID: s.userID, token: s.imToken, platformID: 5, apiAddr: s.apiAddr, wsAddr: s.wsAddr })
}
