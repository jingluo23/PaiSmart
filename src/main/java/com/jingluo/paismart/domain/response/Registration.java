package com.jingluo.paismart.domain.response;

import com.jingluo.paismart.enums.RegistrationMode;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/16 16:25
 * @Desc: 用户注册策略配置
 */
@Data
public class Registration {

    /**
     * 注册模式，默认仅允许邀请码注册
     */
    private RegistrationMode mode = RegistrationMode.INVITE_ONLY;

    /**
     * 是否必须提供邀请码，默认必须
     */
    private boolean inviteRequired = true;
}
