package com.kpb.aop.xml;

import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * @Author: kpb
 * @Description:  环绕通知
 * @Date: 创建时间 2023/1/12
 */
public class AopTest4 {

	public static void main(String[] args) {
		//1.获取容器
		ApplicationContext ac = new ClassPathXmlApplicationContext("spring-config4.xml");
		//2.获取对象
		AopTargetAround aopTargetAround = (AopTargetAround) ac.getBean("aopTargetAround");
		//3.尝试调用被代理类的相关方法
		int x = 2;
		int y = 1;
		System.out.println("-----外部调用切入点方法传递的参数: " + x + " " + y);
		int target = aopTargetAround.target(x, y);
		//最终返回值
		System.out.println("-----外部调用切入点方法获取的最终返回值: " + target);
	}
}
