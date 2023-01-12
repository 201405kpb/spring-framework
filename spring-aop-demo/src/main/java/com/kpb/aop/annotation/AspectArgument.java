package com.kpb.aop.annotation;

import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Date;

@Component
@Aspect
public class AspectArgument {

	@Pointcut("within(AspectArgumentTarget)")
	public void pt() {
	}

	/**
	 * 后置通知，获取切入点方法的返回值作为参数
	 *
	 * @param date 切入点方法的返回值
	 */
	@AfterReturning(value = "pt()", returning = "date")
	public void afterReturning(Date date) {
		System.out.println("----afterReturning----");
		System.out.println("Get the return value : " + date);
		System.out.println("----afterReturning----");
	}

	/**
	 * 异常通知，获取抛出的异常作为参数
	 *
	 * @param e 前置通知、切入点方法、后置通知执行过程中抛出的异常
	 */
	@AfterThrowing(value = "pt()", throwing = "e")
	public void afterThrowing(Exception e) {
		System.out.println("----afterThrowing----");
		System.out.println("Get the exception : " + Arrays.toString(e.getStackTrace()));
		System.out.println("----afterThrowing----");
	}
}