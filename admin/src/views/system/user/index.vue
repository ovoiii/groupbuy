<template>
  <div>
    <!--引用表格-->
    <BasicTable @register="registerTable" :rowSelection="rowSelection">
      <!--插槽:table标题-->
      <template #tableTitle>
        <a-radio-group v-model:value="statusTab" button-style="solid" @change="onStatusChange">
          <a-radio-button value="">全部</a-radio-button>
          <a-radio-button value="1">正常</a-radio-button>
          <a-radio-button value="2">冻结</a-radio-button>
        </a-radio-group>
        <a-button type="primary" preIcon="ant-design:plus-outlined" @click="handleCreate"> 新增</a-button>
        <a-button preIcon="ant-design:export-outlined" @click="onExportXls"> 导出</a-button>
        <import-excel-progress :upload-url="getImportUrl" @success="reload"></import-excel-progress>
        <a-button @click="openModal(true, {})" preIcon="ant-design:hdd-outlined"> 回收站</a-button>
        <!-- 勾选后直接出现批量操作，不用再点一次下拉 -->
        <template v-if="selectedRowKeys.length > 0">
          <span class="user-bulk-tip">已选 {{ selectedRowKeys.length }} 名用户</span>
          <a-button preIcon="ant-design:lock-outlined" @click="batchFrozen(2)"> 冻结</a-button>
          <a-button preIcon="ant-design:unlock-outlined" @click="batchFrozen(1)"> 解冻</a-button>
          <a-button v-if="hasPermission('system:user:resetPassword')" preIcon="ant-design:reload-outlined" @click="batchResetPassword()"> 重置密码</a-button>
          <a-button danger preIcon="ant-design:delete-outlined" @click="batchHandleDelete"> 删除</a-button>
        </template>
      </template>
      <!--操作栏-->
      <template #action="{ record }">
        <TableAction :actions="getTableAction(record)" :dropDownActions="getDropDownAction(record)" />
      </template>
    </BasicTable>
    <!--用户抽屉-->
    <UserDrawer @register="registerDrawer" @success="handleSuccess" />
    <!--修改密码-->
    <PasswordModal @register="registerPasswordModal" @success="reload" />
    <!--回收站-->
    <UserRecycleBinModal @register="registerModal" @success="reload" />
    <!-- 离职人员列弹窗 -->
    <UserQuitModal @register="registerQuitModal" @success="reload" />
  </div>
</template>

