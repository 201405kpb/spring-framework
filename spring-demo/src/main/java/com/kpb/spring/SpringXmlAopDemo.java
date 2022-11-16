package com.kpb.spring;

import com.kpb.spring.aop.IAccountService;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

public class SpringXmlAopDemo {

	public static void main(String[] args) {

		ApplicationContext applicationContext= new ClassPathXmlApplicationContext("classpath:spring-core-aop.xml");

		IAccountService accountService = (IAccountService) applicationContext.getBean("accountServices");
		accountService.deleteAccount();
	}
}
