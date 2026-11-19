// manifest.config.ts
import { defineManifestConfig } from '@uni-helper/vite-plugin-uni-manifest'
import path from 'node:path'
import { loadEnv } from 'vite'

const env = loadEnv(process.env.NODE_ENV!, path.resolve(process.cwd(), 'env'))
const { VITE_APP_TITLE, VITE_UNI_APPID, VITE_WX_APPID, VITE_APP_PUBLIC_BASE, VITE_FALLBACK_LOCALE } = env

export default defineManifestConfig({
  name: VITE_APP_TITLE,
  appid: VITE_UNI_APPID,
  description: '',
  versionName: '1.0.0',
  versionCode: '100',
  transformPx: false,
  locale: VITE_FALLBACK_LOCALE, // 'zh-Hans'
  /* 微信小程序 */
  'mp-weixin': {
    appid: VITE_WX_APPID,
    setting: {
      urlCheck: false, // 开发期不校验合法域名；上线前在微信公众平台配置 request 合法域名
      minified: true,
      es6: true,
    },
    usingComponents: true,
    lazyCodeLoading: 'requiredComponents',
    // 定位：选提货点按距离排序、团长申请时在地图上标位置。上线前还要在公众平台「用户隐私保护指引」里勾选位置信息
    requiredPrivateInfos: ['getLocation', 'chooseLocation'],
    permission: { 'scope.userLocation': { desc: '用于按距离推荐提货点、标记你申请的提货点位置' } },
  },
  // H5 仅用于浏览器调试
  h5: {
    router: { base: VITE_APP_PUBLIC_BASE },
  },
  uniStatistics: { enable: false },
  vueVersion: '3',
})
