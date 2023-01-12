package com.kpb.aop.annotation;

import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

@Component
@Aspect
public class FirstAopAnnotationAspect {

	/**
	 * 一个切入点，绑定到方法上
	 */
	@Pointcut("execution(* *(..))")
	public void pointCut() {
	}


	/**
	 * 一个前置通知，绑定到方法上
	 */
	@Before("pointCut()")
	public void before() {
		System.out.println("----before advice----");
	}

	/**
	 * 一个后置通知，绑定到方法上
	 */
	@AfterReturning("pointCut()")
	public void afterReturning() {
		System.out.println("----afterReturning advice----");
	}
}
