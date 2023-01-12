package com.kpb.aop.annotation;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Date;

/**
 * @Author: kpb
 * @Description: 参数绑定 -- 返回值和异常绑定
 * @Date: 创建时间 2023/1/12
 */
public class AopAnnotationTest4 {
	public static void main(String[] args) {
		//1 获取容器
		AnnotationConfigApplicationContext ac = new AnnotationConfigApplicationContext(AppConfig.class);
		//2 获取目标类bean
		AspectArgumentTarget aspectArgumentTarget = ac.getBean("aspectArgumentTarget", AspectArgumentTarget.class);
		//2 调用目标方法
		Date target = aspectArgumentTarget.target();
		System.out.println(" returned value: "+target);
	}
}
