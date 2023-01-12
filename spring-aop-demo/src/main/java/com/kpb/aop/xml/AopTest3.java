package com.kpb.aop.xml;

import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * @Author: kpb
 * @Description:  测试4种通知
 * @Date: 创建时间 2023/1/12
 */
public class AopTest3 {

	public static void main(String[] args) {
		//1.获取容器
		ApplicationContext ac = new ClassPathXmlApplicationContext("spring-config3.xml");
		//2.获取对象
		AopTarget4Advice aopTarget4Advice = (AopTarget4Advice)ac.getBean("aopTarget4Advice");
		//3.尝试调用被代理类的相关方法
		aopTarget4Advice.target();
	}
}
