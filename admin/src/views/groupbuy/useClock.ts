// 倒计时：按服务器时间算，每秒刷新
import { onBeforeUnmount, ref } from 'vue';
import { toDate } from './api';

const now = ref(Date.now());
let offset = 0;
let timer: any = null;
let users = 0;

export function syncServerTime(serverTime?: any) {
  if (serverTime) offset = toDate(serverTime).getTime() - Date.now();
  now.value = Date.now() + offset;
}

export function useClock() {
  users++;
  if (!timer) timer = setInterval(() => (now.value = Date.now() + offset), 1000);
  onBeforeUnmount(() => {
    if (--users === 0) { clearInterval(timer); timer = null; }
  });
  const leftSec = (t: any) => (t ? Math.max(0, (toDate(t).getTime() - now.value) / 1000) : 0);
  return { now, leftSec };
}
