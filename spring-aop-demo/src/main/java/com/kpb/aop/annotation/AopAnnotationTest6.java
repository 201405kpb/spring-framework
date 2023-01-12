package com.kpb.aop.annotation;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public class AopAnnotationTest6 {
	public static void main(String[] args) {
		//1 获取容器
		AnnotationConfigApplicationContext ac = new AnnotationConfigApplicationContext(AppConfig.class);
		//2 获取目标类bean
		AspectOrderTarget aspectOrderTarget = ac.getBean("aspectOrderTarget", AspectOrderTarget.class);
		//3 调用目标方法
		aspectOrderTarget.target();
	}
}
