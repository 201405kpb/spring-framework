package com.kpb.aop.annotation;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public class AopAnnotationTest1 {
	public static void main(String[] args) {
		//1 获取容器
		AnnotationConfigApplicationContext ac = new AnnotationConfigApplicationContext(AppConfig.class);
		//2 获取bean
		FirstAopAnnotationTarget ft = ac.getBean("firstAopAnnotationTarget", FirstAopAnnotationTarget.class);
		//3 调用目标方法
		ft.target();
	}
}
