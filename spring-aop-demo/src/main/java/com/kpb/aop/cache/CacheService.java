package com.kpb.aop.cache;


import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;

@Service
public class CacheService {


	@Cacheable(cacheNames = "default")
	public String find(Integer id) {
		System.out.println("执行方法");
		if (id != null) {
			return "Spring源码深度解析（第2版）";
		}
		return null;
	}

	@Caching(cacheable = {
			@Cacheable(cacheNames = "default", key = "'default'.concat(T(String).valueOf(#id))"),
			@Cacheable(cacheNames = "default2", key = "'default2'.concat(T(String).valueOf(#id))")
	})
	public String find2(Integer id) {
		System.out.println("执行方法find2");
		if (id !=null) {
			return "Spring源码深度解析（第2版）";
		}
		return null;
	}


	@Cacheable(cacheNames = "default2", key = "'default2'.concat(T(String).valueOf(#id))")
	public String find3(Integer id) {
		System.out.println("执行方法find3");
		if (id !=null) {
			return "Spring源码深度解析（第2版）";
		}
		return null;
	}

}
