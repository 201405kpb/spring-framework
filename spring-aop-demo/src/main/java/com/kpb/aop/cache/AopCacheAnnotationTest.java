package com.kpb.aop.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public class AopCacheAnnotationTest {
	static Logger logger = LoggerFactory.getLogger(AopCacheAnnotationTest.class);

	public static void main(String[] args) {
		if (logger.isTraceEnabled()){
			logger.info("测试信息");
		}
		//1 获取容器
		AnnotationConfigApplicationContext ac = new AnnotationConfigApplicationContext(CacheConfig.class);
		//2.获取对象
		CacheService cacheService = ac.getBean("cacheService", CacheService.class);
		String strValue = cacheService.find2(10);
		System.out.println(strValue);
		String strValue2 = cacheService.find3(10);
		System.out.println(strValue2);

	}
}
