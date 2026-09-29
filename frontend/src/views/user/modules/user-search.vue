<script setup lang="ts">
import { userRoleOptions } from '@/constants/common';

defineOptions({
  name: 'UserSearch'
});

const emit = defineEmits<{
  search: [];
}>();

const { formRef } = useNaiveForm();

const model = defineModel<Api.User.SearchParams>('model', { required: true });

/** 仅追踪业务搜索字段的变化：model 是与 useTable 共享的同一个 reactive 对象， 翻页/改页大小会被分页处理器原地写入 page/size，若不过滤会导致每次翻页重复触发查询 */
const SEARCH_FIELD_KEYS = ['keyword', 'orgTag', 'status'] as const;

const signatureOf = () => JSON.stringify(SEARCH_FIELD_KEYS.map(key => model.value[key] ?? ''));

let lastSignature = signatureOf();

watch(
  model,
  () => {
    const signature = signatureOf();
    if (signature === lastSignature) {
      return;
    }
    lastSignature = signature;
    emit('search');
  },
  { deep: true }
);
</script>

<template>
  <NCard :bordered="false" size="small" class="rd-full px-6">
    <NForm ref="formRef" :model="model" label-placement="left" :show-feedback="false" inline>
      <NFormItem label="关键词" path="keyword">
        <NInput v-model:value="model.keyword" placeholder="请输入关键词" clearable />
      </NFormItem>
      <NFormItem label="组织标签" path="orgTag">
        <OrgTagCascader v-model:value="model.orgTag" clearable class="w-200px!" />
      </NFormItem>
      <NFormItem label="角色" path="status">
        <NSelect
          v-model:value="model.status"
          placeholder="请选择角色"
          :options="userRoleOptions"
          clearable
          class="w-200px!"
        />
      </NFormItem>
    </NForm>
  </NCard>
</template>

<style scoped></style>
