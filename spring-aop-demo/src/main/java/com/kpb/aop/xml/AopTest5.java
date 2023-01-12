package com.kpb.aop.xml;

import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.util.Date;
import java.util.List;

/**
 * @Author: kpb
 * @Description:  JoinPoint 方法测试
 * @Date: 创建时间 2023/1/12
 */
public class AopTest5 {

	public static void main(String[] args) {
		//1.获取容器
		ApplicationContext ac = new ClassPathXmlApplicationContext("spring-config5.xml");
		//2.获取对象
		JoinPointAspectTarget joinPointAspectTarget = (JoinPointAspectTarget) ac.getBean("joinPointAspectTarget");
		//3.尝试调用被代理类的相关方法
		List<Integer> xx = joinPointAspectTarget.target("xx", new Date());
		System.out.println(xx);
	}
}
