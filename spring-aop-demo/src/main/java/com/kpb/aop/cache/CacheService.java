package com.kpb.aop.cache;


import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class CacheService {

	@Cacheable(cacheNames = "default")
	public String find(Integer id) {
		System.out.println("执行方法");
		if (id == 1) {
			return "Spring源码深度解析（第2版）";
		}
		return null;
	}

}
