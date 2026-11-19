/**
 * 不带布局的独立页面路由（仍需登录才能访问）。
 * 模板里默认没有这类页面，需要时按下面的格式添加：
 * { path: '/xxx', name: 'Xxx', component: () => import('/@/views/xxx/index.vue'), meta: { title: 'Xxx', ignoreAuth: true } }
 */
import type { AppRouteModule } from '/@/router/types';

export const mainOutRoutes: AppRouteModule[] = [];

export const mainOutRouteNames = mainOutRoutes.map((item) => item.name);
