package com.kpb.aop.annotation;

import org.springframework.context.annotation.Description;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
public class AspectAttributeTarget {

	@Description("描述")
	public Date target(int i, String s, AspectAttributeTarget aspectAttributeTarget) {
		Date date = new Date();
		System.out.println("target return: " + date);
		//抛出一个异常
		//int j=1/0;
		return date;
	}
}