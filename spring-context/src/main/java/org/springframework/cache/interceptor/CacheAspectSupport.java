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
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.annotation.BeanFactoryAnnotationUtils;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.expression.AnnotatedElementKey;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.expression.EvaluationContext;
import org.springframework.lang.Nullable;
import org.springframework.util.*;
import org.springframework.util.function.SingletonSupplier;
import org.springframework.util.function.SupplierUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Base class for caching aspects, such as the {@link CacheInterceptor} or an
 * AspectJ aspect.
 *
 * 缓存切面支持类，是CacheInterceptor的父类，封装了所有的缓存操作的主体逻辑
 *
 * <p>This enables the underlying Spring caching infrastructure to be used easily
 * to implement an aspect for any aspect system.
 *
 * <p>Subclasses are responsible for calling relevant methods in the correct order.
 *
 * <p>Uses the <b>Strategy</b> design pattern. A {@link CacheOperationSource} is
 * used for determining caching operations, a {@link KeyGenerator} will build the
 * cache keys, and a {@link CacheResolver} will resolve the actual cache(s) to use.
 *
 * <p>Note: A cache aspect is serializable but does not perform any actual caching
 * after deserialization.
 *
 * @author Costin Leau
 * @author Juergen Hoeller
 * @author Chris Beams
 * @author Phillip Webb
 * @author Sam Brannen
 * @author Stephane Nicoll
 * @since 3.1
 */
