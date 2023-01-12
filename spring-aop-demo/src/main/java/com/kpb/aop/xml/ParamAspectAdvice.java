package com.kpb.aop.xml;

import org.aspectj.lang.JoinPoint;

import java.util.Date;

public class ParamAspectAdvice {

	public void before(JoinPoint joinPoint, int i2, Date date, String string) {
		System.out.println("-----before-----");
		System.out.println(joinPoint);
		System.out.println(i2);
		System.out.println(date);
		System.out.println(string);
		System.out.println("-----before-----");
	}


	public void afterReturning(JoinPoint joinPoint, Date date, String string, int returned) {
		System.out.println("-----afterReturning-----");
		System.out.println(joinPoint);
		System.out.println(date);
		System.out.println(string);
		System.out.println(returned);
		System.out.println("-----afterReturning-----");
	}

	public void afterThrowing(JoinPoint joinPoint, int i, Exception e, Date date) {
		System.out.println("-----afterThrowing-----");
		System.out.println(joinPoint);
		System.out.println(i);
		System.out.println(date);
		System.out.println(e);
		System.out.println("-----afterThrowing-----");
	}

	public void after(JoinPoint joinPoint, int i, Date date, String string) {
		System.out.println("-----after-----");
		System.out.println(joinPoint);
		System.out.println(i);
		System.out.println(date);
		System.out.println(string);
		System.out.println("-----after-----");
	}
}
