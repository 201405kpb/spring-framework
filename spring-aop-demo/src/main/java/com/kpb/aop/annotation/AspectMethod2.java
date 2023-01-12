package com.kpb.aop.annotation;

import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

@Component
@Aspect
public class AspectMethod2 {
	/**
	 * 一个切入点，绑定到方法上，该切入点匹配“任何方法”
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
	 * 切面类的方法，无法被增强
	 */
	public void aspectMethod2() {
		System.out.println("aspectMethod2");
	}
}