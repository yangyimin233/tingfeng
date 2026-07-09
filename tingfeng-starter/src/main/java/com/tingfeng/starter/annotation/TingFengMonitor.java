package com.tingfeng.starter.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TingFengMonitor {

    enum Strategy {
        /** 上报所有调用，不论是否异常 */
        ALL,
        /** 仅在发生异常时上报（默认） */
        EXCEPTION_ONLY
    }

    /** 上报策略，默认上报所有调用 */
    Strategy value() default Strategy.ALL;

    /** 方法别名，留空则取类名#方法名 */
    String name() default "";
}
