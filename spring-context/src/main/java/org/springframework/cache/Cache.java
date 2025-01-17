/*
 * Copyright 2002-2022 the original author or authors.
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

package org.springframework.cache;

import org.springframework.lang.Nullable;

import java.util.concurrent.Callable;

/**
 * Interface that defines common cache operations.
 * 定义公共缓存操作的接口
 *
 * <b>Note:</b> Due to the generic use of caching, it is recommended that
 * implementations allow storage of {@code null} values (for example to
 * cache methods that return {@code null}).
 *
 * @author Costin Leau
 * @author Juergen Hoeller
 * @author Stephane Nicoll
 * @since 3.1
 */
public interface Cache {

	/**
	 * Return the cache name.
	 * 缓存名称
	 */
	String getName();

	/**
	 * Return the underlying native cache provider.
	 * 缓存真正负责缓存的对象
	 */
	Object getNativeCache();

	/**
	 * Return the value to which this cache maps the specified key.
	 * 获取 key 对应的 ValueWrapper
	 * <p>Returns {@code null} if the cache contains no mapping for this key;
	 * otherwise, the cached value (which may be {@code null} itself) will
	 * be returned in a {@link ValueWrapper}.
	 * 没有对应的 key 就返回 null，key 对应的 v 是 null 的话返回的是 null 对应的 ValueWrapper
	 * @param key the key whose associated value is to be returned
	 * @return the value to which this cache maps the specified key,
	 * contained within a {@link ValueWrapper} which may also hold
	 * a cached {@code null} value. A straight {@code null} being
	 * returned means that the cache contains no mapping for this key.
	 * @see #get(Object, Class)
	 * @see #get(Object, Callable)
	 */
	@Nullable
	ValueWrapper get(Object key);

	/**
	 * Return the value to which this cache maps the specified key,
	 * generically specifying a type that return value will be cast to.
	 * 返回 key 对应 type 类型的 v
	 * <p>Note: This variant of {@code get} does not allow for differentiating
	 * between a cached {@code null} value and no cache entry found at all.
	 * Use the standard {@link #get(Object)} variant for that purpose instead.
	 * @param key the key whose associated value is to be returned
	 * @param type the required type of the returned value (may be
	 * {@code null} to bypass a type check; in case of a {@code null}
	 * value found in the cache, the specified type is irrelevant)
	 * @return the value to which this cache maps the specified key
	 * (which may be {@code null} itself), or also {@code null} if
	 * the cache contains no mapping for this key
	 * @throws IllegalStateException if a cache entry has been found
	 * but failed to match the specified type
	 * @since 4.0
	 * @see #get(Object)
	 */
	@Nullable
	<T> T get(Object key, @Nullable Class<T> type);

	/**
	 * Return the value to which this cache maps the specified key, obtaining
	 * that value from {@code valueLoader} if necessary. This method provides
	 * a simple substitute for the conventional "if cached, return; otherwise
	 * create, cache and return" pattern.
	 * 如果有 key 对应的 v 就返回，如果没有就缓存 Callable::call 并返回
	 * <p>If possible, implementations should ensure that the loading operation
	 * is synchronized so that the specified {@code valueLoader} is only called
	 * once in case of concurrent access on the same key.
	 * <p>If the {@code valueLoader} throws an exception, it is wrapped in
	 * a {@link ValueRetrievalException}
	 * @param key the key whose associated value is to be returned
	 * @return the value to which this cache maps the specified key
	 * @throws ValueRetrievalException if the {@code valueLoader} throws an exception
	 * @since 4.3
	 * @see #get(Object)
	 */
	@Nullable
	<T> T get(Object key, Callable<T> valueLoader);

	/**
	 * Associate the specified value with the specified key in this cache.
	 * 缓存目标 kv（替换旧值），不保证实时性
	 * <p>If the cache previously contained a mapping for this key, the old
	 * value is replaced by the specified value.
	 * <p>Actual registration may be performed in an asynchronous or deferred
	 * fashion, with subsequent lookups possibly not seeing the entry yet.
	 * This may for example be the case with transactional cache decorators.
	 * Use {@link #putIfAbsent} for guaranteed immediate registration.
	 * @param key the key with which the specified value is to be associated
	 * @param value the value to be associated with the specified key
	 * @see #putIfAbsent(Object, Object)
	 */
	void put(Object key, @Nullable Object value);

