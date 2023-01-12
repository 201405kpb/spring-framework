/*
 * Copyright 2002-2019 the original author or authors.
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

import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.lang.Nullable;

import java.lang.reflect.Method;
import java.util.Collection;

/**
 * Strategy interface for parsing known caching annotation types.
 * {@link AnnotationCacheOperationSource} delegates to such parsers
 * for supporting specific annotation types such as Spring's own
 * {@link Cacheable}, {@link CachePut} and{@link CacheEvict}.
 * 用于解析已知缓存注释类型的策略接口。AnnotationCacheOperationSource委托给这样的解析器，以支持特定的注释类型，
 * 例如Spring自己的Cacheable、CachePut和CacheEvict。
 *
 * @author Costin Leau
 * @author Stephane Nicoll
 * @author Juergen Hoeller
 * @see AnnotationCacheOperationSource
 * @see SpringCacheAnnotationParser
 * @since 3.1
 */
public interface CacheAnnotationParser {

	/**
	 * Determine whether the given class is a candidate for cache operations
	 * in the annotation format of this {@code CacheAnnotationParser}.
	 * <p>确定给定类是否是此CacheAnnotationParser的注释格式的缓存操作的候选类</p>
	 * <p>If this method returns {@code false}, the methods on the given class
	 * will not get traversed for {@code #parseCacheAnnotations} introspection.
	 * Returning {@code false} is therefore an optimization for non-affected
	 * classes, whereas {@code true} simply means that the class needs to get
	 * fully introspected for each method on the given class individually.
	 * <p>如果此方法返回false，则不会遍历给定类上的方法进行CacheAnnotations内省。
	 * 因此，返回false是对未受影响的类的优化，而true仅仅意味着该类需要针对给定类上的每个方法单独进行完全内省。
	 *
	 * @param targetClass the class to introspect
	 * @return {@code false} if the class is known to have no cache operation
	 * annotations at class or method level; {@code true} otherwise. The default
	 * implementation returns {@code true}, leading to regular introspection.
	 * @since 5.2
	 */
	default boolean isCandidateClass(Class<?> targetClass) {
		return true;
	}

	/**
	 * Parse the cache definition for the given class,
	 * based on an annotation type understood by this parser.
	 * <p>根据此解析器理解的注释类型，分析给定类的缓存定义。
	 * <p>This essentially parses a known cache annotation into Spring's metadata
	 * attribute class. Returns {@code null} if the class is not cacheable.
	 * <p>这实际上是将已知的缓存注释解析为Spring的元数据属性类。如果类不可缓存，则返回 null。
	 * @param type the annotated class
	 * @return the configured caching operation, or {@code null} if none found
	 * @see AnnotationCacheOperationSource#findCacheOperations(Class)
	 */
	@Nullable
	Collection<CacheOperation> parseCacheAnnotations(Class<?> type);

	/**
	 * Parse the cache definition for the given method,
	 * based on an annotation type understood by this parser.
	 * <p>根据此解析器理解的注释类型，分析给定方法的缓存定义。
	 * <p>This essentially parses a known cache annotation into Spring's metadata
	 * attribute class. Returns {@code null} if the method is not cacheable.
	 * <p>这实际上是将已知的缓存注释解析为Spring的元数据属性类。如果方法不可缓存，则返回null
	 *
	 * @param method the annotated method
	 * @return the configured caching operation, or {@code null} if none found
	 * @see AnnotationCacheOperationSource#findCacheOperations(Method)
	 */
	@Nullable
	Collection<CacheOperation> parseCacheAnnotations(Method method);

}
