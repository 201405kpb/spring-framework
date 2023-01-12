package com.kpb.aop.xml;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;

import java.util.Arrays;

/**
 * @Author: kpb
 * @Description:  JoinPoint 方法测试
 * @Date: 创建时间 2023/1/12
 */
public class JoinPointAspectAdvice {

	public void advice(JoinPoint joinPoint) {
		System.out.println("_______advice______");
		System.out.println(joinPoint.getClass());
		System.out.println(joinPoint.toString());
		System.out.println(joinPoint.toShortString());
		System.out.println(joinPoint.toLongString());
		System.out.println(joinPoint.getThis());
		System.out.println(joinPoint.getTarget());
		System.out.println(Arrays.toString(joinPoint.getArgs()));
		System.out.println(joinPoint.getSignature());
		System.out.println(joinPoint.getSourceLocation());
		System.out.println(joinPoint.getKind());
		System.out.println(joinPoint.getStaticPart());
		System.out.println("_____advice_____");
	}

	public Object adviceAdvice(ProceedingJoinPoint joinPoint) {
		System.out.println("_______adviceAdvice______");
		System.out.println(joinPoint.getClass());
		System.out.println(joinPoint.toString());
		System.out.println(joinPoint.toShortString());
		System.out.println(joinPoint.toLongString());
		System.out.println(joinPoint.getThis().getClass());
		System.out.println(joinPoint.getTarget().getClass());
		System.out.println(Arrays.toString(joinPoint.getArgs()));
		System.out.println(joinPoint.getSignature());
		//SourceLocation相关方法不支持
		System.out.println(joinPoint.getSourceLocation());
		System.out.println(joinPoint.getKind());
		System.out.println(joinPoint.getStaticPart());
		//执行方法
		Object proceed = null;
		try {
			proceed = joinPoint.proceed();
			System.out.println(proceed);
		} catch (Throwable throwable) {
			throwable.printStackTrace();
		}
		System.out.println("_____adviceAdvice_____");
		return proceed;
	}
}
