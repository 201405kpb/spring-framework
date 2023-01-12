package com.kpb.aop.annotation;

import org.springframework.stereotype.Component;

import java.util.Date;

@Component
public class AspectArgumentTarget {

	public Date target() {
		Date date = new Date();
		System.out.println("target return: " + date);
		//抛出一个异常
		//int i=1/0;
		return date;
	}
}