public abstract class CacheAspectSupport extends AbstractCacheInvoker
		implements BeanFactoryAware, InitializingBean, SmartInitializingSingleton {

	protected final Log logger = LogFactory.getLog(getClass());

	/**
	 * k：CacheOperationCacheKey
	 * v：CacheOperationMetadata 缓存操作元数据
	 */
	private final Map<CacheOperationCacheKey, CacheOperationMetadata> metadataCache = new ConcurrentHashMap<>(1024);

	// 负责 SpEL 解析
	private final CacheOperationExpressionEvaluator evaluator = new CacheOperationExpressionEvaluator();

	// 用来解析指定方法的 CacheOperation
	@Nullable
	private CacheOperationSource cacheOperationSource;

	// 默认的 KeyGenerator 是 SimpleKeyGenerator
	private SingletonSupplier<KeyGenerator> keyGenerator = SingletonSupplier.of(SimpleKeyGenerator::new);

	@Nullable
	private SingletonSupplier<CacheResolver> cacheResolver;

	@Nullable
	private BeanFactory beanFactory;

	// 属性初始化的标识
	private boolean initialized = false;


	/**
	 * Configure this aspect with the given error handler, key generator and cache resolver/manager
	 * suppliers, applying the corresponding default if a supplier is not resolvable.
	 * @since 5.1
	 */
	public void configure(
			@Nullable Supplier<CacheErrorHandler> errorHandler, @Nullable Supplier<KeyGenerator> keyGenerator,
			@Nullable Supplier<CacheResolver> cacheResolver, @Nullable Supplier<CacheManager> cacheManager) {
		// 默认 SimpleCacheErrorHandler
		this.errorHandler = new SingletonSupplier<>(errorHandler, SimpleCacheErrorHandler::new);
		// 默认 SimpleKeyGenerator
		this.keyGenerator = new SingletonSupplier<>(keyGenerator, SimpleKeyGenerator::new);
		// 默认基于 cacheManager 构造 SimpleCacheResolver
		// 至此，cacheResolver 有可能还是 null
		this.cacheResolver = new SingletonSupplier<>(cacheResolver,
				() -> SimpleCacheResolver.of(SupplierUtils.resolve(cacheManager)));
	}


	/**
	 * Set one or more cache operation sources which are used to find the cache
	 * attributes. If more than one source is provided, they will be aggregated
	 * using a {@link CompositeCacheOperationSource}.
	 * @see #setCacheOperationSource
	 */
	public void setCacheOperationSources(CacheOperationSource... cacheOperationSources) {
		Assert.notEmpty(cacheOperationSources, "At least 1 CacheOperationSource needs to be specified");
		this.cacheOperationSource = (cacheOperationSources.length > 1 ?
				new CompositeCacheOperationSource(cacheOperationSources) : cacheOperationSources[0]);
	}

	/**
	 * Set the CacheOperationSource for this cache aspect.
	 * @since 5.1
	 * @see #setCacheOperationSources
	 */
	public void setCacheOperationSource(@Nullable CacheOperationSource cacheOperationSource) {
		this.cacheOperationSource = cacheOperationSource;
	}

	/**
	 * Return the CacheOperationSource for this cache aspect.
	 */
	@Nullable
	public CacheOperationSource getCacheOperationSource() {
		return this.cacheOperationSource;
	}

	/**
	 * Set the default {@link KeyGenerator} that this cache aspect should delegate to
	 * if no specific key generator has been set for the operation.
	 * <p>The default is a {@link SimpleKeyGenerator}.
	 */
	public void setKeyGenerator(KeyGenerator keyGenerator) {
		this.keyGenerator = SingletonSupplier.of(keyGenerator);
	}

	/**
	 * Return the default {@link KeyGenerator} that this cache aspect delegates to.
	 */
	public KeyGenerator getKeyGenerator() {
		return this.keyGenerator.obtain();
	}

	/**
	 * Set the default {@link CacheResolver} that this cache aspect should delegate
	 * to if no specific cache resolver has been set for the operation.
	 * <p>The default resolver resolves the caches against their names and the
	 * default cache manager.
	 * @see #setCacheManager
	 * @see SimpleCacheResolver
	 */
	public void setCacheResolver(@Nullable CacheResolver cacheResolver) {
		this.cacheResolver = SingletonSupplier.ofNullable(cacheResolver);
	}

	/**
	 * Return the default {@link CacheResolver} that this cache aspect delegates to.
	 */
	@Nullable
	public CacheResolver getCacheResolver() {
		return SupplierUtils.resolve(this.cacheResolver);
	}

	/**
	 * Set the {@link CacheManager} to use to create a default {@link CacheResolver}.
	 * Replace the current {@link CacheResolver}, if any.
	 * @see #setCacheResolver
	 * @see SimpleCacheResolver
	 */
	public void setCacheManager(CacheManager cacheManager) {
		this.cacheResolver = SingletonSupplier.of(new SimpleCacheResolver(cacheManager));
	}

	/**
	 * Set the containing {@link BeanFactory} for {@link CacheManager} and other
	 * service lookups.
	 * @since 4.3
	 */
	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}


	@Override
	public void afterPropertiesSet() {
		Assert.state(getCacheOperationSource() != null, "The 'cacheOperationSources' property is required: " +
				"If there are no cacheable methods, then don't use a cache aspect.");
	}

	@Override
	public void afterSingletonsInstantiated() {
		if (getCacheResolver() == null) {
			// Lazily initialize cache resolver via default cache manager...
			Assert.state(this.beanFactory != null, "CacheResolver or BeanFactory must be set on cache aspect");
			try {
				/**
				 * 此时如果 cacheResolver 为 null 就尝试从容器中获取 CacheManager
				 * 		来构造 SimpleCacheResolver
				 * 此时如果容器中没有 CacheManager 则抛出异常
				 */
				setCacheManager(this.beanFactory.getBean(CacheManager.class));
			}
			catch (NoUniqueBeanDefinitionException ex) {
				throw new IllegalStateException("No CacheResolver specified, and no unique bean of type " +
						"CacheManager found. Mark one as primary or declare a specific CacheManager to use.", ex);
			}
			catch (NoSuchBeanDefinitionException ex) {
				throw new IllegalStateException("No CacheResolver specified, and no bean of type CacheManager found. " +
						"Register a CacheManager bean or remove the @EnableCaching annotation from your configuration.", ex);
			}
		}
		this.initialized = true;
	}


	/**
	 * Convenience method to return a String representation of this Method
	 * for use in logging. Can be overridden in subclasses to provide a
	 * different identifier for the given method.
	 * @param method the method we're interested in
	 * @param targetClass class the method is on
	 * @return log message identifying this method
	 * @see org.springframework.util.ClassUtils#getQualifiedMethodName
	 */
	protected String methodIdentification(Method method, Class<?> targetClass) {
		Method specificMethod = ClassUtils.getMostSpecificMethod(method, targetClass);
		return ClassUtils.getQualifiedMethodName(specificMethod);
	}

	protected Collection<? extends Cache> getCaches(
			CacheOperationInvocationContext<CacheOperation> context, CacheResolver cacheResolver) {

		Collection<? extends Cache> caches = cacheResolver.resolveCaches(context);
		if (caches.isEmpty()) {
			throw new IllegalStateException("No cache could be resolved for '" +
					context.getOperation() + "' using resolver '" + cacheResolver +
					"'. At least one cache should be provided per cache operation.");
		}
		return caches;
	}

	protected CacheOperationContext getOperationContext(
			CacheOperation operation, Method method, Object[] args, Object target, Class<?> targetClass) {
		// 构造目标方法目标操作上的 CacheOperationMetadata
		CacheOperationMetadata metadata = getCacheOperationMetadata(operation, method, targetClass);
		return new CacheOperationContext(metadata, args, target);
	}

	/**
	 * Return the {@link CacheOperationMetadata} for the specified operation.
	 * <p>Resolve the {@link CacheResolver} and the {@link KeyGenerator} to be
	 * used for the operation.
	 * @param operation the operation
	 * @param method the method on which the operation is invoked
	 * @param targetClass the target type
	 * @return the resolved metadata for the operation
	 */
	protected CacheOperationMetadata getCacheOperationMetadata(
			CacheOperation operation, Method method, Class<?> targetClass) {
		// 生成从map中获取的key，key由Class+Method+CacheOperation组成
		CacheOperationCacheKey cacheKey = new CacheOperationCacheKey(operation, method, targetClass);
		CacheOperationMetadata metadata = this.metadataCache.get(cacheKey);
		if (metadata == null) {
			// 如果metadataCache缓存中没有，计算出 CacheOperationMetadata 并将结果放入到metadataCache缓存中
			KeyGenerator operationKeyGenerator;
			if (StringUtils.hasText(operation.getKeyGenerator())) {
				// 如果@Cacheable，@CachePut，@CacheEvict这些注解中配置了 keyGenerator 的话，获取 KeyGenerator 对象
				operationKeyGenerator = getBean(operation.getKeyGenerator(), KeyGenerator.class);
			}
			else {
				// 如果@Cacheable，@CachePut，@CacheEvict这些注解中没有配置 keyGenerator 的话，获取默认的 KeyGenerator 对象
				operationKeyGenerator = getKeyGenerator();
			}
			CacheResolver operationCacheResolver;
			if (StringUtils.hasText(operation.getCacheResolver())) {
				// 如果@Cacheable，@CachePut，@CacheEvict这些注解中配置了 cacheResolver 的话，获取 CacheResolver 对象
				operationCacheResolver = getBean(operation.getCacheResolver(), CacheResolver.class);
			}
			else if (StringUtils.hasText(operation.getCacheManager())) {
				// 如果@Cacheable，@CachePut，@CacheEvict这些注解中配置了 cacheManager 的话，获取 CacheManager 来构造 SimpleCacheResolver 对象
				CacheManager cacheManager = getBean(operation.getCacheManager(), CacheManager.class);
				operationCacheResolver = new SimpleCacheResolver(cacheManager);
			}
			else {
				// 如果@Cacheable，@CachePut，@CacheEvict这些注解中没有配置了 cacheResolver 和 cacheManager 的话
				// 获取默认的 CacheResolver
				// afterSingletonsInstantiated() 的时候判断，如果默认的 cacheResolver==null 的话，会new一个对象
				operationCacheResolver = getCacheResolver();
				Assert.state(operationCacheResolver != null, "No CacheResolver/CacheManager set");
			}
			// 计算出 CacheOperationMetadata 并将结果放入到metadataCache缓存中
			metadata = new CacheOperationMetadata(operation, method, targetClass,
					operationKeyGenerator, operationCacheResolver);
			this.metadataCache.put(cacheKey, metadata);
		}
		return metadata;
	}

	/**
	 * Return a bean with the specified name and type. Used to resolve services that
	 * are referenced by name in a {@link CacheOperation}.
	 * @param beanName the name of the bean, as defined by the operation
	 * @param expectedType type for the bean
	 * @return the bean matching that name
	 * @throws org.springframework.beans.factory.NoSuchBeanDefinitionException if such bean does not exist
	 * @see CacheOperation#getKeyGenerator()
	 * @see CacheOperation#getCacheManager()
	 * @see CacheOperation#getCacheResolver()
	 */
	protected <T> T getBean(String beanName, Class<T> expectedType) {
		if (this.beanFactory == null) {
			throw new IllegalStateException(
					"BeanFactory must be set on cache aspect for " + expectedType.getSimpleName() + " retrieval");
		}
		return BeanFactoryAnnotationUtils.qualifiedBeanOfType(this.beanFactory, expectedType, beanName);
	}

	/**
	 * Clear the cached metadata.
	 */
	protected void clearMetadataCache() {
		this.metadataCache.clear();
		this.evaluator.clear();
	}

	// CacheInterceptor调父类的该方法
	@Nullable
	protected Object execute(CacheOperationInvoker invoker, Object target, Method method, Object[] args) {
		// Check whether aspect is enabled (to cope with cases where the AJ is pulled in automatically)
		if (this.initialized) {
			// 已经初始化完成
			Class<?> targetClass = getTargetClass(target);
			// CacheInterceptor调父类的该方法
			CacheOperationSource cacheOperationSource = getCacheOperationSource();
			if (cacheOperationSource != null) {
				// 根据Class+Method获取 Collection<CacheOperation>
				Collection<CacheOperation> operations = cacheOperationSource.getCacheOperations(method, targetClass);
				if (!CollectionUtils.isEmpty(operations)) {
					// 基于 CacheOperation 集合创建的 CacheOperationContexts 执行 execute 方法
					return execute(invoker, method,
							new CacheOperationContexts(operations, method, args, target, targetClass));
				}
			}
		}
		// 如果未初始化好，不走缓存相关的逻辑
		return invoker.invoke();
	}

	/**
	 * Execute the underlying operation (typically in case of cache miss) and return
	 * the result of the invocation. If an exception occurs it will be wrapped in a
	 * {@link CacheOperationInvoker.ThrowableWrapper}: the exception can be handled
	 * or modified but it <em>must</em> be wrapped in a
	 * {@link CacheOperationInvoker.ThrowableWrapper} as well.
	 * @param invoker the invoker handling the operation being cached
	 * @return the result of the invocation
	 * @see CacheOperationInvoker#invoke()
	 */
	@Nullable
	protected Object invokeOperation(CacheOperationInvoker invoker) {
		return invoker.invoke();
	}

	private Class<?> getTargetClass(Object target) {
		return AopProxyUtils.ultimateTargetClass(target);
	}

	// 这个代码，可以结合 @Caching 注解看，一个method上可能 @Cacheable，@CachePut，@CacheEvict 的各种组合.单个 @Cacheable，@CachePut, @CacheEvict只是其中一种特殊情况
	@Nullable
	private Object execute(final CacheOperationInvoker invoker, Method method, CacheOperationContexts contexts) {
		// Special handling of synchronized invocation
		// 如果当前方法要进行同步缓存操作
		if (contexts.isSynchronized()) {
			CacheOperationContext context = contexts.get(CacheableOperation.class).iterator().next();
			if (isConditionPassing(context, CacheOperationExpressionEvaluator.NO_RESULT)) {
				Object key = generateKey(context, CacheOperationExpressionEvaluator.NO_RESULT);
				Cache cache = context.getCaches().iterator().next();
				try {
					/**
					 * 返回值是 Optional 则包装缓存或执行结果后返回
					 * handleSynchronizedGet 方法依赖于缓存的 get(Object key, Callable<T> valueLoader)
					 * 		方法，即 get 不到就将 valueLoader::call 缓存进去并返回
					 * get 方法的实现保证同步性，比如 ConcurrentMapCache 基于 ConcurrentMap#computeIfAbsent
					 * 		方法保证同步
					 */
					return wrapCacheValue(method, handleSynchronizedGet(invoker, key, cache));
				}
				catch (Cache.ValueRetrievalException ex) {
					// Directly propagate ThrowableWrapper from the invoker,
					// or potentially also an IllegalArgumentException etc.
					ReflectionUtils.rethrowRuntimeException(ex.getCause());
				}
			}
			else {
				// No caching required, only call the underlying method
				// condition 不通过就直接执行目标方法了
				return invokeOperation(invoker);
			}
		}


		// Process any early evictions
		// 如果有 @CacheEvict 注解，并且 beforeInvocation==true 执行 cache.evict(key);
		processCacheEvicts(contexts.get(CacheEvictOperation.class), true,
				CacheOperationExpressionEvaluator.NO_RESULT);

		// Check if we have a cached item matching the conditions
		// 不管是否有 @Cacheable 注解，都查一下缓存
		// 如果 cacheName 所在的 cache 缓存命中，返回 Cache.ValueWrapper 对象（这个对象非null，但是getValue可能为null），否则返回 null
		Cache.ValueWrapper cacheHit = findCachedItem(contexts.get(CacheableOperation.class));

		// Collect puts from any @Cacheable miss, if no cached item is found
		// 如果缓存不命中，并且有 @Cacheable ，后续需要放在缓存中，收集这些请求，收集中需要注意 condition 的判断
		// 【注意】：这里没有收集 @CachePut，因为 @CachePut 一定会执行方法，并将结果放入到 cache，后面的代码会有这个逻辑b
		List<CachePutRequest> cachePutRequests = new ArrayList<>();
		if (cacheHit == null) {
			// 虽然缓存没命中，但收集到的 CacheableOperation 操作可能没有
			// 因为前面代码时："不管是否有 @Cacheable 注解，都查一下缓存"
			collectPutRequests(contexts.get(CacheableOperation.class),
					CacheOperationExpressionEvaluator.NO_RESULT, cachePutRequests);
		}

		Object cacheValue;
		Object returnValue;

		/**
		 * 如果缓存命中
		 * cacheVal 就是缓存值
		 * returnVal 有必要的话包装成 Optional
		 */
		if (cacheHit != null && !hasCachePut(contexts)) {
			// If there are no put requests, just use the cache hit
			// 没有满足 condition 的 @Cacheable 操作，或者 当前没有满足condition的 @CachePut 操作
			// 没有满足 condition 的XXX操作的含义是：没有XXX注解，或者有XXX注解，但是condition不满足
			cacheValue = cacheHit.get();
			returnValue = wrapCacheValue(method, cacheValue);
		}
		/**
		 * 如果缓存未命中
		 * returnValue 就是目标方法的执行结果
		 * cacheVal 有必要的话取 Optional 的真实值
		 */
		else {
			// Invoke the method if we don't have a cache hit
			returnValue = invokeOperation(invoker);
			cacheValue = unwrapReturnValue(returnValue);
		}

		// Collect any explicit @CachePuts
		// 收集 @CachePut 的写缓存请求
		// 【注意】：这里会处理 unless 逻辑，只有满足unless逻辑的，才可以写到缓存中
		collectPutRequests(contexts.get(CachePutOperation.class), cacheValue, cachePutRequests);

		// Process any collected put requests, either from @CachePut or a @Cacheable miss
		// 涉及到写缓存的请求，执行 cache.put
		for (CachePutRequest cachePutRequest : cachePutRequests) {
			cachePutRequest.apply(cacheValue);
		}

		// Process any late evictions
		// 处理 @CacheEvict 请求，处理 cache.evict 或者 cache.clear (allEntries=true)
		processCacheEvicts(contexts.get(CacheEvictOperation.class), false, cacheValue);

		// 返回结果
		return returnValue;
	}

	@Nullable
	private Object handleSynchronizedGet(CacheOperationInvoker invoker, Object key, Cache cache) {
		InvocationAwareResult invocationResult = new InvocationAwareResult();
		Object result = cache.get(key, () -> {
			invocationResult.invoked = true;
			if (logger.isTraceEnabled()) {
				logger.trace("No cache entry for key '" + key + "' in cache " + cache.getName());
			}
			return unwrapReturnValue(invokeOperation(invoker));
		});
		if (!invocationResult.invoked && logger.isTraceEnabled()) {
			logger.trace("Cache entry for key '" + key + "' found in cache '" + cache.getName() + "'");
		}
		return result;
	}

	@Nullable
	private Object wrapCacheValue(Method method, @Nullable Object cacheValue) {
		if (method.getReturnType() == Optional.class &&
				(cacheValue == null || cacheValue.getClass() != Optional.class)) {
			return Optional.ofNullable(cacheValue);
		}
		return cacheValue;
	}

	@Nullable
	private Object unwrapReturnValue(@Nullable Object returnValue) {
		return ObjectUtils.unwrapOptional(returnValue);
	}

	private boolean hasCachePut(CacheOperationContexts contexts) {
		// Evaluate the conditions *without* the result object because we don't have it yet...
		Collection<CacheOperationContext> cachePutContexts = contexts.get(CachePutOperation.class);
		Collection<CacheOperationContext> excluded = new ArrayList<>();
		for (CacheOperationContext context : cachePutContexts) {
			try {
				if (!context.isConditionPassing(CacheOperationExpressionEvaluator.RESULT_UNAVAILABLE)) {
					excluded.add(context);
				}
			}
			catch (VariableNotAvailableException ex) {
				// Ignoring failure due to missing result, consider the cache put has to proceed
			}
		}
		// Check if all puts have been excluded by condition
		return (cachePutContexts.size() != excluded.size());
	}

	private void processCacheEvicts(
			Collection<CacheOperationContext> contexts, boolean beforeInvocation, @Nullable Object result) {

		for (CacheOperationContext context : contexts) {
			CacheEvictOperation operation = (CacheEvictOperation) context.metadata.operation;
			if (beforeInvocation == operation.isBeforeInvocation() && isConditionPassing(context, result)) {
				performCacheEvict(context, operation, result);
			}
		}
	}

	private void performCacheEvict(
			CacheOperationContext context, CacheEvictOperation operation, @Nullable Object result) {

		Object key = null;
		for (Cache cache : context.getCaches()) {
			if (operation.isCacheWide()) {
				logInvalidating(context, operation, null);
				doClear(cache, operation.isBeforeInvocation());
			}
			else {
				if (key == null) {
					key = generateKey(context, result);
				}
				logInvalidating(context, operation, key);
				doEvict(cache, key, operation.isBeforeInvocation());
			}
		}
	}

	/**
	 *  验证日志信息
	 * @param context
	 * @param operation 缓存操作
	 * @param key 缓存标识
	 */
	private void logInvalidating(CacheOperationContext context, CacheEvictOperation operation, @Nullable Object key) {
		if (logger.isTraceEnabled()) {
			logger.trace("Invalidating " + (key != null ? "cache key [" + key + "]" : "entire cache") +
					" for operation " + operation + " on method " + context.metadata.method);
		}
	}

	/**
	 * Find a cached item only for {@link CacheableOperation} that passes the condition.
	 * 仅为通过条件的CacheableOperation查找缓存项。
	 * @param contexts the cacheable operations
	 * @return a {@link Cache.ValueWrapper} holding the cached item,
	 * or {@code null} if none is found
	 */
	@Nullable
	private Cache.ValueWrapper findCachedItem(Collection<CacheOperationContext> contexts) {
		Object result = CacheOperationExpressionEvaluator.NO_RESULT;
		for (CacheOperationContext context : contexts) {
			// 如果满足condition条件，才查询缓存
			if (isConditionPassing(context, result)) {
				// 生成缓存key，如果注解中指定了key，则按照Spring表达式解析，否则使用KeyGenerator类生成
				Object key = generateKey(context, result);
				// 根据缓存key，查询缓存值
				Cache.ValueWrapper cached = findInCaches(context, key);
				if (cached != null) {
					return cached;
				}
				else {
					if (logger.isTraceEnabled()) {
						logger.trace("No cache entry for key '" + key + "' in cache(s) " + context.getCacheNames());
					}
				}
			}
		}
		return null;
	}

	/**
	 * Collect the {@link CachePutRequest} for all {@link CacheOperation} using
	 * the specified result item.
	 * 使用指定的结果项收集所有CacheOperation 的CachePutRequest。
	 * @param contexts the contexts to handle
	 * @param result the result item (never {@code null})
	 * @param putRequests the collection to update
	 */
	private void collectPutRequests(Collection<CacheOperationContext> contexts,
			@Nullable Object result, Collection<CachePutRequest> putRequests) {

		for (CacheOperationContext context : contexts) {
			if (isConditionPassing(context, result)) {
				Object key = generateKey(context, result);
				putRequests.add(new CachePutRequest(context, key));
			}
		}
	}

	@Nullable
	private Cache.ValueWrapper findInCaches(CacheOperationContext context, Object key) {
		for (Cache cache : context.getCaches()) {
			// 调用父类AbstractCacheInvoker的doGet方法，查询缓存
			Cache.ValueWrapper wrapper = doGet(cache, key);
			if (wrapper != null) {
				if (logger.isTraceEnabled()) {
					logger.trace("Cache entry for key '" + key + "' found in cache '" + cache.getName() + "'");
				}
				return wrapper;
			}
		}
		return null;
	}

	private boolean isConditionPassing(CacheOperationContext context, @Nullable Object result) {
		boolean passing = context.isConditionPassing(result);
		if (!passing && logger.isTraceEnabled()) {
			logger.trace("Cache condition failed on method " + context.metadata.method +
					" for operation " + context.metadata.operation);
		}
		return passing;
	}

	private Object generateKey(CacheOperationContext context, @Nullable Object result) {
		Object key = context.generateKey(result);
		if (key == null) {
			throw new IllegalArgumentException("Null key returned for cache operation (maybe you are " +
					"using named params on classes without debug info?) " + context.metadata.operation);
		}
		if (logger.isTraceEnabled()) {
			logger.trace("Computed cache key '" + key + "' for operation " + context.metadata.operation);
		}
		return key;
	}

	/**
	 * CacheOperationContexts 维护这样一组结构：
	 * 		一个方法对应多种操作
	 * 		每种操作对应多个操作上下文 CacheOperationContext
	 * 比如：
	 * @Cacheable("cache1")
	 * @Cacheable("cache2")
	 * @CachePut("cache3")
	 * public void test() {}
	 *
	 * 则上述方法 test 对应的 CacheOperationContexts 维护结构大致为：
	 * Cacheable.class -> [CacheOperationContext1, CacheOperationContext2]
	 * CachePut.class -> [CacheOperationContext3]
	 */
	private class CacheOperationContexts {

		// MultiValueMap: Spring 提供的一个可以重复同一个 key 的 Map
		private final MultiValueMap<Class<? extends CacheOperation>, CacheOperationContext> contexts;

		// 当前缓存操作是否需要同步执行
		private final boolean sync;

		public CacheOperationContexts(Collection<? extends CacheOperation> operations, Method method,
				Object[] args, Object target, Class<?> targetClass) {
			// 基于传入的 CacheOperation 集合构造对应的 CacheOperationContexts
			this.contexts = new LinkedMultiValueMap<>(operations.size());
			for (CacheOperation op : operations) {
				this.contexts.add(op.getClass(), getOperationContext(op, method, args, target, targetClass));
			}
			// 推断当前缓存操作是否需要同步执行
			this.sync = determineSyncFlag(method);
		}

		public Collection<CacheOperationContext> get(Class<? extends CacheOperation> operationClass) {
			Collection<CacheOperationContext> result = this.contexts.get(operationClass);
			return (result != null ? result : Collections.emptyList());
		}

		public boolean isSynchronized() {
			return this.sync;
		}

		// 推断是否同步执行
		private boolean determineSyncFlag(Method method) {
			// 同步操作只支持 CacheableOperation，如果没有那就是不需要同步
			List<CacheOperationContext> cacheOperationContexts = this.contexts.get(CacheableOperation.class);
			if (cacheOperationContexts == null) {  // no @Cacheable operation at all
				return false;
			}
			/**
			 * 如果上述 CacheableOperation 操作里只要有一个指定属性 sync = true 的，就开始判断是否真的满足同步执行条件
			 */
			boolean syncEnabled = false;
			for (CacheOperationContext cacheOperationContext : cacheOperationContexts) {
				if (((CacheableOperation) cacheOperationContext.getOperation()).isSync()) {
					syncEnabled = true;
					break;
				}
			}
			// 校验信息
			if (syncEnabled) {
				// 多个 contexts 意味多种缓存操作，不支持同步执行
				if (this.contexts.size() > 1) {
					throw new IllegalStateException(
							"@Cacheable(sync=true) cannot be combined with other cache operations on '" + method + "'");
				}
				// 也不支持多个 @Cacheable(sync=true) 并行
				if (cacheOperationContexts.size() > 1) {
					throw new IllegalStateException(
							"Only one @Cacheable(sync=true) entry is allowed on '" + method + "'");
				}
				// @Cacheable(sync=true) 操作也不支持同时指定多个 cacheName
				CacheOperationContext cacheOperationContext = cacheOperationContexts.iterator().next();
				CacheableOperation operation = (CacheableOperation) cacheOperationContext.getOperation();
				if (cacheOperationContext.getCaches().size() > 1) {
					throw new IllegalStateException(
							"@Cacheable(sync=true) only allows a single cache on '" + operation + "'");
				}
				// 缓存操作不支持指定 unless 属性
				if (StringUtils.hasText(operation.getUnless())) {
					throw new IllegalStateException(
							"@Cacheable(sync=true) does not support unless attribute on '" + operation + "'");
				}
				return true;
			}
			return false;
		}
	}


	/**
	 * Metadata of a cache operation that does not depend on a particular invocation
	 * which makes it a good candidate for caching.
	 */
	protected static class CacheOperationMetadata {

		/**
		 * cache 操作信息
		 */
		private final CacheOperation operation;

		/**
		 * 执行的方法
		 */
		private final Method method;

		/**
		 * 执行方法的Class
		 */
		private final Class<?> targetClass;

		private final Method targetMethod;

		private final AnnotatedElementKey methodKey;

		/**
		 * KeyGenerator
		 * 创建CacheOperationMetadata的地方会根据 CacheOperation 的字符串 keyGenerator 来获取实际的 KeyGenerator 对象
		 */
		private final KeyGenerator keyGenerator;

		/**
		 * CacheResolver 用来管理 Cache
		 * 创建CacheOperationMetadata的地方会根据 CacheOperation 的字符串 cacheManager 和 cacheResolver 获取 CacheResolver 对象
		 */
		private final CacheResolver cacheResolver;

		public CacheOperationMetadata(CacheOperation operation, Method method, Class<?> targetClass,
				KeyGenerator keyGenerator, CacheResolver cacheResolver) {

			this.operation = operation;
			this.method = BridgeMethodResolver.findBridgedMethod(method);
			this.targetClass = targetClass;
			this.targetMethod = (!Proxy.isProxyClass(targetClass) ?
					AopUtils.getMostSpecificMethod(method, targetClass) : this.method);
			this.methodKey = new AnnotatedElementKey(this.targetMethod, targetClass);
			this.keyGenerator = keyGenerator;
			this.cacheResolver = cacheResolver;
		}
	}


	/**
	 * A {@link CacheOperationInvocationContext} context for a {@link CacheOperation}.
	 * 缓存操作上下文：保存单个缓存操作执行时的参数信息，并计算condition和unless参数是否通过
	 */
	protected class CacheOperationContext implements CacheOperationInvocationContext<CacheOperation> {

		/**
		 * cache 操作的元数据
		 */
		private final CacheOperationMetadata metadata;

		/**
		 * 执行方法的参数
		 */
		private final Object[] args;

		/**
		 * 执行方法的实例
		 */
		private final Object target;

		/**
		 * 通过metadata中的CacheOperation.cacheNames和CacheResolver可以获取 Cache集合
		 */
		private final Collection<? extends Cache> caches;

		/**
		 * 就是 CacheOperation.cacheNames 的一个复制
		 */
		private final Collection<String> cacheNames;

		@Nullable
		private Boolean conditionPassing;

		public CacheOperationContext(CacheOperationMetadata metadata, Object[] args, Object target) {
			this.metadata = metadata;
			this.args = extractArgs(metadata.method, args);
			this.target = target;
			this.caches = CacheAspectSupport.this.getCaches(this, metadata.cacheResolver);
			this.cacheNames = createCacheNames(this.caches);
		}

		@Override
		public CacheOperation getOperation() {
			return this.metadata.operation;
		}

		@Override
		public Object getTarget() {
			return this.target;
		}

		@Override
		public Method getMethod() {
			return this.metadata.method;
		}

		@Override
		public Object[] getArgs() {
			return this.args;
		}

		private Object[] extractArgs(Method method, Object[] args) {
			if (!method.isVarArgs()) {
				return args;
			}
			Object[] varArgs = ObjectUtils.toObjectArray(args[args.length - 1]);
			Object[] combinedArgs = new Object[args.length - 1 + varArgs.length];
			System.arraycopy(args, 0, combinedArgs, 0, args.length - 1);
			System.arraycopy(varArgs, 0, combinedArgs, args.length - 1, varArgs.length);
			return combinedArgs;
		}

		// 是否满足 condition 条件
		protected boolean isConditionPassing(@Nullable Object result) {
			if (this.conditionPassing == null) {
				if (StringUtils.hasText(this.metadata.operation.getCondition())) {
					EvaluationContext evaluationContext = createEvaluationContext(result);
					// 基于 CacheOperationExpressionEvaluator::condition 方法
					this.conditionPassing = evaluator.condition(this.metadata.operation.getCondition(),
							this.metadata.methodKey, evaluationContext);
				}
				else {
					this.conditionPassing = true;
				}
			}
			return this.conditionPassing;
		}

		// 是否缓存目标结果，即 unless 条件判断
		protected boolean canPutToCache(@Nullable Object value) {
			String unless = "";
			// 只有 Cacheable 和 CachePut 支持 unless
			if (this.metadata.operation instanceof CacheableOperation) {
				unless = ((CacheableOperation) this.metadata.operation).getUnless();
			}
			else if (this.metadata.operation instanceof CachePutOperation) {
				unless = ((CachePutOperation) this.metadata.operation).getUnless();
			}
			if (StringUtils.hasText(unless)) {
				EvaluationContext evaluationContext = createEvaluationContext(value);
				// 基于 CacheOperationExpressionEvaluator::unless 方法
				return !evaluator.unless(unless, this.metadata.methodKey, evaluationContext);
			}
			// 未指定就是执行缓存操作
			return true;
		}

		/**
		 * Compute the key for the given caching operation.
		 * 生成对应的缓存 key
		 */
		@Nullable
		protected Object generateKey(@Nullable Object result) {
			// 如果指定了 key 就基于 key 表达式计算
			if (StringUtils.hasText(this.metadata.operation.getKey())) {
				EvaluationContext evaluationContext = createEvaluationContext(result);
				return evaluator.key(this.metadata.operation.getKey(), this.metadata.methodKey, evaluationContext);
			}
			// 否则就由 keyGenerator 生成
			return this.metadata.keyGenerator.generate(this.target, this.metadata.method, this.args);
		}

		// 基于 CacheOperationExpressionEvaluator::createEvaluationContext 获取对应的 EvaluationContext
		private EvaluationContext createEvaluationContext(@Nullable Object result) {
			return evaluator.createEvaluationContext(this.caches, this.metadata.method, this.args,
					this.target, this.metadata.targetClass, this.metadata.targetMethod, result, beanFactory);
		}

		protected Collection<? extends Cache> getCaches() {
			return this.caches;
		}

		protected Collection<String> getCacheNames() {
			return this.cacheNames;
		}

		private Collection<String> createCacheNames(Collection<? extends Cache> caches) {
			Collection<String> names = new ArrayList<>();
			for (Cache cache : caches) {
				names.add(cache.getName());
			}
			return names;
		}
	}


	private class CachePutRequest {

		private final CacheOperationContext context;

		private final Object key;

		public CachePutRequest(CacheOperationContext context, Object key) {
			this.context = context;
			this.key = key;
		}

		public void apply(@Nullable Object result) {
			if (this.context.canPutToCache(result)) {
				for (Cache cache : this.context.getCaches()) {
					doPut(cache, this.key, result);
				}
			}
		}
	}


	private static final class CacheOperationCacheKey implements Comparable<CacheOperationCacheKey> {

		private final CacheOperation cacheOperation;

		private final AnnotatedElementKey methodCacheKey;

		private CacheOperationCacheKey(CacheOperation cacheOperation, Method method, Class<?> targetClass) {
			this.cacheOperation = cacheOperation;
			this.methodCacheKey = new AnnotatedElementKey(method, targetClass);
		}

		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other) {
				return true;
			}
			if (!(other instanceof CacheOperationCacheKey otherKey)) {
				return false;
			}
			return (this.cacheOperation.equals(otherKey.cacheOperation) &&
					this.methodCacheKey.equals(otherKey.methodCacheKey));
		}

		@Override
		public int hashCode() {
			return (this.cacheOperation.hashCode() * 31 + this.methodCacheKey.hashCode());
		}

		@Override
		public String toString() {
			return this.cacheOperation + " on " + this.methodCacheKey;
		}

		@Override
		public int compareTo(CacheOperationCacheKey other) {
			int result = this.cacheOperation.getName().compareTo(other.cacheOperation.getName());
			if (result == 0) {
				result = this.methodCacheKey.compareTo(other.methodCacheKey);
			}
			return result;
		}
	}

	/**
	 * Internal holder class for recording that a cache method was invoked.
	 */
	private static class InvocationAwareResult {

		boolean invoked;

	}

}
