package com.kpb.aop.xml;

import org.aspectj.lang.ProceedingJoinPoint;

import java.util.Arrays;

/**
 * @Author: kpb
 * @Description: 环绕通知
 * @Date: 创建时间 2023/1/12
 */
public class AopAspectAround {
	/**
	 * 一定要有ProceedingJoinPoint类型的参数
	 */
	public int around(ProceedingJoinPoint pjp) {
		int finalReturn = 0;
		Object[] args = pjp.getArgs();
		System.out.println("外部传递的参数: " + Arrays.toString(args));
		System.out.println("==前置通知==");
		try {
			//proceed调用切入点方法，args表示参数，proceed就是切入点方法的返回值
			Object proceed = pjp.proceed(args);
			//也可以直接掉用proceed方法，它会自动传递参数外部的参数
			//Object proceed = pjp.proceed(args);
			System.out.println("切入点方法的返回值: " + proceed);
			System.out.println("==后置通知==");
			finalReturn = (int) proceed;
		} catch (Throwable throwable) {
			throwable.printStackTrace();
			System.out.println("==异常通知==");
			finalReturn = 444;
		} finally {
			System.out.println("==最终通知==");
		}
		//外部调用切入点方法获取的最终返回值
		return finalReturn;
	}
}
