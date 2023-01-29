/*
 * Copyright 2002-2020 the original author or authors.
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

package org.springframework.cache.support;

import org.springframework.cache.Cache;
import org.springframework.lang.Nullable;

/**
 * Common base class for {@link Cache} implementations that need to adapt
 * {@code null} values (and potentially other such special values) before
 * passing them on to the underlying store.
 *
 * <p>Transparently replaces given {@code null} user values with an internal
 * {@link NullValue#INSTANCE}, if configured to support {@code null} values
 * (as indicated by {@link #isAllowNullValues()}).
 *
 * 保存的value为null时，如果allowNullValues=true，则保存NullValue，如果allowNullValues=false，则抛出异常。默认为true；
 * 读取的value为NullValue时，返回null，不受allowNullValues影响；
 * lookup方法直接从底层存储获取value，不会进行任何处理和转换，其它方法会对value进行一些处理；
 *
 * @author Juergen Hoeller
 * @since 4.2.2
 */
public abstract class AbstractValueAdaptingCache implements Cache {

	// 是否允许 null 值
	private final boolean allowNullValues;


	/**
	 * Create an {@code AbstractValueAdaptingCache} with the given setting.
	 * @param allowNullValues whether to allow for {@code null} values
	 */
	protected AbstractValueAdaptingCache(boolean allowNullValues) {
		this.allowNullValues = allowNullValues;
	}


	/**
	 * Return whether {@code null} values are allowed in this cache.
	 */
	public final boolean isAllowNullValues() {
		return this.allowNullValues;
	}

	@Override
	@Nullable
	public ValueWrapper get(Object key) {
		// get 操作依赖 toValueWrapper
		return toValueWrapper(lookup(key));
	}

	@Override
	@SuppressWarnings("unchecked")
	@Nullable
	public <T> T get(Object key, @Nullable Class<T> type) {
		// 查询到的缓存值 fromStoreValue 转换
		Object value = fromStoreValue(lookup(key));
		// 转换后非 null 值无法类型转换则抛出异常
		if (value != null && type != null && !type.isInstance(value)) {
			throw new IllegalStateException(
					"Cached value is not of required type [" + type.getName() + "]: " + value);
		}
		return (T) value;
	}

	/**
	 * Perform an actual lookup in the underlying store.
	 * 从缓存中获取 key 对应的 v，子类实现
	 * @param key the key whose associated value is to be returned
	 * @return the raw store value for the key, or {@code null} if none
	 */
	@Nullable
	protected abstract Object lookup(Object key);


	/**
	 * Convert the given value from the internal store to a user value
	 * returned from the get method (adapting {@code null}).
	 * 对于从缓存中获取的值，允许为空且值为 NullValue 时，处理为 null
	 * @param storeValue the store value
	 * @return the value to return to the user
	 */
	@Nullable
	protected Object fromStoreValue(@Nullable Object storeValue) {
		if (this.allowNullValues && storeValue == NullValue.INSTANCE) {
			return null;
		}
		return storeValue;
	}

	/**
	 * Convert the given user value, as passed into the put method,
	 * to a value in the internal store (adapting {@code null}).
	 * 对于要插入缓存的 null 值，在允许 null 值的情况下处理为 NullValue，否则抛出异常 IllegalArgumentException
	 * @param userValue the given user value
	 * @return the value to store
	 */
	protected Object toStoreValue(@Nullable Object userValue) {
		if (userValue == null) {
			if (this.allowNullValues) {
				return NullValue.INSTANCE;
			}
			throw new IllegalArgumentException(
					"Cache '" + getName() + "' is configured to not allow null values but null was provided");
		}
		return userValue;
	}

	/**
	 * Wrap the given store value with a {@link SimpleValueWrapper}, also going
	 * through {@link #fromStoreValue} conversion. Useful for {@link #get(Object)}
	 * and {@link #putIfAbsent(Object, Object)} implementations.
	 * get 操作基于此完成，查询到缓存值非 null 则 fromStoreValue 转换后包装成 SimpleValueWrapper 返回
	 * @param storeValue the original value
	 * @return the wrapped value
	 */
	@Nullable
	protected Cache.ValueWrapper toValueWrapper(@Nullable Object storeValue) {
		return (storeValue != null ? new SimpleValueWrapper(fromStoreValue(storeValue)) : null);
	}

}
