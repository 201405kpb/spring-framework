package com.kpb.aop.xml;

/**
 * 第一个Spring AOP案例
 * 该类用于定义通知的逻辑
 *
 * @author kpb
 */
public class FirstAopAspect {

	/**
	 * 通知的行为/逻辑
	 */
	public void helloAop() {
		System.out.println("hello Aop");
	}
}