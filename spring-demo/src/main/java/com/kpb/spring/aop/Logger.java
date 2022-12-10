package com.kpb.spring.aop;

import org.aspectj.lang.ProceedingJoinPoint;
import org.slf4j.LoggerFactory;

/**
 * 用户记录日志的工具类，里面提供公共的代码
 */
public class Logger {

	private  org.slf4j.Logger logger= LoggerFactory.getLogger(com.kpb.spring.aop.Logger.class);
	/**
	 * 用于打印日志：计划让其在切入点方法执行前执行（切入点方法就是业务层方法）
	 */
	public void beforePrintLog(){
		logger.info("Logger类中的printLog方法开始记录日志了。。。");
	}
	public void afterReturningPrintLog()
	{
		logger.info("后置通知Logger类中的afterReturningPrintLog方法开始记录日志了。。。");
	}
	/**
	 * 异常通知
	 */
	public void afterThrowingPrintLog()
	{
		logger.info("异常通知Logger类中的afterThrowingPrintLog方法开始记录日志了。。。");

	}
	/**
	 * 最终通知
	 */
	public void afterPrintLog()
	{
		logger.info("最终通知Logger类中的afterPrintLog方法开始记录日志了。。。");
	}

	/**
	 * 环绕通知
	 * 问题 当我们配置了环绕通知以后，切入点方法没有执行，而通知方法执行了
	 * 分析： 通过对比动态代理中的环绕通知代码，发现动态代理中的环绕通知有明确的切入点方法调用，而我们的代码中没有
	 * 解决： Spring 框架为我们提供了一个接口：ProceedingJoinPoint。该接口有一个方法proceed（），此方法就相当于明确调用切入点的方法
	 * 该接口可以作为环绕通知的参数方法，在程序执行时，spring框架会为我们提供该接口的实现类供我们使用
	 * spring中的环绕通知
	 * 他是spring框架为我们提供的一种可以在代码中手动控制增强方法何时会执行的方式
	 * @param pjp
	 * @return
	 */
	public Object aroundPrintLog(ProceedingJoinPoint pjp){
		Object rtValue = null;
		try{
			Object[] args = pjp.getArgs();//得到方法执行所需的参数

			logger.info("Logger类中的aroundPrintLog方法开始记录日志了。。。前置");

			rtValue = pjp.proceed(args);//明确调用业务层方法（切入点方法）

			logger.info("Logger类中的aroundPrintLog方法开始记录日志了。。。后置");

			return rtValue;
		}catch (Throwable t){
			logger.info("Logger类中的aroundPrintLog方法开始记录日志了。。。异常");
			throw new RuntimeException(t);
		}finally {
			logger.info("Logger类中的aroundPrintLog方法开始记录日志了。。。最终");
		}
	}
}