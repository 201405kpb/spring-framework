/*
 * Copyright 2002-2023 the original author or authors.
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

import org.springframework.cache.interceptor.CacheEvictOperation;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.CachePutOperation;
import org.springframework.cache.interceptor.CacheableOperation;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;

/**
 * Strategy implementation for parsing Spring's {@link Caching}, {@link Cacheable},
 * {@link CacheEvict}, and {@link CachePut} annotations.
 *
 * 用于解析Spring的Caching、Cacheable、CacheEvict和CachePut注释的策略实现。
 *
 * @author Costin Leau
 * @author Juergen Hoeller
 * @author Chris Beams
 * @author Phillip Webb
 * @author Stephane Nicoll
 * @author Sam Brannen
 * @since 3.1
 */
@SuppressWarnings("serial")
public class SpringCacheAnnotationParser implements CacheAnnotationParser, Serializable {

	// Spring 对应的 CacheOperation 对应注解
	private static final Set<Class<? extends Annotation>> CACHE_OPERATION_ANNOTATIONS =
			Set.of(Cacheable.class, CacheEvict.class, CachePut.class, Caching.class);


	// 指定类上、类的方法、类的属性上是否包含指定注解
	@Override
	public boolean isCandidateClass(Class<?> targetClass) {
		return AnnotationUtils.isCandidateClass(targetClass, CACHE_OPERATION_ANNOTATIONS);
	}

	@Override
	@Nullable
	public Collection<CacheOperation> parseCacheAnnotations(Class<?> type) {
		// 基于 @CacheConfig 注解生成的默认配置
		DefaultCacheConfig defaultConfig = new DefaultCacheConfig(type);
		return parseCacheAnnotations(defaultConfig, type);
	}

	@Override
	@Nullable
	public Collection<CacheOperation> parseCacheAnnotations(Method method) {
		// 基于 @CacheConfig 注解生成的默认配置
		DefaultCacheConfig defaultConfig = new DefaultCacheConfig(method.getDeclaringClass());
		return parseCacheAnnotations(defaultConfig, method);
	}

	@Nullable
	private Collection<CacheOperation> parseCacheAnnotations(DefaultCacheConfig cachingConfig, AnnotatedElement ae) {
		/**
		 * 先忽略层级，如果解析的 CacheOperation 多余 1 个 则在本层级再解析一次
		 * （应该是为了防止接口和实现类都声明注解导致重复）
		 */
		Collection<CacheOperation> ops = parseCacheAnnotations(cachingConfig, ae, false);
		if (ops != null && ops.size() > 1) {
			// More than one operation found -> local declarations override interface-declared ones...
			Collection<CacheOperation> localOps = parseCacheAnnotations(cachingConfig, ae, true);
			if (localOps != null) {
				return localOps;
			}
		}
		return ops;
	}

	@Nullable
	private Collection<CacheOperation> parseCacheAnnotations(
			DefaultCacheConfig cachingConfig, AnnotatedElement ae, boolean localOnly) {

		Collection<? extends Annotation> annotations = (localOnly ?
				// 获取本层级元素上所有相关注解集合
				AnnotatedElementUtils.getAllMergedAnnotations(ae, CACHE_OPERATION_ANNOTATIONS) :
				// 获取包括父级元素上所有相关注解集合
				AnnotatedElementUtils.findAllMergedAnnotations(ae, CACHE_OPERATION_ANNOTATIONS));
		if (annotations.isEmpty()) {
			return null;
		}
		/**
		 * 把所有注解元素封装成对应的 CacheOperation
		 * @Cacheable -> CacheableOperation
		 * @CacheEvict -> CacheEvictOperation
		 * @CachePut -> CachePutOperation
		 * @Caching -> 分别解析成上述 CacheOperation
		 */
		Collection<CacheOperation> ops = new ArrayList<>(1);
		annotations.stream().filter(Cacheable.class::isInstance).map(Cacheable.class::cast).forEach(
				cacheable -> ops.add(parseCacheableAnnotation(ae, cachingConfig, cacheable)));
		annotations.stream().filter(CacheEvict.class::isInstance).map(CacheEvict.class::cast).forEach(
				cacheEvict -> ops.add(parseEvictAnnotation(ae, cachingConfig, cacheEvict)));
		annotations.stream().filter(CachePut.class::isInstance).map(CachePut.class::cast).forEach(
				cachePut -> ops.add(parsePutAnnotation(ae, cachingConfig, cachePut)));
		annotations.stream().filter(Caching.class::isInstance).map(Caching.class::cast).forEach(
				caching -> parseCachingAnnotation(ae, cachingConfig, caching, ops));
		return ops;
	}

