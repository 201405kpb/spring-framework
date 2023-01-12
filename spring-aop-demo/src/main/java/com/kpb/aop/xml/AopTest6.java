package com.kpb.aop.xml;

import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.util.Date;

/**
 * @Author: kpb
 * @Description:  JoinPoint 方法测试
 * @Date: 创建时间 2023/1/12
 */
public class AopTest6 {

	public static void main(String[] args) {
		//1.获取容器
		ApplicationContext ac = new ClassPathXmlApplicationContext("spring-config6.xml");
		//2.获取对象
		ParamAspectTarget paramAspectTarget = (ParamAspectTarget) ac.getBean("paramAspectTarget");
		//3.尝试调用被代理类的相关方法
		int xx = paramAspectTarget.target(1, new Date(),"");
		System.out.println(xx);
	}
}
