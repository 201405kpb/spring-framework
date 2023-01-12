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

import org.springframework.aop.ClassFilter;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.cache.CacheManager;
import org.springframework.lang.Nullable;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;

import java.io.Serializable;
import java.lang.reflect.Method;

/**
 * A Pointcut that matches if the underlying {@link CacheOperationSource}
 * has an attribute for a given method.
 *
 * StaticMethodMatcherPointcut 是一个 MethodMatcher 又是一个 Pointcut，
 * 因此可以基于 Pointcut 拓展 ClassFilter 的能力
 *
 * @author Costin Leau
 * @author Juergen Hoeller
 * @since 3.1
 */
@SuppressWarnings("serial")
abstract class CacheOperationSourcePointcut extends StaticMethodMatcherPointcut implements Serializable {

	// 指定 ClassFilter
	protected CacheOperationSourcePointcut() {
		setClassFilter(new CacheOperationSourceClassFilter());
	}


	/**
	 * 通过Class+Method查找是否有CacheOperation操作，如果有的话，说明这个方法可能涉及到缓存的操作
	 */
	@Override
	public boolean matches(Method method, Class<?> targetClass) {
		CacheOperationSource cas = getCacheOperationSource();
		return (cas != null && !CollectionUtils.isEmpty(cas.getCacheOperations(method, targetClass)));
	}

	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof CacheOperationSourcePointcut otherPc)) {
			return false;
		}
		return ObjectUtils.nullSafeEquals(getCacheOperationSource(), otherPc.getCacheOperationSource());
	}

	@Override
	public int hashCode() {
		return CacheOperationSourcePointcut.class.hashCode();
	}

	@Override
	public String toString() {
		return getClass().getName() + ": " + getCacheOperationSource();
	}


	/**
	 * Obtain the underlying {@link CacheOperationSource} (may be {@code null}).
	 * To be implemented by subclasses.
	 * 实现类指定具体的 CacheOperationSource
	 */
	@Nullable
	protected abstract CacheOperationSource getCacheOperationSource();


	/**
	 * {@link ClassFilter} that delegates to {@link CacheOperationSource#isCandidateClass}
	 * for filtering classes whose methods are not worth searching to begin with.
	 * 内部类维护对应的 ClassFilter
	 */
	private class CacheOperationSourceClassFilter implements ClassFilter {

		/**
		 * ClassFilter#match 委托 CacheOperationSource 实现
		 */
		@Override
		public boolean matches(Class<?> clazz) {
			// 不代理 CacheManager
			if (CacheManager.class.isAssignableFrom(clazz)) {
				return false;
			}
			// 基于 CacheOperationSource#isCandidateClass 过滤
			CacheOperationSource cas = getCacheOperationSource();
			return (cas == null || cas.isCandidateClass(clazz));
		}
	}

}
