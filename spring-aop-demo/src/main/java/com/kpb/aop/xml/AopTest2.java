package com.kpb.aop.xml;

import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * @Author: kpb
 * @Description:  AOP order 信息测试
 * @Date: 创建时间 2023/1/12
 */
public class AopTest2 {

	public static void main(String[] args) {
		//1.获取容器
		ApplicationContext ac = new ClassPathXmlApplicationContext("spring-config2.xml");
		//2.获取对象
		AopTargetOrder aopTargetOrder = (AopTargetOrder)ac.getBean("aopTargetOrder");
		//3.尝试调用被代理类的相关方法
		aopTargetOrder.target();
	}
}
