import { transformRecordToOption } from '@/utils/common';

export const yesOrNoRecord: Record<CommonType.YesOrNo, App.I18n.I18nKey> = {
  Y: 'common.yesOrNo.yes',
  N: 'common.yesOrNo.no'
};

export const yesOrNoOptions = transformRecordToOption(yesOrNoRecord);

export const userRoleOptions = [
  { label: '用户', value: 1 },
  { label: '管理员', value: 0 }
];

export const chunkSize = 5 * 1024 * 1024;

export const uploadAccept = '.pdf,.doc,.docx,.txt';
