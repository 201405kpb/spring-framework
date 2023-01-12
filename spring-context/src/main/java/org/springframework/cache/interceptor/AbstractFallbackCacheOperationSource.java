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

package org.springframework.cache.interceptor;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.MethodClassKey;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Abstract implementation of {@link CacheOperation} that caches attributes
 * for methods and implements a fallback policy: 1. specific target method;
 * 2. target class; 3. declaring method; 4. declaring class/interface.
 * <p>
 * CacheOperation的抽象实现，用于缓存方法的属性并实现回退策略：1。特定目标方法；2.目标类别；3申报方法；4.声明类接口。
 *
 * <p>Defaults to using the target class's caching attribute if none is
 * associated with the target method. Any caching attribute associated with
 * the target method completely overrides a class caching attribute.
 * If none found on the target class, the interface that the invoked method
 * has been called through (in case of a JDK proxy) will be checked.
 *
 * <p>如果没有与目标方法关联，则默认使用目标类的缓存属性。
 * 与目标方法关联的任何缓存属性都会完全覆盖类缓存属性。
 * 如果在目标类上找不到任何对象，则将检查已调用方法的接口（如果是JDK代理）。
 *
 * <p>This implementation caches attributes by method after they are first
 * used. If it is ever desirable to allow dynamic changing of cacheable
 * attributes (which is very unlikely), caching could be made configurable.
 *
 * <p>此实现在属性首次使用后按方法缓存它们。如果希望允许动态更改可缓存属性（这是非常不可能的），
 * 那么可以将缓存设置为可配置的。
 *
 * @author Costin Leau
 * @author Juergen Hoeller
 * @since 3.1
 */
public abstract class AbstractFallbackCacheOperationSource implements CacheOperationSource {

	/**
	 * Canonical value held in cache to indicate no caching attribute was
	 * found for this method and we don't need to look again.
	 * 缓存中保存的标准值表示未找到此方法的缓存属性，我们不需要再次查找。
	 */
	private static final Collection<CacheOperation> NULL_CACHING_ATTRIBUTE = Collections.emptyList();


	/**
	 * Logger available to subclasses.
	 * <p>As this base class is not marked Serializable, the logger will be recreated
	 * after serialization - provided that the concrete subclass is Serializable.
	 */
	protected final Log logger = LogFactory.getLog(getClass());

	/**
	 * Cache of CacheOperations, keyed by method on a specific target class.
	 * CacheOperations的缓存，由特定目标类上的方法键控。
	 * <p>As this base class is not marked Serializable, the cache will be recreated
	 * after serialization - provided that the concrete subclass is Serializable.
	 * <p>由于此基类未标记为Serializable，因此在序列化后将重新创建缓存-前提是具体子类是Serializable。
	 */
	private final Map<Object, Collection<CacheOperation>> attributeCache = new ConcurrentHashMap<>(1024);


	/**
	 * Determine the caching attribute for this method invocation.
	 * 确定此方法调用的缓存属性。
	 * <p>Defaults to the class's caching attribute if no method attribute is found.
	 * <p>如果找不到方法属性，则默认为类的缓存属性。
	 *
	 * @param method      the method for the current invocation (never {@code null})
	 *                    -- 当前调用的方法（不可能为空）
	 * @param targetClass the target class for this invocation (may be {@code null})
	 *                    -- 调用的目标类（可以为空）
	 * @return {@link CacheOperation} for this method, or {@code null} if the method
	 * is not cacheable
	 * 如果该方法不可缓存 则返回null,否则返回 此方法对应的 CacheOperation集合
	 */
	@Override
	@Nullable
	public Collection<CacheOperation> getCacheOperations(Method method, @Nullable Class<?> targetClass) {
		// Object的方法没有缓存
		if (method.getDeclaringClass() == Object.class) {
			return null;
		}
		// 根据类和方法生成缓存KEY
		Object cacheKey = getCacheKey(method, targetClass);
		Collection<CacheOperation> cached = this.attributeCache.get(cacheKey);
		// 根据KEY，查缓存MAP，如果已经解析过，则直接返回
		if (cached != null) {
			return (cached != NULL_CACHING_ATTRIBUTE ? cached : null);
		} else {
			// 缓存MAP中不存在，则需要解析指定类的指定方法上的SpringCache动作
			Collection<CacheOperation> cacheOps = computeCacheOperations(method, targetClass);
			// 将解析结果存入缓存MAP，以便下次直接返回
			if (cacheOps != null) {
				if (logger.isTraceEnabled()) {
					logger.trace("Adding cacheable method '" + method.getName() + "' with attribute: " + cacheOps);
				}
				this.attributeCache.put(cacheKey, cacheOps);
			} else {
				this.attributeCache.put(cacheKey, NULL_CACHING_ATTRIBUTE);
			}
			return cacheOps;
		}
	}

