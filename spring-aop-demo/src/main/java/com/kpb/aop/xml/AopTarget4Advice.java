package com.kpb.aop.xml;

public class AopTarget4Advice {
	public int target() {
		System.out.println("---test 4 advice target---");
		//引发一个异常，可能被异常通知捕获，并尝试传递给通知方法的参数
		//int j=1/0;

		//返回值，可以被后置通知捕获，并传递给后置通知的方法参数
		return 3;
	}
}
