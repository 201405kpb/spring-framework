package com.kpb.spring;

import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public class SpringAnnotationDemo {

	public static void main(String[] args) {
		ApplicationContext applicationContext = new AnnotationConfigApplicationContext("com.kpb.spring");

		Object helloWorldService = applicationContext.getBean("helloWorldService");
		System.out.println(helloWorldService.getClass().getName());
	}
}
