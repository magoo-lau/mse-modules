/** 把 clients/web 的桌面构建产物（dist-desktop）同步为壳内 web-dist；缺失时提示先构建 */
const fs = require('fs')
const path = require('path')

const src = path.join(__dirname, '..', '..', 'web', 'dist-desktop')
const dst = path.join(__dirname, '..', 'web-dist')

if (!fs.existsSync(path.join(src, 'index.html'))) {
  console.error('未找到桌面构建产物：' + src)
  console.error('请先执行：cd clients/web && npm run build:desktop')
  process.exit(1)
}
fs.rmSync(dst, { recursive: true, force: true })
fs.cpSync(src, dst, { recursive: true })
console.log('web-dist 已同步自 ' + src)
