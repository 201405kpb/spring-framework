package com.kpb.aop.xml;

import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * @Author: kpb
 * @Description:  declare-parents 引介
 * @Date: 创建时间 2023/1/12
 */
public class AopTest9 {

	public static void main(String[] args) {
		//1.获取容器
		ApplicationContext ac = new ClassPathXmlApplicationContext("spring-config9.xml");
		//2.获取对象
		AdvisorTarget advisorTarget = (AdvisorTarget) ac.getBean("advisorTarget");
		//3.尝试调用被代理类的相关方法
		advisorTarget.target();
	}
}