	/**
	 * Determine a cache key for the given method and target class.
	 * 确定给定方法和目标类的缓存键。
	 * <p>Must not produce same key for overloaded methods.
	 * Must produce same key for different instances of the same method.
	 * <p>不能为重载方法生成相同的键。必须为同一方法的不同实例生成相同的密钥。
	 *
	 * @param method      the method (never {@code null})
	 * @param targetClass the target class (may be {@code null})
	 * @return the cache key (never {@code null})
	 */
	protected Object getCacheKey(Method method, @Nullable Class<?> targetClass) {
		return new MethodClassKey(method, targetClass);
	}

	/**
	 * 解析SpringCache de 方法
	 */
	@Nullable
	private Collection<CacheOperation> computeCacheOperations(Method method, @Nullable Class<?> targetClass) {
		// Don't allow non-public methods, as configured.
		// 如果方法不是public并且只允许public有缓存动作
		if (allowPublicMethodsOnly() && !Modifier.isPublic(method.getModifiers())) {
			return null;
		}

		// The method may be on an interface, but we need attributes from the target class.
		// If the target class is null, the method will be unchanged.
		// 找到method的真正实现
		Method specificMethod = AopUtils.getMostSpecificMethod(method, targetClass);

		// First try is the method in the target class.
		//第一次解析，根据method
		Collection<CacheOperation> opDef = findCacheOperations(specificMethod);
		if (opDef != null) {
			return opDef;
		}

		// Second try is the caching operation on the target class.
		//第二次解析，根据target class
		opDef = findCacheOperations(specificMethod.getDeclaringClass());
		if (opDef != null && ClassUtils.isUserLevelMethod(method)) {
			return opDef;
		}
		// 如果被解析的method不是原始入参，那么根据原始入参method再走一遍解析过程
		if (specificMethod != method) {
			// Fallback is to look at the original method.
			opDef = findCacheOperations(method);
			if (opDef != null) {
				return opDef;
			}
			// Last fallback is the class of the original method.
			opDef = findCacheOperations(method.getDeclaringClass());
			if (opDef != null && ClassUtils.isUserLevelMethod(method)) {
				return opDef;
			}
		}

		return null;
	}


	/**
	 * Subclasses need to implement this to return the caching attribute for the
	 * given class, if any.
	 * 子类需要实现这一点，以返回给定类的缓存属性（如果有的话）。
	 *
	 * @param clazz the class to retrieve the attribute for
	 * @return all caching attribute associated with this class, or {@code null} if none
	 */
	@Nullable
	protected abstract Collection<CacheOperation> findCacheOperations(Class<?> clazz);

	/**
	 * Subclasses need to implement this to return the caching attribute for the
	 * given method, if any.
	 * 子类需要实现这一点，以返回给定方法的缓存属性（如果有的话）。
	 *
	 * @param method the method to retrieve the attribute for
	 * @return all caching attribute associated with this method, or {@code null} if none
	 */
	@Nullable
	protected abstract Collection<CacheOperation> findCacheOperations(Method method);

	/**
	 * Should only public methods be allowed to have caching semantics?
	 * <p>The default implementation returns {@code false}.
	 * 是否只支持public方法拥有SpringCache动作
	 */
	protected boolean allowPublicMethodsOnly() {
		return false;
	}

}
