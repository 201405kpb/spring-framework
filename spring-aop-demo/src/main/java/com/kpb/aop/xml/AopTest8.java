package com.kpb.aop.xml;

import com.kpb.aop.xml.introduction.BasicFunction;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * @Author: kpb
 * @Description:  declare-parents 引介
 * @Date: 创建时间 2023/1/12
 */
public class AopTest8 {

	public static void main(String[] args) {
		//1.获取容器
		ApplicationContext ac = new ClassPathXmlApplicationContext("spring-config8.xml");
		//2.获取对象
		Object o = ac.getBean("basicFunction");
		System.out.println(o.getClass());
		System.out.println(o instanceof AddFunctionImpl);
		System.out.println(o instanceof AddFunction);
		System.out.println(o instanceof BasicFunction);
		System.out.println("------------");
		BasicFunction basicFunction=(BasicFunction) o;
		//3.尝试调用被代理类的相关方法
		basicFunction.get();
		basicFunction.update();
		//转换类型，调用新增的方法
		AddFunction addFunction=(AddFunction) basicFunction;
		addFunction.delete();
		addFunction.insert();
		System.out.println(AddFunction.str);
	}
}