	/**
	 *  解析 Cacheable 注解，并返回 CacheableOperation 实例
	 */
	private CacheableOperation parseCacheableAnnotation(
			AnnotatedElement ae, DefaultCacheConfig defaultConfig, Cacheable cacheable) {

		CacheableOperation.Builder builder = new CacheableOperation.Builder();

		builder.setName(ae.toString());
		builder.setCacheNames(cacheable.cacheNames());
		builder.setCondition(cacheable.condition());
		builder.setUnless(cacheable.unless());
		builder.setKey(cacheable.key());
		builder.setKeyGenerator(cacheable.keyGenerator());
		builder.setCacheManager(cacheable.cacheManager());
		builder.setCacheResolver(cacheable.cacheResolver());
		builder.setSync(cacheable.sync());

		defaultConfig.applyDefault(builder);
		CacheableOperation op = builder.build();
		validateCacheOperation(ae, op);

		return op;
	}

	/**
	 *  解析 CacheEvict 注解，并返回 CacheableOperation 实例
	 */
	private CacheEvictOperation parseEvictAnnotation(
			AnnotatedElement ae, DefaultCacheConfig defaultConfig, CacheEvict cacheEvict) {

		CacheEvictOperation.Builder builder = new CacheEvictOperation.Builder();

		builder.setName(ae.toString());
		builder.setCacheNames(cacheEvict.cacheNames());
		builder.setCondition(cacheEvict.condition());
		builder.setKey(cacheEvict.key());
		builder.setKeyGenerator(cacheEvict.keyGenerator());
		builder.setCacheManager(cacheEvict.cacheManager());
		builder.setCacheResolver(cacheEvict.cacheResolver());
		builder.setCacheWide(cacheEvict.allEntries());
		builder.setBeforeInvocation(cacheEvict.beforeInvocation());

		defaultConfig.applyDefault(builder);
		CacheEvictOperation op = builder.build();
		validateCacheOperation(ae, op);

		return op;
	}

	/**
	 *  解析 CachePut 注解，并返回 CacheableOperation 实例
	 */
	private CacheOperation parsePutAnnotation(
			AnnotatedElement ae, DefaultCacheConfig defaultConfig, CachePut cachePut) {

		CachePutOperation.Builder builder = new CachePutOperation.Builder();

		builder.setName(ae.toString());
		builder.setCacheNames(cachePut.cacheNames());
		builder.setCondition(cachePut.condition());
		builder.setUnless(cachePut.unless());
		builder.setKey(cachePut.key());
		builder.setKeyGenerator(cachePut.keyGenerator());
		builder.setCacheManager(cachePut.cacheManager());
		builder.setCacheResolver(cachePut.cacheResolver());

		defaultConfig.applyDefault(builder);
		CachePutOperation op = builder.build();
		validateCacheOperation(ae, op);

		return op;
	}

	/**
	 *  解析 Caching 注解，并返回 CacheableOperation 实例
	 */
	private void parseCachingAnnotation(
			AnnotatedElement ae, DefaultCacheConfig defaultConfig, Caching caching, Collection<CacheOperation> ops) {

		Cacheable[] cacheables = caching.cacheable();
		for (Cacheable cacheable : cacheables) {
			ops.add(parseCacheableAnnotation(ae, defaultConfig, cacheable));
		}
		CacheEvict[] cacheEvicts = caching.evict();
		for (CacheEvict cacheEvict : cacheEvicts) {
			ops.add(parseEvictAnnotation(ae, defaultConfig, cacheEvict));
		}
		CachePut[] cachePuts = caching.put();
		for (CachePut cachePut : cachePuts) {
			ops.add(parsePutAnnotation(ae, defaultConfig, cachePut));
		}
	}