<script lang="ts" name="system-user" setup>
  //ts语法
  import { ref, computed, unref } from 'vue';
  import { BasicTable, TableAction, ActionItem } from '/@/components/Table';
  import UserDrawer from './UserDrawer.vue';
  import UserRecycleBinModal from './UserRecycleBinModal.vue';
  import PasswordModal from './PasswordModal.vue';
  import JThirdAppButton from '/@/components/jeecg/thirdApp/JThirdAppButton.vue';
  import UserQuitModal from './UserQuitModal.vue';
  import { useDrawer } from '/@/components/Drawer';
  import { useListPage } from '/@/hooks/system/useListPage';
  import { useModal } from '/@/components/Modal';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { columns, searchFormSchema } from './user.data';
  import { listNoCareTenant, deleteUser, batchDeleteUser, getImportUrl, getExportUrl, frozenBatch, resetPassword } from './user.api';
  import { batchPreloadDepartPathNames } from '/@/utils/common/compUtils';
  import { usePermission } from '/@/hooks/web/usePermission';
  import ImportExcelProgress from './components/ImportExcelProgress.vue';

  const { createMessage, createConfirm } = useMessage();
  const { isDisabledAuth, hasPermission } = usePermission();
  
  const statusTab = ref('');
  //注册drawer
  const [registerDrawer, { openDrawer }] = useDrawer();
  //回收站model
  const [registerModal, { openModal }] = useModal();
  //密码model
  const [registerPasswordModal, { openModal: openPasswordModal }] = useModal();
  //代理人model
  const [registerAgentModal, { openModal: openAgentModal }] = useModal();
  //离职代理人model
  const [registerQuitAgentModal, { openModal: openQuitAgentModal }] = useModal();
  //离职用户列表model
  const [registerQuitModal, { openModal: openQuitModal }] = useModal();

  // 列表页面公共参数、方法
  const { prefixCls, tableContext, onExportXls, onImportXls } = useListPage({
    designScope: 'user-list',
    tableProps: {
      title: '用户列表',
      api: listNoCareTenant,
      columns: columns,
      canResize: true,
      size: 'small',
      formConfig: {
        // labelWidth: 200,
        schemas: searchFormSchema,
      },
      actionColumn: {
        width: 120,
      },
      afterFetch: async (result) => {
        // 批量预加载部门/岗位路径名称，避免每个单元格逐条请求接口
        await batchPreloadDepartPathNames(result);
        return result;
      },
      beforeFetch: (params) => {
        // 表头上方的状态分段优先于查询表单里的「用户状态」
        // 注意：useListPage 会忽略这里的返回值，必须直接改 params
        if (statusTab.value) {
          params.status = statusTab.value;
        }
        return Object.assign({ column: 'createTime', order: 'desc' }, params);
      },
      defSort: {
        column: "",
        order: ""
      },
    },
    exportConfig: {
      name: '用户列表',
      url: getExportUrl,
    },
    importConfig: {
      url: getImportUrl,
    },
  });

  // 状态分段：全部 / 正常(1) / 冻结(2)
  function onStatusChange() {
    reload({ page: 1 });
  }

  //注册table数据
  const [registerTable, { reload, updateTableDataRecord, clearSelectedRowKeys }, { rowSelection, selectedRows, selectedRowKeys }] = tableContext;

  /**
   * 新增事件
   */
  function handleCreate() {
    openDrawer(true, {
      isUpdate: false,
      showFooter: true,
      tenantSaas: false,
    });
  }
  /**
   * 编辑事件
   */
  async function handleEdit(record: Recordable) {
    openDrawer(true, {
      record,
      isUpdate: true,
      showFooter: true,
      tenantSaas: false,
    });
  }
  /**
   * 详情
   */
  async function handleDetail(record: Recordable) {
    openDrawer(true, {
      record,
      isUpdate: true,
      showFooter: false,
      tenantSaas: false,
    });
  }
  /**
   * 删除事件
   */
  async function handleDelete(record) {
    if ('admin' == record.username) {
      createMessage.warning('管理员账号不允许此操作！');
      return;
    }
    await deleteUser({ id: record.id }, reload);
  }
  /**
   * 批量删除事件
   */
  async function batchHandleDelete() {
    let hasAdmin = unref(selectedRows).filter((item) => item.username == 'admin');
    if (unref(hasAdmin).length > 0) {
      createMessage.warning('管理员账号不允许此操作！');
      return;
    }
    await batchDeleteUser({ ids: selectedRowKeys.value }, () => {
      selectedRowKeys.value = [];
      reload();
    });
  }
  /**
   * 成功回调
   */
  function handleSuccess() {
    reload();
  }

  /**
   * 打开修改密码弹窗
   */
  function handleChangePassword(username) {
    openPasswordModal(true, { username });
  }
  /**
   * 冻结解冻
   */
  async function handleFrozen(record, status) {
    if ('admin' == record.username) {
      createMessage.warning('管理员账号不允许此操作！');
      return;
    }
    await frozenBatch({ ids: record.id, status: status }, reload);
  }
  /**
   * 批量冻结解冻
   */
  function batchFrozen(status) {
    let hasAdmin = selectedRows.value.filter((item) => item.username == 'admin');
    if (unref(hasAdmin).length > 0) {
      createMessage.warning('管理员账号不允许此操作！');
      return;
    }
    createConfirm({
      iconType: 'warning',
      title: '确认操作',
      content: '是否' + (status == 1 ? '解冻' : '冻结') + '选中账号?',
      onOk: async () => {
        await frozenBatch({ ids: unref(selectedRowKeys).join(','), status: status }, reload);
      },
    });
  }
  /**
   * 批量重置密码
   */
  function batchResetPassword() {
    let hasAdmin = selectedRows.value.filter((item) => item.username == 'admin');
    if (unref(hasAdmin).length > 0) {
      createMessage.warning('所选用户中包含管理员，管理员账号不允许重置密码！！');
      return;
    }
    if (selectedRows.value.length > 0) {
      createConfirm({
        iconType: 'warning',
        title: '确认操作',
        content: '是否重置选中的账号密码?',
        onOk: async () => {
          const usernames = selectedRows.value.map((item) => item.username).join(',');
          await resetPassword({ usernames: usernames }, ()=>{reload();clearSelectedRowKeys();});
        },
      });
    }
  }


  /**
   *同步钉钉和微信回调
   */
  function onSyncFinally({ isToLocal }) {
    // 同步到本地时刷新下数据
    if (isToLocal) {
      reload();
    }
  }

  /**
   * 操作栏
   */
  function getTableAction(record): ActionItem[] {
    return [
      {
        label: '编辑',
        onClick: handleEdit.bind(null, record),
        // ifShow: () => hasPermission('system:user:edit'),
      },
    ];
  }
  /**
   * 下拉操作栏
   */
  function getDropDownAction(record): ActionItem[] {
    return [
      {
        label: '详情',
        onClick: handleDetail.bind(null, record),
      },
      {
        label: '密码',
        //auth: 'user:changepwd',
        onClick: handleChangePassword.bind(null, record.username),
      },
      {
        label: '删除',
        popConfirm: {
          title: '是否确认删除',
          confirm: handleDelete.bind(null, record),
        },
      },
      {
        label: '冻结',
        ifShow: record.status == 1,
        popConfirm: {
          title: '确定冻结吗?',
          confirm: handleFrozen.bind(null, record, 2),
        },
      },
      {
        label: '解冻',
        ifShow: record.status == 2,
        popConfirm: {
          title: '确定解冻吗?',
          confirm: handleFrozen.bind(null, record, 1),
        },
      },
    ];
  }

</script>

<style scoped></style>

<style lang="less">
  .user-cell {
    display: flex;
    align-items: center;
    gap: 10px;
    min-width: 0;
    text-align: left;

    &.frozen {
      opacity: 0.6;
    }
  }
  .user-avatar {
    display: inline-grid;
    place-items: center;
    flex: none;
    width: 32px;
    height: 32px;
    border-radius: 50%;
    object-fit: cover;
    background: var(--brand-primary-soft-2);
    color: var(--brand-primary);
    font-size: 12px;
    font-weight: 700;
  }
  .user-cell-2 {
    display: flex;
    flex-direction: column;
    min-width: 0;
    line-height: 1.4;
    text-align: left;

    .name {
      font-weight: 600;
    }
    .sub {
      font-size: 12px;
      color: var(--brand-text-3);
      overflow: hidden;
      white-space: nowrap;
      text-overflow: ellipsis;
    }
    .mono {
      font-family: Consolas, 'Cascadia Mono', monospace;
    }
  }
  .user-status {
    display: inline-flex;
    align-items: center;
    height: 22px;
    padding: 0 8px;
    border-radius: 4px;
    font-size: 12px;

    &.ok {
      background: var(--brand-success-soft);
      color: var(--brand-success);
    }
    &.frozen {
      background: var(--brand-danger-soft);
      color: var(--brand-danger);
    }
  }
  .user-bulk-tip {
    margin: 0 4px 0 12px;
    color: var(--brand-text-2);
  }
</style>
