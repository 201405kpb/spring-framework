package com.kpb.aop.annotation;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * @Author: kpb
 * @Description: 在Spring AOP中，切面类本身不能成为其他切面通知的目标，类上面标注了@Aspect注解之后，
 * 该类的bean将从AOP自动配置bean中排除，因此，切面类里面的方法都不能被代理。
 * @Date: 创建时间 2023/1/12
 */
public class AopAnnotationTest2 {
	public static void main(String[] args) {
		//1 获取容器
		AnnotationConfigApplicationContext ac = new AnnotationConfigApplicationContext(AppConfig.class);
		//2 获取切面类bean
		AspectMethod1 am1 = ac.getBean("aspectMethod1", AspectMethod1.class);
		AspectMethod2 am2 = ac.getBean("aspectMethod2", AspectMethod2.class);
		//3 调用切面类方法，并不会被代理
		am1.aspectMethod1();
		am2.aspectMethod2();
	}
}
