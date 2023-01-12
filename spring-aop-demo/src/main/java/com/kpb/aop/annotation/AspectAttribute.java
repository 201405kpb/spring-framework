package com.kpb.aop.annotation;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.context.annotation.Description;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
@Aspect
public class AspectAttribute {
	/**
	 * 使用args()传递参数值给通知方法的参数，args用于指定通知方法的参数名称对应切入点方法的第几个参数，这将会进行参数值的注入，如果类型不匹配，那么该通知将不会执行（不会抛出异常）
	 * argNames属性也可以指定参数名，这个属性在使用注解时基本都可以省略
	 */
	@Before(value = "execution(* com.kpb.aop.annotation.AspectAttributeTarget.target(..)) && args(i,s,aspectAttributeTarget,..)", argNames = "joinPoint,i,s,aspectAttributeTarget")
	public void before(JoinPoint joinPoint, int i, String s, AspectAttributeTarget aspectAttributeTarget) {
		System.out.println("----------before attribute----------");
		System.out.println(joinPoint);
		System.out.println(i);
		System.out.println(s);
		System.out.println(aspectAttributeTarget);
		System.out.println("----------before attribute----------");

	}

	/**
	 * 使用this()传递代理对象
	 * 使用target()传递目标对象
	 */
	@Before(value = "execution(* com.kpb.aop.annotation.AspectAttributeTarget.target(..)) && this(aspectAttributeTargetAop) && target(aspectAttributeTarget)")
	public void before2(JoinPoint joinPoint, AspectAttributeTarget aspectAttributeTargetAop, AspectAttributeTarget aspectAttributeTarget) {
		System.out.println("----------before this&target----------");
		System.out.println(joinPoint);
		System.out.println("当前代理对象: " + aspectAttributeTargetAop.getClass());
		System.out.println("当前目标对象: " + aspectAttributeTarget.getClass());
		System.out.println("----------before this&target----------");

	}

	/**
	 * 预定义的切入点。可以在Pointcut中定义一个传递参数的模版，这要求Pointcut绑定的方法同样具有参数
	 * 在通知中引用这个Pointcut时，需要在指定参数位置传递所需的参数名（这个名字是通知方法的参数名）
	 */
	@Pointcut(value = "execution(* com.kpb.aop.annotation.AspectAttributeTarget.target(..)) && args(i,s,aspectAttributeTarget,..)")
	public void attribute(int i, String s, AspectAttributeTarget aspectAttributeTarget) {
	}

	/**
	 * 引入预定义的切入点，绑定参数和返回值
	 * 在通知中引用这个Pointcut时，需要在指定参数位置传递所需的参数名（这个名字是通知方法的参数名）
	 * 使用args()传递参数值给通知方法的参数，如果args对应的名称一致，那么可以省略argNames属性（argNames属性用于确定参数名称）
	 */
	@AfterReturning(value = "attribute(i,s,aspectAttributeTarget)", returning = "date")
	public void afterReturning(JoinPoint joinPoint, int i, String s, AspectAttributeTarget aspectAttributeTarget, Date date) {
		System.out.println("----------afterReturning attribute&returned value----------");
		System.out.println(joinPoint);
		System.out.println(i);
		System.out.println(s);
		System.out.println(aspectAttributeTarget);
		System.out.println(date);
		System.out.println("----------afterReturning attribute&returned value----------");
	}


	/**
	 * 引入预定义的切入点，绑定参数和异常
	 * 在通知中引用这个Pointcut时，需要在指定参数位置传递所需的参数名（这个名字是通知方法的参数名）
	 * args()还可以传递我们所需要的参数而不是全部，参数位置也不一定需要和切入点方法的参数位置一致，只要参数名称对应
	 */
	@AfterThrowing(value = "attribute(i1,*,aspectAttributeTarget1)", throwing = "e")
	public void afterThrowing(JoinPoint joinPoint, int i1, Exception e, AspectAttributeTarget aspectAttributeTarget1) {
		System.out.println("----------afterThrowing attribute&exception----------");
		System.out.println(joinPoint);
		System.out.println(i1);
		System.out.println(aspectAttributeTarget1);
		System.out.println("exception: " + e.getMessage());
		System.out.println("----------afterThrowing attribute&exception----------");
	}

	/**
	 * 可以使用@annotation()绑定切入点方法的注解作为参数
	 */
	@After(value = "execution(* com.kpb.aop.annotation.AspectAttributeTarget.target(..)) && @annotation(description)")
	public void after(JoinPoint joinPoint, Description description) {
		System.out.println("----------after annotation----------");
		System.out.println(joinPoint);
		System.out.println("annotation: " + description);
		System.out.println("----------after annotation----------");
	}

	//……………………
}

