package com.kpb.aop.annotation;

import com.kpb.aop.annotation.first.FirstAopAnnotationTarget;
import com.kpb.aop.annotation.first.FirstAppConfig;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public class AopAnnotationTest1 {
	public static void main(String[] args) {
		//1 获取容器
		AnnotationConfigApplicationContext ac = new AnnotationConfigApplicationContext(FirstAppConfig.class);
		//2 获取bean
		FirstAopAnnotationTarget ft = ac.getBean("firstAopAnnotationTarget", FirstAopAnnotationTarget.class);
		//3 调用目标方法
		ft.target();
	}
}
