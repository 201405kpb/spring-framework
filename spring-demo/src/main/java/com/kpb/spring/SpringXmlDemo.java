package com.kpb.spring;

import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.util.PropertyPlaceholderHelper;

public class SpringXmlDemo {

	public static void main(String[] args) {
		ApplicationContext applicationContext= new ClassPathXmlApplicationContext("classpath:spring-core-config.xml");
		Object helloWorldService = applicationContext.getBean("mFactoryBean");

	}
}


