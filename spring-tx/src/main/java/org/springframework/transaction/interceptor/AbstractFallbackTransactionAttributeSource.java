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

package org.springframework.transaction.interceptor;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.support.AopUtils;
import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.core.MethodClassKey;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringValueResolver;

/**
 * Abstract implementation of {@link TransactionAttributeSource} that caches
 * attributes for methods and implements a fallback policy: 1. specific target
 * method; 2. target class; 3. declaring method; 4. declaring class/interface.
 *
 * <p>Defaults to using the target class's transaction attribute if none is
 * associated with the target method. Any transaction attribute associated with
 * the target method completely overrides a class transaction attribute.
 * If none found on the target class, the interface that the invoked method
 * has been called through (in case of a JDK proxy) will be checked.
 *
 * <p>This implementation caches attributes by method after they are first used.
 * If it is ever desirable to allow dynamic changing of transaction attributes
 * (which is very unlikely), caching could be made configurable. Caching is
 * desirable because of the cost of evaluating rollback rules.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @since 1.1
 */
public abstract class AbstractFallbackTransactionAttributeSource
		implements TransactionAttributeSource, EmbeddedValueResolverAware {

	/**
	 * Canonical value held in cache to indicate no transaction attribute was
	 * found for this method, and we don't need to look again.
	 */
	@SuppressWarnings("serial")
	private static final TransactionAttribute NULL_TRANSACTION_ATTRIBUTE = new DefaultTransactionAttribute() {
		@Override
		public String toString() {
			return "null";
		}
	};


	/**
	 * Logger available to subclasses.
	 * <p>As this base class is not marked Serializable, the logger will be recreated
	 * after serialization - provided that the concrete subclass is Serializable.
	 */
	protected final Log logger = LogFactory.getLog(getClass());

	@Nullable
	private transient StringValueResolver embeddedValueResolver;

	/**
	 * Cache of TransactionAttributes, keyed by method on a specific target class.
	 * <p>As this base class is not marked Serializable, the cache will be recreated
	 * after serialization - provided that the concrete subclass is Serializable.
	 */
	private final Map<Object, TransactionAttribute> attributeCache = new ConcurrentHashMap<>(1024);


	@Override
	public void setEmbeddedValueResolver(StringValueResolver resolver) {
		this.embeddedValueResolver = resolver;
	}


	/**
	 * Determine the transaction attribute for this method invocation.
	 * <p>Defaults to the class's transaction attribute if no method attribute is found.
	 * 确定此方法调用的事务属性。如果没有找到方法属性，则默认为类的事务属性。
	 * @param method the method for the current invocation (never {@code null})
	 * 当前调用的方法(永不为空)
	 * @param targetClass the target class for this invocation (may be {@code null})
	 * 调用的目标类(可能是null)
	 * @return a TransactionAttribute for this method, or {@code null} if the method
	 * is not transactional
	 * 此方法的TransactionAttribute，如果该方法不是事务性的，则为null
	 */
	@Override
	@Nullable
	public TransactionAttribute getTransactionAttribute(Method method, @Nullable Class<?> targetClass) {
		//如果方法是属于Object，直接返回null，这些方法不会应用事务，比如Object的hashcode、equals……
		if (method.getDeclaringClass() == Object.class) {
			return null;
		}

		//获取方法和目标类的缓存key
		Object cacheKey = getCacheKey(method, targetClass);
		//尝试从缓存获取
		TransactionAttribute cached = this.attributeCache.get(cacheKey);
		//如果此前解析过该方法以及目标类，那么Value要么是指示没有事务属性，要么是一个实际的事务属性，一定不会为null
		if (cached != null) {
			//如果value指示没有事务属性，那么返回null
			if (cached == NULL_TRANSACTION_ATTRIBUTE) {
				return null;
			} else {
				//否则直接返回此前解析的事务属性
				return cached;
			}
		} else {
			//到这里表示此前没有解析过该方法和目标类型

			//那么根据当前方法和目标类型计算出TransactionAttribute
			TransactionAttribute txAttr = computeTransactionAttribute(method, targetClass);
			// 如果为null
			if (txAttr == null) {
				//那么存入一个表示没有事务属性的固定值到缓存中，再次遇到时不再解析
				this.attributeCache.put(cacheKey, NULL_TRANSACTION_ATTRIBUTE);
			} else {
				//如果不为null，说明获取到了事务属性

				//获取给定方法的全限定名，基本仅用于输出日志
				String methodIdentification = ClassUtils.getQualifiedMethodName(method, targetClass);
				//如果事务属性属于DefaultTransactionAttribute
				if (txAttr instanceof DefaultTransactionAttribute) {
					//设置描述符
					((DefaultTransactionAttribute) txAttr).setDescriptor(methodIdentification);
				}
				if (logger.isTraceEnabled()) {
					logger.trace("Adding transactional method '" + methodIdentification + "' with attribute: " + txAttr);
				}
				//将结果存入缓存，再次遇到时不再解析
				this.attributeCache.put(cacheKey, txAttr);
			}
			//返回txAttr，可能为null
			return txAttr;
		}
	}


	/**
	 * Determine a cache key for the given method and target class.
	 * <p>Must not produce same key for overloaded methods.
	 * Must produce same key for different instances of the same method.
	 * @param method the method (never {@code null})
	 * @param targetClass the target class (may be {@code null})
	 * @return the cache key (never {@code null})
	 */
	protected Object getCacheKey(Method method, @Nullable Class<?> targetClass) {
		return new MethodClassKey(method, targetClass);
	}

	/**
	 * Same signature as {@link #getTransactionAttribute}, but doesn't cache the result.
	 * {@link #getTransactionAttribute} is effectively a caching decorator for this method.
	 * <p>As of 4.1.8, this method can be overridden.
	 * 计算事务属性的核心方法，但不缓存结果。getTransactionAttribute是这个方法的一个有效的缓存装饰器。
	 * @since 4.1.8
	 * @see #getTransactionAttribute
	 */
	@Nullable
	protected TransactionAttribute computeTransactionAttribute(Method method, @Nullable Class<?> targetClass) {
		//默认不允许非公共方法进行事务代理，这里就是判断public方法的逻辑，但是可以通过allowPublicMethodsOnly方法修改
		//如果是其他访问权限，比如default，那么获取其他AOP操作能够代理，但是事务不会生效
		if (allowPublicMethodsOnly() && !Modifier.isPublic(method.getModifiers())) {
			return null;
		}

		//获取最终要执行的目标方法，有可能参数方法表示的是一个接口的方法，我们需要找到实现类的方法
		//通常情况下，参数方法就是最终执行的方法
		Method specificMethod = AopUtils.getMostSpecificMethod(method, targetClass);

		//首先去找直接标记在方法上的事务注解并解析为事务属性
		//如果方法上有就直接返回，不会再看类上的了事务注解了，这就是方法上的事务注解的优先级更高的原理
		//findTransactionAttribute方法由子类实现
		TransactionAttribute txAttr = findTransactionAttribute(specificMethod);
		if (txAttr != null) {
			return txAttr;
		}
		//如果方法上没有就查找目标类上的事务注解，有就直接返回
		//findTransactionAttribute方法由子类实现
		txAttr = findTransactionAttribute(specificMethod.getDeclaringClass());
		if (txAttr != null && ClassUtils.isUserLevelMethod(method)) {
			return txAttr;
		}
		//到这里表示没有在目标方法或者类上找到事务注解，如果最终的目标方法和当前方法不一致，那么在当前方法中查找
		//实际上很难走到这一步，在此前的查找中基本上就返回了
		if (specificMethod != method) {
			//我们会查找参数方法上的事务注解，如果找到了就返回
			//findTransactionAttribute方法由子类实现
			txAttr = findTransactionAttribute(method);
			if (txAttr != null) {
				return txAttr;
			}
			//如果还是没找到，那么最后一次尝试
			//查找类上是否有事务注解，如果找到了就返回
			//findTransactionAttribute方法由子类实现
			txAttr = findTransactionAttribute(method.getDeclaringClass());
			if (txAttr != null && ClassUtils.isUserLevelMethod(method)) {
				return txAttr;
			}
		}
		//以上都找不到事务注解，那么将返回null，表示当前方法不会进行事务代理
		return null;
	}


	/**
	 * Subclasses need to implement this to return the transaction attribute for the
	 * given class, if any.
	 * @param clazz the class to retrieve the attribute for
	 * @return all transaction attribute associated with this class, or {@code null} if none
	 */
	@Nullable
	protected abstract TransactionAttribute findTransactionAttribute(Class<?> clazz);

	/**
	 * Subclasses need to implement this to return the transaction attribute for the
	 * given method, if any.
	 * @param method the method to retrieve the attribute for
	 * @return all transaction attribute associated with this method, or {@code null} if none
	 */
	@Nullable
	protected abstract TransactionAttribute findTransactionAttribute(Method method);

	/**
	 * Should only public methods be allowed to have transactional semantics?
	 * <p>The default implementation returns {@code false}.
	 */
	protected boolean allowPublicMethodsOnly() {
		return false;
	}

}
