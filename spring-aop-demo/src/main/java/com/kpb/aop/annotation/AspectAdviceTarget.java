package com.kpb.aop.annotation;

import org.springframework.stereotype.Component;

@Component
public class AspectAdviceTarget {

	public int target() {
		System.out.println("target");
		//抛出一个异常
		//int i = 1 / 0;
		return 33;
	}
}
