package com.kpb.aop.xml;

import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * @Author: kpb
 * @Description:  切入点表达式
 * @Date: 创建时间 2023/1/12
 */
public class AopTest7 {

	public static void main(String[] args) {
		//1.获取容器
		ApplicationContext ac = new ClassPathXmlApplicationContext("spring-config7.xml");
		//2.获取对象
		AopTargetPointcut aopTargetPointcut = (AopTargetPointcut) ac.getBean("aopTargetPointcut");
		//3.尝试调用被代理类的相关方法
		aopTargetPointcut.target1();
		System.out.println("----------");
		aopTargetPointcut.target2();
	}
}