	/**
	 * Atomically associate the specified value with the specified key in this cache
	 * if it is not set already.
	 * 立即插入缓存，默认基于先 get 不存在则 put 操作实现
	 * <p>This is equivalent to:
	 * <pre><code>
	 * ValueWrapper existingValue = cache.get(key);
	 * if (existingValue == null) {
	 *     cache.put(key, value);
	 * }
	 * return existingValue;
	 * </code></pre>
	 * except that the action is performed atomically. While all out-of-the-box
	 * {@link CacheManager} implementations are able to perform the put atomically,
	 * the operation may also be implemented in two steps, e.g. with a check for
	 * presence and a subsequent put, in a non-atomic way. Check the documentation
	 * of the native cache implementation that you are using for more details.
	 * <p>The default implementation delegates to {@link #get(Object)} and
	 * {@link #put(Object, Object)} along the lines of the code snippet above.
	 * @param key the key with which the specified value is to be associated
	 * @param value the value to be associated with the specified key
	 * @return the value to which this cache maps the specified key (which may be
	 * {@code null} itself), or also {@code null} if the cache did not contain any
	 * mapping for that key prior to this call. Returning {@code null} is therefore
	 * an indicator that the given {@code value} has been associated with the key.
	 * @since 4.1
	 * @see #put(Object, Object)
	 */
	@Nullable
	default ValueWrapper putIfAbsent(Object key, @Nullable Object value) {
		ValueWrapper existingValue = get(key);
		if (existingValue == null) {
			put(key, value);
		}
		return existingValue;
	}

	/**
	 * Evict the mapping for this key from this cache if it is present.
	 * 剔除缓存，不保证实时性
	 * <p>Actual eviction may be performed in an asynchronous or deferred
	 * fashion, with subsequent lookups possibly still seeing the entry.
	 * This may for example be the case with transactional cache decorators.
	 * Use {@link #evictIfPresent} for guaranteed immediate removal.
	 * @param key the key whose mapping is to be removed from the cache
	 * @see #evictIfPresent(Object)
	 */
	void evict(Object key);

	/**
	 * Evict the mapping for this key from this cache if it is present,
	 * expecting the key to be immediately invisible for subsequent lookups.
	 * 立即剔除缓存，返回 false 表示剔除前不存在指定 key 或不确定是否存在，返回 true 表示该 key 之前存在
	 * <p>The default implementation delegates to {@link #evict(Object)},
	 * returning {@code false} for not-determined prior presence of the key.
	 * Cache providers and in particular cache decorators are encouraged
	 * to perform immediate eviction if possible (e.g. in case of generally
	 * deferred cache operations within a transaction) and to reliably
	 * determine prior presence of the given key.
	 * @param key the key whose mapping is to be removed from the cache
	 * @return {@code true} if the cache was known to have a mapping for
	 * this key before, {@code false} if it did not (or if prior presence
	 * could not be determined)
	 * @since 5.2
	 * @see #evict(Object)
	 */
	default boolean evictIfPresent(Object key) {
		evict(key);
		return false;
	}

	/**
	 * Clear the cache through removing all mappings.
	 * 清除所有缓存，不保证实时性
	 * <p>Actual clearing may be performed in an asynchronous or deferred
	 * fashion, with subsequent lookups possibly still seeing the entries.
	 * This may for example be the case with transactional cache decorators.
	 * Use {@link #invalidate()} for guaranteed immediate removal of entries.
	 * @see #invalidate()
	 */
	void clear();

	/**
	 * Invalidate the cache through removing all mappings, expecting all
	 * entries to be immediately invisible for subsequent lookups.
	 * 立即清除所有缓存
	 * @return {@code true} if the cache was known to have mappings before,
	 * {@code false} if it did not (or if prior presence of entries could
	 * not be determined)
	 * @since 5.2
	 * @see #clear()
	 */
	default boolean invalidate() {
		clear();
		return false;
	}


	/**
	 * A (wrapper) object representing a cache value.
	 * 缓存值的一个包装器接口
	 */
	@FunctionalInterface
	interface ValueWrapper {

		/**
		 * Return the actual value in the cache.
		 * 返回缓存中的实际值
		 */
		@Nullable
		Object get();
	}


	/**
	 * Wrapper exception to be thrown from {@link #get(Object, Callable)}
	 * in case of the value loader callback failing with an exception.
	 * @since 4.3
	 */
	@SuppressWarnings("serial")
	class ValueRetrievalException extends RuntimeException {

		@Nullable
		private final Object key;

		public ValueRetrievalException(@Nullable Object key, Callable<?> loader, Throwable ex) {
			super(String.format("Value for key '%s' could not be loaded using '%s'", key, loader), ex);
			this.key = key;
		}

		@Nullable
		public Object getKey() {
			return this.key;
		}
	}

}
