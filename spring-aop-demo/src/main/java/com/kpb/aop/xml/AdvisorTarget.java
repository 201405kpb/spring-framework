package com.kpb.aop.xml;

public class AdvisorTarget {
	public void target() {
		//抛出一个异常
		//int i=1/0;
		System.out.println("---业务---");
	}
}
