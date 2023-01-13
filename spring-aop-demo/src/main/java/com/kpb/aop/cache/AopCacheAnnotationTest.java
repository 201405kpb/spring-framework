package com.kpb.aop.cache;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public class AopCacheAnnotationTest {
	public static void main(String[] args) {
		//1 获取容器
		AnnotationConfigApplicationContext ac = new AnnotationConfigApplicationContext(CacheConfig.class);
		//2.获取对象
		CacheService cacheService = ac.getBean("cacheService",CacheService.class);
		String strValue = cacheService.find(1);
		System.out.println(strValue);
		String strValue2 = cacheService.find(1);
		System.out.println(strValue2);

	}
}
