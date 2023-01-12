package com.kpb.spring;

import com.kpb.spring.service.CacheService;
import com.kpb.spring.tx.Book;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

public class SpringCacheDemo {

	public static void main(String[] args) {
		ApplicationContext applicationContext = new ClassPathXmlApplicationContext("classpath:spring-core-cache.xml");
		CacheService cacheService = (CacheService) applicationContext.getBean("cacheService");
		Book book = cacheService.find(1);
		System.out.println(book);

		Book book2 = cacheService.find(1);
		System.out.println(book2);
	}
}


