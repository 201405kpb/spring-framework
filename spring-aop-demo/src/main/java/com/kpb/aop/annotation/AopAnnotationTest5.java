package com.kpb.aop.annotation;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Date;

/**
 * @Author: kpb
 * @Description: 参数绑定 -- 其他参数绑定
 * @Date: 创建时间 2023/1/12
 */
public class AopAnnotationTest5 {
	public static void main(String[] args) {
		//1 获取容器
		AnnotationConfigApplicationContext ac = new AnnotationConfigApplicationContext(AppConfig.class);
		//2 获取目标类bean
		AspectAttributeTarget aspectAttributeTarget = ac.getBean("aspectAttributeTarget", AspectAttributeTarget.class);
		//2 调用目标方法
		AspectAttributeTarget aspectAttributeTarget1 = new AspectAttributeTarget();
		System.out.println("AspectAttributeTarget: " + aspectAttributeTarget1);
		Date target = aspectAttributeTarget.target(33, "参数", aspectAttributeTarget1);
		System.out.println(" returned value: " + target);
	}
}
