package com.kpb.aop.annotation;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * @Author: kpb
 * @Description: 注解配置通知
 * @Date: 创建时间 2023/1/12
 */
public class AopAnnotationTest3 {
	public static void main(String[] args) {
		//1 获取容器
		AnnotationConfigApplicationContext ac = new AnnotationConfigApplicationContext(AppConfig.class);
		//2 获取目标类bean
		AspectAdviceTarget aspectAdviceTarget = ac.getBean("aspectAdviceTarget", AspectAdviceTarget.class);
		//2 调用目标方法
		aspectAdviceTarget.target();
	}
}
