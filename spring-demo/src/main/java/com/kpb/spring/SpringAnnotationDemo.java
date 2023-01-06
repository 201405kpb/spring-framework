package com.kpb.spring;

import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public class SpringAnnotationDemo {

	public static void main(String[] args) {
		// 系统环境变量会影响一个系统平台的所有程序，不可在Java程序中设置和修改，JVM环境变量只会影响一个JVM实例，可以在Java程序中设置和修改。我们使用System.setProperty(key,value)修改和设置JVM环境变量。
		System.setProperty("x","config");
		ApplicationContext applicationContext = new AnnotationConfigApplicationContext("com.kpb.spring");

		Object helloWorldService = applicationContext.getBean("helloWorldService");
		System.out.println(helloWorldService.getClass().getName());
	}
}
