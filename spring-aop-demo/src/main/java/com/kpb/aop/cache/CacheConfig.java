package com.kpb.aop.cache;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@ComponentScan("com.kpb.aop.cache")
@EnableCaching
public class CacheConfig {

	@Bean(name = "cacheManager")
	public SimpleCacheManager BuildCacheManager(List<ConcurrentMapCache> list){
		SimpleCacheManager cacheManager = new SimpleCacheManager();
		cacheManager.setCaches(list);
		return cacheManager;
	}

	@Bean
	public ConcurrentMapCache DefaultCache(){
		return new ConcurrentMapCache("default");
	}

	@Bean
	public ConcurrentMapCache CacheBean(){
		return new ConcurrentMapCache("default2");
	}

}
