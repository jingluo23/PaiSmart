package com.jingluo.paismart.utils;

import java.text.DecimalFormat;
import java.util.Objects;

/**
 * 金额工具类，提供分转元的格式化能力
 *
 * @Author: 鲸落
 * @Date: 2026/9/17 9:44
 */
public class PriceUtil {

    /**
     * 将分转换为元的字符串形式，整元不带小数（如 100 -> "1"），整角保留一位小数（如 150 -> "1.5"）， 其余保留两位小数（如 155 -> "1.55"）
     *
     * @param price
     *            金额，单位分
     * @return 元为单位的金额字符串，入参为 null 时返回 null
     */
    public static String toYuanPrice(Long price) {
        if (Objects.isNull(price)) {
            return null;
        }

        DecimalFormat df1 = new DecimalFormat("0.00");
        String ans = df1.format(price / 100f);

        if (price % 100 == 0) {
            // 整元时，移除后面的小数
            return ans.substring(0, ans.length() - 3);
        } else if (price % 10 == 0) {
            return ans.substring(0, ans.length() - 1);
        }

        return ans;
    }
}
