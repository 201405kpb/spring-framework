package com.kpb.aop.annotation.first;

import org.springframework.stereotype.Component;

@Component
public class FirstAopAnnotationTarget {

	/**
	 * 该方法将被增强
	 */
	public void target() {
		System.out.println("target");
	}
}
