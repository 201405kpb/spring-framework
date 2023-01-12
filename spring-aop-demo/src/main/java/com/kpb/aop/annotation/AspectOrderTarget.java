package com.kpb.aop.annotation;

import org.springframework.stereotype.Component;

@Component
public class AspectOrderTarget {

	public void target() {
		System.out.println("target");
	}
}
