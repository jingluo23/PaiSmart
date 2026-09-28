package com.jingluo.paismart.domain.response;

import java.util.Objects;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import reactor.core.Disposable;

/**
 * @Author: 鲸落
 * @Date: 2026/9/28 14:47
 * @Desc: 流式 LLM 调用句柄：持有响应式订阅和取消回调， 支持在用户主动停止生成时中断上游请求并回滚 token 预留
 */
@AllArgsConstructor
@NoArgsConstructor
@Data
public class StreamHandle {

    /**
     * 上游流式响应的订阅，dispose 后不再产生新的数据块
     */
    private Disposable subscription;

    /**
     * 取消时的附加回调（如结算/回滚本次调用的 token 预留）
     */
    private Runnable onCancel;

    /**
     * 取消流式调用：先中断上游订阅，再执行取消回调
     */
    public void cancel() {
        if (Objects.nonNull(subscription) && !subscription.isDisposed()) {
            subscription.dispose();
        }

        if (Objects.nonNull(onCancel)) {
            onCancel.run();
        }
    }
}
