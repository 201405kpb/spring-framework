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

package org.springframework.aop.framework;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.aop.Advisor;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor;
import org.springframework.core.SmartClassLoader;
import org.springframework.lang.Nullable;

/**
 * Base class for {@link BeanPostProcessor} implementations that apply a
 * Spring AOP {@link Advisor} to specific beans.
 *
 * @author Juergen Hoeller
 * @since 3.2
 */
@SuppressWarnings("serial")
public abstract class AbstractAdvisingBeanPostProcessor extends ProxyProcessorSupport
		implements SmartInstantiationAwareBeanPostProcessor {

	@Nullable
	protected Advisor advisor;

	protected boolean beforeExistingAdvisors = false;

	private final Map<Class<?>, Boolean> eligibleBeans = new ConcurrentHashMap<>(256);


	/**
	 * Set whether this post-processor's advisor is supposed to apply before
	 * existing advisors when encountering a pre-advised object.
	 * <p>Default is "false", applying the advisor after existing advisors, i.e.
	 * as close as possible to the target method. Switch this to "true" in order
	 * for this post-processor's advisor to wrap existing advisors as well.
	 * <p>Note: Check the concrete post-processor's javadoc whether it possibly
	 * changes this flag by default, depending on the nature of its advisor.
	 */
	public void setBeforeExistingAdvisors(boolean beforeExistingAdvisors) {
		this.beforeExistingAdvisors = beforeExistingAdvisors;
	}


	@Override
	public Class<?> determineBeanType(Class<?> beanClass, String beanName) {
		if (this.advisor != null && isEligible(beanClass)) {
			ProxyFactory proxyFactory = new ProxyFactory();
			proxyFactory.copyFrom(this);
			proxyFactory.setTargetClass(beanClass);

			if (!proxyFactory.isProxyTargetClass()) {
				evaluateProxyInterfaces(beanClass, proxyFactory);
			}
			proxyFactory.addAdvisor(this.advisor);
			customizeProxyFactory(proxyFactory);

			// Use original ClassLoader if bean class not locally loaded in overriding class loader
			ClassLoader classLoader = getProxyClassLoader();
			if (classLoader instanceof SmartClassLoader && classLoader != beanClass.getClassLoader()) {
				classLoader = ((SmartClassLoader) classLoader).getOriginalClassLoader();
			}
			return proxyFactory.getProxyClass(classLoader);
		}

		return beanClass;
	}

	/**
	 * 此时普通bean已经实例化、初始化完毕
	 */
	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		//如果通知器为null或者当前bean是一个Spring AOP的基础bean(比如AbstractAutoProxyCreator)，那么不进行代理
		if (this.advisor == null || bean instanceof AopInfrastructureBean) {
			// Ignore AOP infrastructure such as scoped proxies.
			return bean;
		}
		/*
		 * 如果这个Bean属于Advised类型，即表示当前bean已经被代理过了(因为创建的代理类将会实现Advised接口)
		 * 那么不再重新创建代理对象，只是尝试将通知器添加到当前代理类的通知器链中
		 */
		if (bean instanceof Advised advised) {
			//isFrozen判断frozen属性，也就是是否需要优化CGLIB，默认false
			//isEligible判断给定的类是否有资格应用此后处理器的advisor
			if (!advised.isFrozen() && isEligible(AopUtils.getTargetClass(bean))) {
				//如果beforeExistingAdvisors为true，异步任务的AsyncAnnotationBeanPostProcessor将会设置为true
				if (this.beforeExistingAdvisors) {
					//将我们的本地通知器添加到现有代理的通知器链的头部
					advised.addAdvisor(0, this.advisor);
				} else {
					//否则添加到尾部
					advised.addAdvisor(this.advisor);
				}
				//返回原代理对象
				return bean;
			}
		}
		//如果当前bean支持被增强，那么进行增强
		if (isEligible(bean, beanName)) {
			//通过给定的 bean 准备一个代理工厂，用于创建代理对象
			ProxyFactory proxyFactory = prepareProxyFactory(bean, beanName);
			//如果proxyTargetClass属性为false，即不是采用CGLIB的代理
			if (!proxyFactory.isProxyTargetClass()) {
				/*
				 * 评估需要代理的接口，添加到proxyFactory中
				 * 如果没有至少一个合理的代理接口，那么仍然会走基于类的CGLIB代理
				 *
				 * AbstractAutoProxyCreator中也有该方法，该方法我们在AspectJAwareAdvisorAutoProxyCreator源码部分就讲过了，在此不再赘述：
				 * 如果当前接口不是一个容器回调接口（isConfigurationCallbackInterface返回false），
				 * 并且当前接口不是内部语言接口（isInternalLanguageInterface返回false），
				 * 并且接口方法个数至少为1个（不是标志性接口）。同时满足上面三个条件，当前接口就是一个合理的代理接口。
				 */
				evaluateProxyInterfaces(bean.getClass(), proxyFactory);
			}
			//当前的本地advisor添加到proxyFactory的advisors属性集合中
			proxyFactory.addAdvisor(this.advisor);
			//继续自定义ProxyFactory钩子方法，默认空实现，留给子类实现
			//目前版本还没有内置的子类实现这个方法
			customizeProxyFactory(proxyFactory);
			//最终通过代理工厂创建一个代理对象，该方法我们在AspectJAwareAdvisorAutoProxyCreator源码部分就讲过了，在此不再赘述
			//可能会通过JdkDynamicAopProxy创建或者ObjenesisCglibAopProxy创建
			return proxyFactory.getProxy(getProxyClassLoader());
		}

		//无需代理，返回原始对象
		return bean;
	}

	/**
	 * Check whether the given bean is eligible for advising with this
	 * post-processor's {@link Advisor}.
	 * <p>Delegates to {@link #isEligible(Class)} for target class checking.
	 * Can be overridden e.g. to specifically exclude certain beans by name.
	 * <p>Note: Only called for regular bean instances but not for existing
	 * proxy instances which implement {@link Advised} and allow for adding
	 * the local {@link Advisor} to the existing proxy's {@link Advisor} chain.
	 * For the latter, {@link #isEligible(Class)} is being called directly,
	 * with the actual target class behind the existing proxy (as determined
	 * by {@link AopUtils#getTargetClass(Object)}).
	 * 判断给定的类是否有资格应用此后处理器的advisor
	 * @param bean the bean instance
	 * @param beanName the name of the bean
	 * @see #isEligible(Class)
	 */
	protected boolean isEligible(Object bean, String beanName) {
		return isEligible(bean.getClass());
	}

	/**
	 * Check whether the given class is eligible for advising with this
	 * post-processor's {@link Advisor}.
	 * 判断给定的类是否有资格应用此后处理器的advisor
	 * <p>Implements caching of {@code canApply} results per bean target class.
	 * @param targetClass the class to check against
	 * @see AopUtils#canApply(Advisor, Class)
	 */
	protected boolean isEligible(Class<?> targetClass) {
		//如果缓存中存在该类型的判断，那么直接从缓存中获取结果
		Boolean eligible = this.eligibleBeans.get(targetClass);
		if (eligible != null) {
			return eligible;
		}
		//如果advisor为null，那么直接返回null
		if (this.advisor == null) {
			return false;
		}
		//通过AopUtils.canApply来判断当前Class是否可被当前advisor增强
		//简单的说就是检查Class否符合advisor中的切入规则，
		eligible = AopUtils.canApply(this.advisor, targetClass);
		//存入eligibleBeans缓存
		this.eligibleBeans.put(targetClass, eligible);
		return eligible;
	}

	/**
	 * Prepare a {@link ProxyFactory} for the given bean.
	 * <p>Subclasses may customize the handling of the target instance and in
	 * particular the exposure of the target class. The default introspection
	 * of interfaces for non-target-class proxies and the configured advisor
	 * will be applied afterwards; {@link #customizeProxyFactory} allows for
	 * late customizations of those parts right before proxy creation.
	 * 通过给定的 bean 准备一个代理工厂，用于创建代理对象
	 * 子类可重写该方法，自定义代理工厂的创建逻辑，比如暴露代理对象
	 * @param bean the bean instance to create a proxy for
	 * @param beanName the corresponding bean name
	 * @return the ProxyFactory, initialized with this processor's
	 * {@link ProxyConfig} settings and the specified bean
	 * @since 4.2.3
	 * @see #customizeProxyFactory
	 */
	protected ProxyFactory prepareProxyFactory(Object bean, String beanName) {
		//新建一个ProxyFactory代理工厂对象，用于创建代理
		ProxyFactory proxyFactory = new ProxyFactory();
		//从当前AbstractAdvisingBeanPostProcessor拷贝属性，实际上就是拷贝ProxyConfig内部的几个属性
		proxyFactory.copyFrom(this);
		//将给定对象实例设置为目标源
		proxyFactory.setTarget(bean);
		return proxyFactory;
	}


	/**
	 * Subclasses may choose to implement this: for example,
	 * to change the interfaces exposed.
	 * <p>The default implementation is empty.
	 * @param proxyFactory the ProxyFactory that is already configured with
	 * target, advisor and interfaces and will be used to create the proxy
	 * immediately after this method returns
	 * @since 4.2.3
	 * @see #prepareProxyFactory
	 */
	protected void customizeProxyFactory(ProxyFactory proxyFactory) {
	}

}
