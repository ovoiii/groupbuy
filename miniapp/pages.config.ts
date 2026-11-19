import { defineUniPages } from '@uni-helper/vite-plugin-uni-pages'

// 页面路由写在各页面的 <route> 块里，这里只放全局配置和底部标签栏
export default defineUniPages({
  globalStyle: {
    navigationStyle: 'default',
    navigationBarTitleText: '邻里鲜',
    navigationBarBackgroundColor: '#FFFFFF',
    navigationBarTextStyle: 'black',
    backgroundColor: '#F4F8F5',
  },
  easycom: {
    autoscan: true,
    custom: {
      '^wd-(.*)': 'wot-design-uni/components/wd-$1/wd-$1.vue',
    },
  },
  tabBar: {
    color: '#6B716D',
    selectedColor: '#078149',
    backgroundColor: '#FFFFFF',
    borderStyle: 'white',
    list: [
      { pagePath: 'pages/index/index', text: '首页', iconPath: 'static/tabbar/home.png', selectedIconPath: 'static/tabbar/home-on.png' },
      { pagePath: 'pages/cart/cart', text: '购物车', iconPath: 'static/tabbar/cart.png', selectedIconPath: 'static/tabbar/cart-on.png' },
      { pagePath: 'pages/orders/orders', text: '订单', iconPath: 'static/tabbar/order.png', selectedIconPath: 'static/tabbar/order-on.png' },
      { pagePath: 'pages/mine/mine', text: '我的', iconPath: 'static/tabbar/mine.png', selectedIconPath: 'static/tabbar/mine-on.png' },
    ],
  },
})
