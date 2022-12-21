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

package org.springframework.aop.framework.autoproxy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.aopalliance.aop.Advice;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.Advisor;
import org.springframework.aop.Pointcut;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.AopInfrastructureBean;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.framework.ProxyProcessorSupport;
import org.springframework.aop.framework.adapter.AdvisorAdapterRegistry;
import org.springframework.aop.framework.adapter.GlobalAdvisorAdapterRegistry;
import org.springframework.aop.target.EmptyTargetSource;
import org.springframework.aop.target.SingletonTargetSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor;
import org.springframework.core.SmartClassLoader;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * {@link org.springframework.beans.factory.config.BeanPostProcessor} implementation
 * that wraps each eligible bean with an AOP proxy, delegating to specified interceptors
 * before invoking the bean itself.
 *
 * <p>This class distinguishes between "common" interceptors: shared for all proxies it
 * creates, and "specific" interceptors: unique per bean instance. There need not be any
 * common interceptors. If there are, they are set using the interceptorNames property.
 * As with {@link org.springframework.aop.framework.ProxyFactoryBean}, interceptors names
 * in the current factory are used rather than bean references to allow correct handling
 * of prototype advisors and interceptors: for example, to support stateful mixins.
 * Any advice type is supported for {@link #setInterceptorNames "interceptorNames"} entries.
 *
 * <p>Such auto-proxying is particularly useful if there's a large number of beans that
 * need to be wrapped with similar proxies, i.e. delegating to the same interceptors.
 * Instead of x repetitive proxy definitions for x target beans, you can register
 * one single such post processor with the bean factory to achieve the same effect.
 *
 * <p>Subclasses can apply any strategy to decide if a bean is to be proxied, e.g. by type,
 * by name, by definition details, etc. They can also return additional interceptors that
 * should just be applied to the specific bean instance. A simple concrete implementation is
 * {@link BeanNameAutoProxyCreator}, identifying the beans to be proxied via given names.
 *
 * <p>Any number of {@link TargetSourceCreator} implementations can be used to create
 * a custom target source: for example, to pool prototype objects. Auto-proxying will
 * occur even if there is no advice, as long as a TargetSourceCreator specifies a custom
 * {@link org.springframework.aop.TargetSource}. If there are no TargetSourceCreators set,
 * or if none matches, a {@link org.springframework.aop.target.SingletonTargetSource}
 * will be used by default to wrap the target bean instance.
 *
 * @author Juergen Hoeller
 * @author Rod Johnson
 * @author Rob Harrop
 * @author Sam Brannen
 * @since 13.10.2003
 * @see #setInterceptorNames
 * @see #getAdvicesAndAdvisorsForBean
 * @see BeanNameAutoProxyCreator
 * @see DefaultAdvisorAutoProxyCreator
 */
@SuppressWarnings("serial")
public abstract class AbstractAutoProxyCreator extends ProxyProcessorSupport
		implements SmartInstantiationAwareBeanPostProcessor, BeanFactoryAware {

	/**
	 * Convenience constant for subclasses: Return value for "do not proxy".
	 * @see #getAdvicesAndAdvisorsForBean
	 * 自定义的TargetSourceCreator数组
	 * 通过setCustomTargetSourceCreators方法初始化，默认为null
	 */
	@Nullable
	protected static final Object[] DO_NOT_PROXY = null;

	/**
	 * Convenience constant for subclasses: Return value for
	 * "proxy without additional interceptors, just the common ones".
	 * AbstractAutoProxyCreator
	 * @see #getAdvicesAndAdvisorsForBean
	 */
	protected static final Object[] PROXY_WITHOUT_ADDITIONAL_INTERCEPTORS = new Object[0];


	/** Logger available to subclasses. */
	protected final Log logger = LogFactory.getLog(getClass());

	/** Default is global AdvisorAdapterRegistry.
	 *  DefaultAdvisorAdapterRegistry 单例，Advisor适配器注册中心
	 *  */
	private AdvisorAdapterRegistry advisorAdapterRegistry = GlobalAdvisorAdapterRegistry.getInstance();

	/**
	 * Indicates whether the proxy should be frozen. Overridden from super
	 * to prevent the configuration from becoming frozen too early.
	 *
	 * 是否冻结代理对象
	 */
	private boolean freezeProxy = false;

	/** Default is no common interceptors.
	 * 公共的拦截器对象
	 * */
	private String[] interceptorNames = new String[0];

	/**
	 * 公共的拦截器对象
	 */
	private boolean applyCommonInterceptorsFirst = true;

	/**
	 * 自定义的 TargetSource 创建器
	 */
	@Nullable
	private TargetSourceCreator[] customTargetSourceCreators;

	// bean工厂，通过BeanFactoryAware回调设置
	@Nullable
	private BeanFactory beanFactory;

	/**
	 * 通过自定义TargetSourceCreator创建TargetSource的beanName集合
	 */
	private final Set<String> targetSourcedBeans = Collections.newSetFromMap(new ConcurrentHashMap<>(16));

	/**
	 * 早期代理引用缓存，key为缓存key，value为bean实例
	 */
	private final Map<Object, Object> earlyProxyReferences = new ConcurrentHashMap<>(16);

	/**
	 * 代理类型缓存
	 */
	private final Map<Object, Class<?>> proxyTypes = new ConcurrentHashMap<>(16);

	/**
	 * 通知的bean缓存，包括AOP基础框架Bean或者免代理的Bean，比如Advisor
	 */
	private final Map<Object, Boolean> advisedBeans = new ConcurrentHashMap<>(256);


	/**
	 * Set whether the proxy should be frozen, preventing advice
	 * from being added to it once it is created.
	 * <p>Overridden from the superclass to prevent the proxy configuration
	 * from being frozen before the proxy is created.
	 */
	@Override
	public void setFrozen(boolean frozen) {
		this.freezeProxy = frozen;
	}

	@Override
	public boolean isFrozen() {
		return this.freezeProxy;
	}

	/**
	 * Specify the {@link AdvisorAdapterRegistry} to use.
	 * <p>Default is the global {@link AdvisorAdapterRegistry}.
	 * @see org.springframework.aop.framework.adapter.GlobalAdvisorAdapterRegistry
	 */
	public void setAdvisorAdapterRegistry(AdvisorAdapterRegistry advisorAdapterRegistry) {
		this.advisorAdapterRegistry = advisorAdapterRegistry;
	}

	/**
	 * Set custom {@code TargetSourceCreators} to be applied in this order.
	 * If the list is empty, or they all return null, a {@link SingletonTargetSource}
	 * will be created for each bean.
	 * <p>Note that TargetSourceCreators will kick in even for target beans
	 * where no advices or advisors have been found. If a {@code TargetSourceCreator}
	 * returns a {@link TargetSource} for a specific bean, that bean will be proxied
	 * in any case.
	 * <p>{@code TargetSourceCreators} can only be invoked if this post processor is used
	 * in a {@link BeanFactory} and its {@link BeanFactoryAware} callback is triggered.
	 * @param targetSourceCreators the list of {@code TargetSourceCreators}.
	 * Ordering is significant: The {@code TargetSource} returned from the first matching
	 * {@code TargetSourceCreator} (that is, the first that returns non-null) will be used.
	 */
	public void setCustomTargetSourceCreators(TargetSourceCreator... targetSourceCreators) {
		this.customTargetSourceCreators = targetSourceCreators;
	}

	/**
	 * Set the common interceptors. These must be bean names in the current factory.
	 * They can be of any advice or advisor type Spring supports.
	 * <p>If this property isn't set, there will be zero common interceptors.
	 * This is perfectly valid, if "specific" interceptors such as matching
	 * Advisors are all we want.
	 */
	public void setInterceptorNames(String... interceptorNames) {
		this.interceptorNames = interceptorNames;
	}

	/**
	 * Set whether the common interceptors should be applied before bean-specific ones.
	 * Default is "true"; else, bean-specific interceptors will get applied first.
	 */
	public void setApplyCommonInterceptorsFirst(boolean applyCommonInterceptorsFirst) {
		this.applyCommonInterceptorsFirst = applyCommonInterceptorsFirst;
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	/**
	 * Return the owning {@link BeanFactory}.
	 * May be {@code null}, as this post-processor doesn't need to belong to a bean factory.
	 */
	@Nullable
	protected BeanFactory getBeanFactory() {
		return this.beanFactory;
	}


	@Override
	@Nullable
	public Class<?> predictBeanType(Class<?> beanClass, String beanName) {
		if (this.proxyTypes.isEmpty()) {
			return null;
		}
		Object cacheKey = getCacheKey(beanClass, beanName);
		return this.proxyTypes.get(cacheKey);
	}

	@Override
	public Class<?> determineBeanType(Class<?> beanClass, String beanName) {
		Object cacheKey = getCacheKey(beanClass, beanName);
		Class<?> proxyType = this.proxyTypes.get(cacheKey);
		if (proxyType == null) {
			TargetSource targetSource = getCustomTargetSource(beanClass, beanName);
			if (targetSource != null) {
				if (StringUtils.hasLength(beanName)) {
					this.targetSourcedBeans.add(beanName);
				}
			}
			else {
				targetSource = EmptyTargetSource.forClass(beanClass);
			}
			Object[] specificInterceptors = getAdvicesAndAdvisorsForBean(beanClass, beanName, targetSource);
			if (specificInterceptors != DO_NOT_PROXY) {
				this.advisedBeans.put(cacheKey, Boolean.TRUE);
				proxyType = createProxyClass(beanClass, beanName, specificInterceptors, targetSource);
				this.proxyTypes.put(cacheKey, proxyType);
			}
		}
		return (proxyType != null ? proxyType : beanClass);
	}

	@Override
	@Nullable
	public Constructor<?>[] determineCandidateConstructors(Class<?> beanClass, String beanName) {
		return null;
	}

	/**
	 * AbstractAutoProxyCreator的方法
	 * <p>
	 * 用于获取早期bean的实例，此时bean实例还没有被初始化，该方法用于解决循环引用问题并尝试创建代理对象
	 * <p>
	 * 在我们学习"IoC容器初始化(7)"的文章中就说过该方法，默认情况下其他后处理器在该方法是一个空实现，但是现在增加了AOP功能之后
	 * 新添加了一个AbstractAutoProxyCreator后处理器，它的getEarlyBeanReference方法则用于创建代理对象
	 *
	 * @param bean     已创建的bean实例
	 * @param beanName beanName
	 * @return 原始bean实例或者代理对象
	 */
	@Override
	public Object getEarlyBeanReference(Object bean, String beanName) {
		//获取缓存key
		Object cacheKey = getCacheKey(bean.getClass(), beanName);
		//这里将缓存key和当前bean实例存入earlyProxyReferences缓存中
		this.earlyProxyReferences.put(cacheKey, bean);
		//随后调用同一个wrapIfNecessary方法尝试获取代理对象获取还是返回原始对象
		return wrapIfNecessary(bean, beanName, cacheKey);
	}

	/**
	 * 在bean在Spring实例化bean之前调用，给一个返回代理对象来代替创建目标bean实例的机会。
	 * 如果返回值不为null，那么通过此扩展点获取的bean，随后还会执行postProcessAfterInitialization扩展点方法，
	 * 之后直接返回该bean作为实例，否则继续向后调用，通过Spring的规则实例化、初始化bean。
	 */
	@Override
	public Object postProcessBeforeInstantiation(Class<?> beanClass, String beanName) {
		//为给定beanClass和beanName生成缓存key
		Object cacheKey = getCacheKey(beanClass, beanName);
		//如果没设置beanName，或者targetSourcedBeans缓存不包含该beanName
		if (!StringUtils.hasLength(beanName) || !this.targetSourcedBeans.contains(beanName)) {
			//如果advisedBeans缓存包含该beanName，表示已经被处理了，返回null
			if (this.advisedBeans.containsKey(cacheKey)) {
				//返回null，表示会走Spring创建对象的逻辑，但是后面的postProcessAfterInitialization方法中不会再创建代理
				return null;
			}
			//如果当前bean是Spring AOP的基础结构类，或者shouldSkip返回true，表示不需要代理
			//这个shouldSkip方法，在AspectJAwareAdvisorAutoProxyCreator子类中将会被重写并初始化全部的Advisor通知器实例
			if (isInfrastructureClass(beanClass) || shouldSkip(beanClass, beanName)) {
				//那么当前cacheKey和value=false存入advisedBeans缓存，表示已处理过并且无需代理
				this.advisedBeans.put(cacheKey, Boolean.FALSE);
				//返回null，表示会走Spring创建对象的逻辑，但是后面的postProcessAfterInitialization方法中不会再创建代理
				return null;
			}
		}

		//如果我们有自定义的TargetSource，那么目标bean没必要通过Spring的机制实例化
		//而是使用自定义的TargetSource将以自定义方式实例化目标实例，进而在此处创建代理对象

		//根据beanClass和beanClass获取对应的自定义TargetSource
		TargetSource targetSource = getCustomTargetSource(beanClass, beanName);
		//如果获取到了自定义目标源，那么需要再当前方法创建代理
		if (targetSource != null) {
			if (StringUtils.hasLength(beanName)) {
				//加入targetSourcedBeans缓存集合，表示已处理过，后面的postProcessAfterInitialization方法中不会再创建代理
				this.targetSourcedBeans.add(beanName);
			}
			/*
			 * 下面就是创建代理的逻辑，这部分我们在下面再讲解
			 */
			//获取当前bean的Advisor通知器
			Object[] specificInterceptors = getAdvicesAndAdvisorsForBean(beanClass, beanName, targetSource);
			//创建代理对象
			Object proxy = createProxy(beanClass, beanName, specificInterceptors, targetSource);
			//将当前cacheKey和代理对象class存入proxyTypes缓存中
			this.proxyTypes.put(cacheKey, proxy.getClass());
			//返回代理对象，后续不会走Spring创建对象的逻辑
			return proxy;
		}
		//返回null，表示会走Spring创建对象的逻辑
		return null;
	}


	@Override
	public PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName) {
		return pvs;  // skip postProcessPropertyValues
	}

	/**
	 * Create a proxy with the configured interceptors if the bean is
	 * identified as one to proxy by the subclass.
	 * 如果bean被子类标识为要代理的bean，则使用配置的拦截器创建代理。
	 * @see #getAdvicesAndAdvisorsForBean
	 */
	@Override
	public Object postProcessAfterInitialization(@Nullable Object bean, String beanName) {
		if (bean != null) {
			Object cacheKey = getCacheKey(bean.getClass(), beanName);
			/*
			 * 如果earlyProxyReferences的缓存移除该cacheKey之后的value不等于当前bean，表示当前bean可能需要被代理
			 * earlyProxyReferences的数据是在getEarlyBeanReference方法被调用的时候存入进去的
			 *
			 * 如果相等，表示此前已经对这个bean已经在getEarlyBeanReference方法中调用过了wrapIfNecessary方法
			 * 这个判断就是为了保证对同一个bean的同一次创建过程中，wrapIfNecessary方法只被调用一次
			 */
			if (this.earlyProxyReferences.remove(cacheKey) != bean) {
				/*
				 * 调用wrapIfNecessary方法，如有必要，对目标对象进行代理包装
				 */
				return wrapIfNecessary(bean, beanName, cacheKey);
			}
		}
		return bean;
	}


	/**
	 * Build a cache key for the given bean class and bean name.
	 * <p>Note: As of 4.2.3, this implementation does not return a concatenated
	 * class/name String anymore but rather the most efficient cache key possible:
	 * a plain bean name, prepended with {@link BeanFactory#FACTORY_BEAN_PREFIX}
	 * in case of a {@code FactoryBean}; or if no bean name specified, then the
	 * given bean {@code Class} as-is.
	 * 如果具有beanName，如果是FactoryBean则返回"&beanName"作为cacheKey，否则直接返回beanName作为cacheKey
	 * 如果没有beanName，则直接返回beanClass作为cacheKey
	 * @param beanClass the bean class
	 * @param beanName the bean name
	 * @return the cache key for the given class and name
	 */
	protected Object getCacheKey(Class<?> beanClass, @Nullable String beanName) {
		//如果有beanName
		if (StringUtils.hasLength(beanName)) {
			return (FactoryBean.class.isAssignableFrom(beanClass) ?
					BeanFactory.FACTORY_BEAN_PREFIX + beanName : beanName);
		}
		else {
			return beanClass;
		}
	}

	/**
	 * Wrap the given bean if necessary, i.e. if it is eligible for being proxied.
	 * 如果当前bean对象有资格被代理，那么包装给定的bean类，返回代理对象
	 * @param bean the raw bean instance
	 * @param beanName the name of the bean
	 * @param cacheKey the cache key for metadata access
	 * @return a proxy wrapping the bean, or the raw bean instance as-is
	 */
	protected Object wrapIfNecessary(Object bean, String beanName, Object cacheKey) {
		/*
		 * 如果targetSourcedBeans缓存中包含该beanName，表示已通过TargetSource创建了代理，直接返回原始bean实例
		 * targetSourcedBeans在postProcessBeforeInstantiation中就见过了
		 */
		if (StringUtils.hasLength(beanName) && this.targetSourcedBeans.contains(beanName)) {
			return bean;
		}
		/*
		 * 如果advisedBeans缓存中包含该cacheKey，并且value为false，表示不需要代理，直接返回原始bean实例
		 * advisedBeans在postProcessBeforeInstantiation中就见过了
		 */
		if (Boolean.FALSE.equals(this.advisedBeans.get(cacheKey))) {
			return bean;
		}
		/*
		 * 如果当前bean是Spring AOP的基础结构类，或者shouldSkip返回true，表示不需要代理，直接返回原始bean实例
		 * 这个shouldSkip方法，在AspectJAwareAdvisorAutoProxyCreator子类中将会被重写并初始化全部的Advisor通知器实例
		 * 这两个方法在postProcessBeforeInstantiation中就见过了
		 */
		if (isInfrastructureClass(bean.getClass()) || shouldSkip(bean.getClass(), beanName)) {
			this.advisedBeans.put(cacheKey, Boolean.FALSE);
			return bean;
		}
		/*
		 * 类似于postProcessBeforeInstantiation中创建代理的逻辑 尝试创建代理对象
		 * 1 获取当前bean可用的Advisor通知器，该方法由子类实现
		 */
		Object[] specificInterceptors = getAdvicesAndAdvisorsForBean(bean.getClass(), beanName, null);
		//如果具有Advisor，那么才可以创建代理
		if (specificInterceptors != DO_NOT_PROXY) {
			//存入已代理的缓存集合，value=true，表示已创建代理
			this.advisedBeans.put(cacheKey, Boolean.TRUE);
			/*
			 * 2 通过JDK或者CGLIB创建代理对象，使用默认的SingletonTargetSource包装目标对象
			 */
			Object proxy = createProxy(
					bean.getClass(), beanName, specificInterceptors, new SingletonTargetSource(bean));
			//将当前cacheKey和代理对象class存入proxyTypes缓存中
			this.proxyTypes.put(cacheKey, proxy.getClass());
			//返回代理对象
			return proxy;
		}
		//最终，存入已代理的缓存集合，value=false，表示不需要创建代理，直接返回原始bean实例
		this.advisedBeans.put(cacheKey, Boolean.FALSE);
		return bean;
	}

	/**
	 * Return whether the given bean class represents an infrastructure class
	 * that should never be proxied.
	 * <p>>判断给定的 bean 类是否表示不应被代理的Spring AOP的基础结构类
	 * 默认实现是将Advice、Pointcut、Advisor、AopInfrastructureBean及其实现均作为基础结构类，不会被代理
	 * <p>The default implementation considers Advices, Advisors and
	 * AopInfrastructureBeans as infrastructure classes.
	 * @param beanClass the class of the bean
	 * @return whether the bean represents an infrastructure class
	 * @see org.aopalliance.aop.Advice
	 * @see org.springframework.aop.Advisor
	 * @see org.springframework.aop.framework.AopInfrastructureBean
	 * @see #shouldSkip
	 */
	protected boolean isInfrastructureClass(Class<?> beanClass) {
		boolean retVal = Advice.class.isAssignableFrom(beanClass) ||
				Pointcut.class.isAssignableFrom(beanClass) ||
				Advisor.class.isAssignableFrom(beanClass) ||
				AopInfrastructureBean.class.isAssignableFrom(beanClass);
		if (retVal && logger.isTraceEnabled()) {
			logger.trace("Did not attempt to auto-proxy infrastructure class [" + beanClass.getName() + "]");
		}
		return retVal;
	}

	/**
	 * Subclasses should override this method to return {@code true} if the
	 * given bean should not be considered for auto-proxying by this post-processor.
	 * <p>Sometimes we need to be able to avoid this happening, e.g. if it will lead to
	 * a circular reference or if the existing target instance needs to be preserved.
	 * This implementation returns {@code false} unless the bean name indicates an
	 * "original instance" according to {@code AutowireCapableBeanFactory} conventions.
	 * 是否需要跳过对给定的bean进行自动代理
	 *
	 * @param beanClass the class of the bean
	 * @param beanName the name of the bean
	 * @return whether to skip the given bean
	 * @see org.springframework.beans.factory.config.AutowireCapableBeanFactory#ORIGINAL_INSTANCE_SUFFIX
	 */
	protected boolean shouldSkip(Class<?> beanClass, String beanName) {
		// 检查beanName代表的是不是原始对象(以.ORIGINAL结尾)
		return AutoProxyUtils.isOriginalInstance(beanName, beanClass);
	}

	/**
	 * Create a target source for bean instances. Uses any TargetSourceCreators if set.
	 * Returns {@code null} if no custom TargetSource should be used.
	 * <p>This implementation uses the "customTargetSourceCreators" property.
	 * Subclasses can override this method to use a different mechanism.
	 * <p>如果设置了任何TargetSourceCreator，则为当前bean尝试创建一个目标源，否则返回null
	 * <p>此方法实现使用"customTargetSourceCreators"属性，子类可以重写此方法以使用不同的机制
	 * @param beanClass the class of the bean to create a TargetSource for
	 * @param beanName the name of the bean
	 * @return a TargetSource for this bean
	 * @see #setCustomTargetSourceCreators
	 */
	@Nullable
	protected TargetSource getCustomTargetSource(Class<?> beanClass, String beanName) {
		// 如果customTargetSourceCreators不为null并且当前beanFactory中包含给定的beanName的bean实例或者bean定义
		if (this.customTargetSourceCreators != null &&
				this.beanFactory != null && this.beanFactory.containsBean(beanName)) {
			//遍历全部TargetSourceCreator
			for (TargetSourceCreator tsc : this.customTargetSourceCreators) {
				//依次调用getTargetSource方法
				TargetSource ts = tsc.getTargetSource(beanClass, beanName);
				//如果当前TargetSourceCreator的getTargetSource方法的返回的ts不为null，那么就结束遍历，返回ts
				if (ts != null) {
					// Found a matching TargetSource.
					if (logger.isTraceEnabled()) {
						logger.trace("TargetSourceCreator [" + tsc +
								"] found custom TargetSource for bean with name '" + beanName + "'");
					}
					return ts;
				}
			}
		}
		//未找到自定义目标源，返回null
		return null;
	}

	/**
	 * Create an AOP proxy for the given bean.
	 * @param beanClass the class of the bean
	 * @param beanName the name of the bean
	 * @param specificInterceptors the set of interceptors that is
	 * specific to this bean (may be empty, but not null)
	 * @param targetSource the TargetSource for the proxy,
	 * already pre-configured to access the bean
	 * @return the AOP proxy for the bean
	 * @see #buildAdvisors
	 */
	protected Object createProxy(Class<?> beanClass, @Nullable String beanName,
			@Nullable Object[] specificInterceptors, TargetSource targetSource) {

		return buildProxy(beanClass, beanName, specificInterceptors, targetSource, false);
	}

	private Class<?> createProxyClass(Class<?> beanClass, @Nullable String beanName,
			@Nullable Object[] specificInterceptors, TargetSource targetSource) {

		return (Class<?>) buildProxy(beanClass, beanName, specificInterceptors, targetSource, true);
	}

	/**
	 * 为给定的 bean 创建 AOP 代理
	 * @param beanClass beanClass
	 * @param beanName beanName
	 * @param specificInterceptors 当前bean可用的Advisor通知器链
	 * @param targetSource 代理的目标源
	 * @param classOnly bean的 Aop 代理
	 * @return
	 */
	private Object buildProxy(Class<?> beanClass, @Nullable String beanName,
			@Nullable Object[] specificInterceptors, TargetSource targetSource, boolean classOnly) {

		if (this.beanFactory instanceof ConfigurableListableBeanFactory) {
			//公开指定 bean 的给定目标类，主要就是设置bean定义的ORIGINAL_TARGET_CLASS_ATTRIBUTE属性，
			//即"org.springframework.aop.framework.autoproxy.AutoProxyUtils.originalTargetClass"属性，value为beanClass
			//也就是保存其原来的类型
			AutoProxyUtils.exposeTargetClass((ConfigurableListableBeanFactory) this.beanFactory, beanName, beanClass);
		}

		//新建一个ProxyFactory代理工厂对象，用于创建代理
		ProxyFactory proxyFactory = new ProxyFactory();
		//从当前AbstractAutoProxyCreator拷贝属性，实际上就是拷贝ProxyConfig内部的几个属性
		proxyFactory.copyFrom(this);
		/*
		 * 检查proxyTargetClass属性，判断是走哪种代理方式
		 * proxyTargetClass默认是false的，即先尝试走JDK代理，不行再走CGLIB代理
		 * 如果设置为true，那么就强制走CGLIB代理
		 *
		 * 可通过<aop:aspectj-autoproxy/>、<aop:config/>标签的proxy-target-class的属性设置，默认false
		 * 或者@EnableAspectJAutoProxy注解的proxyTargetClass属性设置，默认false
		 */

		/*
		 * 即使proxyTargetClass属性为false，那么还要继续校验，或者评估接口
		 */
		if (proxyFactory.isProxyTargetClass()) {
			// Explicit handling of JDK proxy targets and lambdas (for introduction advice scenarios)
			if (Proxy.isProxyClass(beanClass) || ClassUtils.isLambdaClass(beanClass)) {
				// Must allow for introductions; can't just set interfaces to the proxy's interfaces only.
				for (Class<?> ifc : beanClass.getInterfaces()) {
					proxyFactory.addInterface(ifc);
				}
			}
		}
		else {
			/*
			 * 继续检查当前bean对应的bean定义的PRESERVE_TARGET_CLASS_ATTRIBUTE属性，即"org.springframework.aop.framework.autoproxy
			 * .AutoProxyUtils.preserveTargetClass"属性，如果存在该属性，并且值为true
			 *
			 * 我们在前面讲解"ConfigurationClassPostProcessor配置类后处理器"的文章中就见过该属性
			 * 对于@Configuration注解标注的代理类，它的bean定义会添加这个属性并且值为true，表示强制走CGLIB代理
			 */
			// No proxyTargetClass flag enforced, let's apply our default checks...
			if (shouldProxyTargetClass(beanClass, beanName)) {
				//那么将proxyTargetClass改为true，表示还是走基于类的CGLIB代理
				proxyFactory.setProxyTargetClass(true);
			}
			else {
				/*
				 * 评估需要代理的接口，添加到proxyFactory中
				 * 如果没有至少一个合理的代理接口，那么仍然会走基于类的CGLIB代理
				 */
				evaluateProxyInterfaces(beanClass, proxyFactory);
			}
		}
		//构建给定 bean 的advisors拦截器链，包括特定的拦截器以及公共拦截器，并且将这些都适配成Advisor接口体系
		//比如MethodInterceptor方法拦截器将被包装成为一个DefaultPointcutAdvisor
		Advisor[] advisors = buildAdvisors(beanName, specificInterceptors);
		//advisors添加到proxyFactory的advisors属性集合中
		proxyFactory.addAdvisors(advisors);
		//targetSource添加到proxyFactory的targetSource属性中，通过此可以获取源目标对象
		proxyFactory.setTargetSource(targetSource);
		//继续自定义ProxyFactory钩子方法，默认空实现，留给子类实现
		//目前版本还没有内置的子类实现这个方法
		customizeProxyFactory(proxyFactory);
		//设置frozen属性，表示指示是否应冻结代理并且无法更改任何advice，默认false
		proxyFactory.setFrozen(this.freezeProxy);
		//判断是否已对advisors进行ClassFilter筛选，如果已苏筛选，那么后续在生成 AOP 调用的advisors链时跳过 ClassFilter 检查
		//默认返回false，即没有进行筛选
		if (advisorsPreFiltered()) {
			//设置proxyFactory的preFiltered属性为true，表示已筛选
			proxyFactory.setPreFiltered(true);
		}

		// Use original ClassLoader if bean class not locally loaded in overriding class loader
		ClassLoader classLoader = getProxyClassLoader();
		if (classLoader instanceof SmartClassLoader && classLoader != beanClass.getClassLoader()) {
			classLoader = ((SmartClassLoader) classLoader).getOriginalClassLoader();
		}
		/*
		 * 通过proxyFactory获取代理对象
		 */
		return (classOnly ? proxyFactory.getProxyClass(classLoader) : proxyFactory.getProxy(classLoader));
	}

	/**
	 * Determine whether the given bean should be proxied with its target class rather than its interfaces.
	 * 指示给定 bean 是否应使用基于类的代理，即CGLIB代理
	 * <p>Checks the {@link AutoProxyUtils#PRESERVE_TARGET_CLASS_ATTRIBUTE "preserveTargetClass" attribute}
	 * of the corresponding bean definition.
	 * @param beanClass the class of the bean
	 * @param beanName the name of the bean
	 * @return whether the given bean should be proxied with its target class
	 * @see AutoProxyUtils#shouldProxyTargetClass
	 */
	protected boolean shouldProxyTargetClass(Class<?> beanClass, @Nullable String beanName) {
		//调用AutoProxyUtils.shouldProxyTargetClass方法
		return (this.beanFactory instanceof ConfigurableListableBeanFactory &&
				AutoProxyUtils.shouldProxyTargetClass((ConfigurableListableBeanFactory) this.beanFactory, beanName));
	}

	/**
	 * Return whether the Advisors returned by the subclass are pre-filtered
	 * to match the bean's target class already, allowing the ClassFilter check
	 * to be skipped when building advisors chains for AOP invocations.
	 * <p>Default is {@code false}. Subclasses may override this if they
	 * will always return pre-filtered Advisors.
	 * @return whether the Advisors are pre-filtered
	 * @see #getAdvicesAndAdvisorsForBean
	 * @see org.springframework.aop.framework.Advised#setPreFiltered
	 */
	protected boolean advisorsPreFiltered() {
		return false;
	}

	/**
	 * Determine the advisors for the given bean, including the specific interceptors
	 * as well as the common interceptor, all adapted to the Advisor interface.
	 * @param beanName the name of the bean
	 * @param specificInterceptors the set of interceptors that is
	 * specific to this bean (may be empty, but not null)
	 * @return the list of Advisors for the given bean
	 */
	protected Advisor[] buildAdvisors(@Nullable String beanName, @Nullable Object[] specificInterceptors) {
		// Handle prototypes correctly...
		Advisor[] commonInterceptors = resolveInterceptorNames();

		List<Object> allInterceptors = new ArrayList<>();
		if (specificInterceptors != null) {
			if (specificInterceptors.length > 0) {
				// specificInterceptors may equal PROXY_WITHOUT_ADDITIONAL_INTERCEPTORS
				allInterceptors.addAll(Arrays.asList(specificInterceptors));
			}
			if (commonInterceptors.length > 0) {
				if (this.applyCommonInterceptorsFirst) {
					allInterceptors.addAll(0, Arrays.asList(commonInterceptors));
				}
				else {
					allInterceptors.addAll(Arrays.asList(commonInterceptors));
				}
			}
		}
		if (logger.isTraceEnabled()) {
			int nrOfCommonInterceptors = commonInterceptors.length;
			int nrOfSpecificInterceptors = (specificInterceptors != null ? specificInterceptors.length : 0);
			logger.trace("Creating implicit proxy for bean '" + beanName + "' with " + nrOfCommonInterceptors +
					" common interceptors and " + nrOfSpecificInterceptors + " specific interceptors");
		}

		Advisor[] advisors = new Advisor[allInterceptors.size()];
		for (int i = 0; i < allInterceptors.size(); i++) {
			advisors[i] = this.advisorAdapterRegistry.wrap(allInterceptors.get(i));
		}
		return advisors;
	}

	/**
	 * Resolves the specified interceptor names to Advisor objects.
	 * @see #setInterceptorNames
	 */
	private Advisor[] resolveInterceptorNames() {
		BeanFactory bf = this.beanFactory;
		ConfigurableBeanFactory cbf = (bf instanceof ConfigurableBeanFactory ? (ConfigurableBeanFactory) bf : null);
		List<Advisor> advisors = new ArrayList<>();
		for (String beanName : this.interceptorNames) {
			if (cbf == null || !cbf.isCurrentlyInCreation(beanName)) {
				Assert.state(bf != null, "BeanFactory required for resolving interceptor names");
				Object next = bf.getBean(beanName);
				advisors.add(this.advisorAdapterRegistry.wrap(next));
			}
		}
		return advisors.toArray(new Advisor[0]);
	}

	/**
	 * Subclasses may choose to implement this: for example,
	 * to change the interfaces exposed.
	 * <p>The default implementation is empty.
	 * @param proxyFactory a ProxyFactory that is already configured with
	 * TargetSource and interfaces and will be used to create the proxy
	 * immediately after this method returns
	 */
	protected void customizeProxyFactory(ProxyFactory proxyFactory) {
	}


	/**
	 * Return whether the given bean is to be proxied, what additional
	 * advices (e.g. AOP Alliance interceptors) and advisors to apply.
	 * 获取特定于Bean的拦截器
	 * @param beanClass the class of the bean to advise
	 * @param beanName the name of the bean
	 * @param customTargetSource the TargetSource returned by the
	 * {@link #getCustomTargetSource} method: may be ignored.
	 * Will be {@code null} if no custom target source is in use.
	 * @return an array of additional interceptors for the particular bean;
	 * or an empty array if no additional interceptors but just the common ones;
	 * or {@code null} if no proxy at all, not even with the common interceptors.
	 * See constants DO_NOT_PROXY and PROXY_WITHOUT_ADDITIONAL_INTERCEPTORS.
	 * @throws BeansException in case of errors
	 * @see #DO_NOT_PROXY
	 * @see #PROXY_WITHOUT_ADDITIONAL_INTERCEPTORS
	 */
	@Nullable
	protected abstract Object[] getAdvicesAndAdvisorsForBean(Class<?> beanClass, String beanName,
			@Nullable TargetSource customTargetSource) throws BeansException;

}
