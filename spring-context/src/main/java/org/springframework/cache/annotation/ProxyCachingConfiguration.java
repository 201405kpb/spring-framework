/*
 * Copyright 2002-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cache.annotation;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.cache.config.CacheManagementConfigUtils;
import org.springframework.cache.interceptor.BeanFactoryCacheOperationSourceAdvisor;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.cache.interceptor.CacheOperationSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;

/**
 * {@code @Configuration} class that registers the Spring infrastructure beans necessary
 * to enable proxy-based annotation-driven cache management.
 *
 * 注册启用基于代理的注释驱动缓存管理所需的Spring基础结构bean
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @since 3.1
 * @see EnableCaching
 * @see CachingConfigurationSelector
 */
@Configuration(proxyBeanMethods = false)
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class ProxyCachingConfiguration extends AbstractCachingConfiguration {

	@Bean(name = CacheManagementConfigUtils.CACHE_ADVISOR_BEAN_NAME)
	@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
	// 基于cache操作的Advisor，在spring切面(pointcut)判断是否拦截(是否有缓存注解)，并调用CacheInterceptor
	public BeanFactoryCacheOperationSourceAdvisor cacheAdvisor(
			CacheOperationSource cacheOperationSource, CacheInterceptor cacheInterceptor) {

		BeanFactoryCacheOperationSourceAdvisor advisor = new BeanFactoryCacheOperationSourceAdvisor();
		// 会基于 cacheOperationSource 创建对应的 Pointcut
		advisor.setCacheOperationSource(cacheOperationSource);
		// 指定了具体的 Advice
		advisor.setAdvice(cacheInterceptor);
		if (this.enableCaching != null) {
			advisor.setOrder(this.enableCaching.<Integer>getNumber("order"));
		}
		return advisor;
	}

	@Bean
	@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
	// 缓存注解解析，把Cacheable、CachePut和CacheEvict注解解析为相应的缓存操作(CacheOperation)
	public CacheOperationSource cacheOperationSource() {
		// Accept protected @Cacheable etc methods on CGLIB proxies, as of 6.0.
		// 方法不是 public 也行
		return new AnnotationCacheOperationSource(false);
	}

	@Bean
	@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
	// 实现MethodInterceptor接口，aop的方法拦截器，缓存代理类会调用此方法进行相应的缓存处理
	public CacheInterceptor cacheInterceptor(CacheOperationSource cacheOperationSource) {
		CacheInterceptor interceptor = new CacheInterceptor();
		/**
		 * 这些属性在父类中可以基于自定义的 CachingConfigurer 进行配置
		 */
		interceptor.configure(this.errorHandler, this.keyGenerator, this.cacheResolver, this.cacheManager);
		interceptor.setCacheOperationSource(cacheOperationSource);
		return interceptor;
	}

}
