package com.kpb.aop.xml;

public class AopTargetAround {
	public int target(int x,int y) {
		System.out.println("---test around advice target---");
		//引发一个异常
		//int j=1/0;
		//返回值
		return x+y;
	}
}
