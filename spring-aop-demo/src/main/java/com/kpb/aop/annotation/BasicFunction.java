package com.kpb.aop.annotation;

import org.springframework.stereotype.Component;

@Component
public class BasicFunction {
	public void get(){
		System.out.println("get");
	}

	public void update(){
		System.out.println("update");
	}
}