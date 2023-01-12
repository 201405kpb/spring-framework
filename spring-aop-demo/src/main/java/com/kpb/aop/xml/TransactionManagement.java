package com.kpb.aop.xml;


import org.springframework.aop.AfterReturningAdvice;
import org.springframework.aop.MethodBeforeAdvice;
import org.springframework.aop.ThrowsAdvice;

import java.lang.reflect.Method;
import java.rmi.RemoteException;

public class TransactionManagement implements MethodBeforeAdvice, AfterReturningAdvice, ThrowsAdvice {
	/**
	 * 前置通知
	 */
	@Override
	public void before(Method arg0, Object[] arg1, Object arg2) {
		System.out.println("前置通知！");
	}

	/**
	 * 后置通知
	 */
	@Override
	public void afterReturning(Object arg0, Method arg1, Object[] arg2,
							   Object arg3) {
		System.out.println("后置通知!");
	}

	//异常通知


	public void afterThrowing(RemoteException ex) {
		System.out.println("异常通知!");
		// Do something with remote exception
	}

	public void afterThrowing(Exception ex) {
		System.out.println("异常通知!");
	}

	public void afterThrowing(Method method, Object[] args, Object target, Exception ex) {
		System.out.println("异常通知!");
	}

}