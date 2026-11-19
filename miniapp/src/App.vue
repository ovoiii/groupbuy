<script lang="ts">
import { onLaunch } from '@dcloudio/uni-app'
import { syncServerTime } from '@/gb/util'

export default {
  onLaunch() {
    syncServerTime()
  },
}
</script>

<style lang="scss">
page {
  background: #f4f8f5;
  color: #1d231f;
  font-size: 15px;
  font-family: 'PingFang SC', 'HarmonyOS Sans SC', 'MiSans', 'Microsoft YaHei', sans-serif;
}
</style>
