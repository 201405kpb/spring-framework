package com.kpb.aop.annotation;

import org.aspectj.lang.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * order测试
 * order越小的切面，其内部定义的前置通知越先执行，后置通知越后执行。
 * 相同的order的切面则无法确定它们内部的通知执行顺序，同一个切面内的相同类型的通知也无法确定执行顺序。
 */
@Component
public class AspectOrder {
	/**
	 * 默认order为Integer.MAX_VALUE
	 */
	@Component
	@Aspect
	public static class AspectOrder1 {

		@Pointcut("execution(* *..AspectOrderTarget.*())")
		public void pt() {
		}

		@Before("pt()")
		public void before() {
			System.out.println("-----Before advice 1-----");
		}

		@AfterReturning("pt()")
		public void afterReturning() {
			System.out.println("-----afterReturning advice 1-----");
		}

		@After("pt()")
		public void after() {
			System.out.println("-----after advice 1-----");
		}
	}

	/**
	 * 使用注解
	 */
	@Component
	@Aspect
	@Order(Integer.MAX_VALUE - 2)
	public static class AspectOrder2 {

		@Before("com.kpb.aop.annotation.AspectOrder.AspectOrder1.pt()")
		public void before() {
			System.out.println("-----Before advice 2-----");
		}

		@AfterReturning("com.kpb.aop.annotation.AspectOrder.AspectOrder1.pt()")
		public void afterReturning() {
			System.out.println("-----afterReturning advice 2-----");
		}

		@After("com.kpb.aop.annotation.AspectOrder.AspectOrder1.pt()")
		public void after() {
			System.out.println("-----after advice 2-----");
		}
	}

	/**
	 * 实现Ordered接口
	 */
	@Component
	@Aspect
	public static class AspectOrder3 implements Ordered {

		@Before("com.kpb.aop.annotation.AspectOrder.AspectOrder1.pt()")
		public void before() {
			System.out.println("-----Before advice 3-----");
		}

		@AfterReturning("com.kpb.aop.annotation.AspectOrder.AspectOrder1.pt()")
		public void afterReturning() {
			System.out.println("-----afterReturning advice 3-----");
		}

		@After("com.kpb.aop.annotation.AspectOrder.AspectOrder1.pt()")
		public void after() {
			System.out.println("-----after advice 3-----");
		}

		/**
		 * @return 获取order
		 */
		public int getOrder() {
			return Integer.MAX_VALUE - 1;
		}
	}
}