package com.kpb.aop.annotation;

import org.aspectj.lang.annotation.*;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class AspectAdvice {
	/**
	 * 切入点，用于筛选通知将在哪些方法上执行
	 */
	@Pointcut("execution(* AspectAdviceTarget.target())")
	public void pt() {
	}


	//五种通知

	/**
	 * 前置通知
	 * 可以通过名称引用定义好的切入点
	 */
	@Before("pt()")
	public void before() {
		System.out.println("---before---");
	}

	/**
	 * 后置通知
	 * 也可以定义自己的切入点
	 */
	@AfterReturning("execution(* AspectAdviceTarget.target())")
	public void afterReturning() {
		System.out.println("---afterReturning---");
	}

	/**
	 * 异常通知
	 */
	@AfterThrowing("pt()")
	public void afterThrowing() {
		System.out.println("---afterThrowing---");
	}

	/**
	 * 最终通知
	 */
	@After("pt()")
	public void after() {
		System.out.println("---after---");
	}

}