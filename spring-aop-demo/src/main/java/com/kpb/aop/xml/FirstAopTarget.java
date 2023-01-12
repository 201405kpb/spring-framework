package com.kpb.aop.xml;

/**
 * 第一个Spring AOP案例
 * 被代理的目标对象
 *
 * @author kpb
 */
public class FirstAopTarget {

	/**
	 * 配置被代理的方法
	 */
	public void target() {
		System.out.println("Method is proxy");
	}

	/**
	 * 没被代理的方法
	 */
	public void target2() {
		System.out.println("Method is not proxy");
	}
}