	/**
	 * Validates the specified {@link CacheOperation}.
	 * 验证指定的CacheOperation。
	 * <p>Throws an {@link IllegalStateException} if the state of the operation is
	 * invalid. As there might be multiple sources for default values, this ensures
	 * that the operation is in a proper state before being returned.
	 * @param ae the annotated element of the cache operation 缓存操作的带注释元素
	 * @param operation the {@link CacheOperation} to validate
	 */
	private void validateCacheOperation(AnnotatedElement ae, CacheOperation operation) {
		// 不能同时指定 key 和 keyGenerator
		if (StringUtils.hasText(operation.getKey()) && StringUtils.hasText(operation.getKeyGenerator())) {
			throw new IllegalStateException("Invalid cache annotation configuration on '" +
					ae.toString() + "'. Both 'key' and 'keyGenerator' attributes have been set. " +
					"These attributes are mutually exclusive: either set the SpEL expression used to" +
					"compute the key at runtime or set the name of the KeyGenerator bean to use.");
		}
		// 不能同时指定 cacheManager 和 cacheResolver
		if (StringUtils.hasText(operation.getCacheManager()) && StringUtils.hasText(operation.getCacheResolver())) {
			throw new IllegalStateException("Invalid cache annotation configuration on '" +
					ae.toString() + "'. Both 'cacheManager' and 'cacheResolver' attributes have been set. " +
					"These attributes are mutually exclusive: the cache manager is used to configure a" +
					"default cache resolver if none is set. If a cache resolver is set, the cache manager" +
					"won't be used.");
		}
	}

	@Override
	public boolean equals(@Nullable Object other) {
		return (other instanceof SpringCacheAnnotationParser);
	}

	@Override
	public int hashCode() {
		return SpringCacheAnnotationParser.class.hashCode();
	}


	/**
	 * Provides default settings for a given set of cache operations.
	 * 为给定的一组缓存操作提供默认设置。
	 */
	private static class DefaultCacheConfig {

		private final Class<?> target;

		@Nullable
		private String[] cacheNames;

		@Nullable
		private String keyGenerator;

		@Nullable
		private String cacheManager;

		@Nullable
		private String cacheResolver;

		private boolean initialized = false;

		public DefaultCacheConfig(Class<?> target) {
			this.target = target;
		}

		/**
		 * Apply the defaults to the specified {@link CacheOperation.Builder}.
		 * 将默认值应用于指定的CacheOperation.Builder。
		 * @param builder the operation builder to update
		 *                -- 要更新的操作生成器
		 */
		public void applyDefault(CacheOperation.Builder builder) {
			if (!this.initialized) {
				CacheConfig annotation = AnnotatedElementUtils.findMergedAnnotation(this.target, CacheConfig.class);
				if (annotation != null) {
					this.cacheNames = annotation.cacheNames();
					this.keyGenerator = annotation.keyGenerator();
					this.cacheManager = annotation.cacheManager();
					this.cacheResolver = annotation.cacheResolver();
				}
				this.initialized = true;
			}
			// 如果注解在方法上未设置这些字段，用注解在类上的字段去填充
			if (builder.getCacheNames().isEmpty() && this.cacheNames != null) {
				builder.setCacheNames(this.cacheNames);
			}
			if (!StringUtils.hasText(builder.getKey()) && !StringUtils.hasText(builder.getKeyGenerator()) &&
					StringUtils.hasText(this.keyGenerator)) {
				builder.setKeyGenerator(this.keyGenerator);
			}

			if (StringUtils.hasText(builder.getCacheManager()) || StringUtils.hasText(builder.getCacheResolver())) {
				// One of these is set so we should not inherit anything
				// cacheManager和cacheResolver 是互斥字段，不可能同时有；一个字段设置了，那么这两个字段都不需要覆盖
			}
			else if (StringUtils.hasText(this.cacheResolver)) {
				builder.setCacheResolver(this.cacheResolver);
			}
			else if (StringUtils.hasText(this.cacheManager)) {
				builder.setCacheManager(this.cacheManager);
			}
		}
	}

}
