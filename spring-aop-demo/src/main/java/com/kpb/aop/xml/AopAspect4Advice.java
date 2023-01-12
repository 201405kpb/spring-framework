package com.kpb.aop.xml;

/**
 * @Author: kpb
 * @Description: 测试4 种通知
 * @Date: 创建时间 2023/1/12
 */
public class AopAspect4Advice {

	/**
	 * 前置通知
	 */
	public void beforeAdvice() {
		System.out.println("before advice");
		//这将引发一个异常，可以被异常通知捕获，并尝试传递给通知方法的参数，这还会导致切入点方法不被执行
		//int j=1/0;
	}

	/**
	 * 后置通知
	 */
	public void afterReturningAdvance(int i) {
		System.out.println("i: " + i);
		System.out.println("after-returning advice");
		//这将引发一个异常，可以被异常通知捕获，并尝试传递给通知方法的参数
		//最终抛出异常是时，它将覆盖前面所有流程中抛出的异常。
		//Object o=null;
		//o.toString();
	}

	/**
	 * 异常通知
	 */
	public void afterThrowingAdvance(Exception e) {
		System.out.println("e: " + e.getMessage());
		System.out.println("after-throwing advice");
		//这将引发一个异常，它将覆盖前面所有流程中抛出的异常。
		//int j=1/0;
	}

	/**
	 * 最终通知
	 */
	public void afterAdvance() {
		System.out.println("after advice");
		//这将引发一个异常，它将覆盖前面所有流程中抛出的异常，最终输出的异常就行就是该异常。
		//int j=1/0;
	}
}
