package com.kpb.aop.annotation;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public class AopAnnotationTest7 {
	public static void main(String[] args) {
		//1 获取容器
		AnnotationConfigApplicationContext ac = new AnnotationConfigApplicationContext(AppConfig.class);
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

	}
}
