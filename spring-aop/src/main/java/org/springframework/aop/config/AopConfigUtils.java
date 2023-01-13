/*
 * Copyright 2002-2018 the original author or authors.
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

package org.springframework.aop.config;

import org.springframework.aop.aspectj.annotation.AnnotationAwareAspectJAutoProxyCreator;
import org.springframework.aop.aspectj.autoproxy.AspectJAwareAdvisorAutoProxyCreator;
import org.springframework.aop.framework.autoproxy.InfrastructureAdvisorAutoProxyCreator;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.Ordered;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for handling registration of AOP auto-proxy creators.
 *
 * <p>Only a single auto-proxy creator should be registered yet multiple concrete
 * implementations are available. This class provides a simple escalation protocol,
 * allowing a caller to request a particular auto-proxy creator and know that creator,
 * <i>or a more capable variant thereof</i>, will be registered as a post-processor.
 *
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @author Mark Fisher
 * @since 2.5
 * @see AopNamespaceUtils
 */
public abstract class AopConfigUtils {

	/**
	 * The bean name of the internally managed auto-proxy creator.
	 * Spring内部管理的自动代理创建者的 beanName
	 */
	public static final String AUTO_PROXY_CREATOR_BEAN_NAME =
			"org.springframework.aop.config.internalAutoProxyCreator";

	/**
	 * Stores the auto proxy creator classes in escalation order.
	 * 按升序顺序存储的自动代理创建者的类型集合
	 * 可以看到，默认有三种类型，优先级就是比较索引顺序的大小，因此优先级为：
	 * InfrastructureAdvisorAutoProxyCreator < AspectJAwareAdvisorAutoProxyCreator < AnnotationAwareAspectJAutoProxyCreator
	 * 如果是解析<tx:annotation-driven />标签或者@EnableTransactionManagement事物注解，那么cls参数是InfrastructureAdvisorAutoProxyCreator.class
	 * 如果是解析<aop:config />标签，那么cls参数是AspectJAwareAdvisorAutoProxyCreator.class
	 * 如果是解析<aop:aspectj-autoproxy />标签或者@EnableAspectJAutoProxy注解，那么cls参数是AnnotationAwareAspectJAutoProxyCreator.class
	 */
	private static final List<Class<?>> APC_PRIORITY_LIST = new ArrayList<>(3);

	static {
		// Set up the escalation list...
		//按先后顺序添加三种类型
		APC_PRIORITY_LIST.add(InfrastructureAdvisorAutoProxyCreator.class);
		APC_PRIORITY_LIST.add(AspectJAwareAdvisorAutoProxyCreator.class);
		APC_PRIORITY_LIST.add(AnnotationAwareAspectJAutoProxyCreator.class);
	}


	/**
	 *  如有必要，注册自动代理创建者，类型为AspectJAwareAdvisorAutoProxyCreator.class
	 */
	@Nullable
	public static BeanDefinition registerAutoProxyCreatorIfNecessary(BeanDefinitionRegistry registry) {
		/**
		 * 继续调用registerOrEscalateApcAsRequired方法
		 * 由于解析的<aop:config />标签，因此第一个参数是AspectJAwareAdvisorAutoProxyCreator.class
		 */
		return registerAutoProxyCreatorIfNecessary(registry, null);
	}

	@Nullable
	public static BeanDefinition registerAutoProxyCreatorIfNecessary(
			BeanDefinitionRegistry registry, @Nullable Object source) {

		return registerOrEscalateApcAsRequired(InfrastructureAdvisorAutoProxyCreator.class, registry, source);
	}

	@Nullable
	public static BeanDefinition registerAspectJAutoProxyCreatorIfNecessary(BeanDefinitionRegistry registry) {
		return registerAspectJAutoProxyCreatorIfNecessary(registry, null);
	}

	@Nullable
	public static BeanDefinition registerAspectJAutoProxyCreatorIfNecessary(
			BeanDefinitionRegistry registry, @Nullable Object source) {

		return registerOrEscalateApcAsRequired(AspectJAwareAdvisorAutoProxyCreator.class, registry, source);
	}

	@Nullable
	public static BeanDefinition registerAspectJAnnotationAutoProxyCreatorIfNecessary(BeanDefinitionRegistry registry) {
		return registerAspectJAnnotationAutoProxyCreatorIfNecessary(registry, null);
	}

	@Nullable
	public static BeanDefinition registerAspectJAnnotationAutoProxyCreatorIfNecessary(
			BeanDefinitionRegistry registry, @Nullable Object source) {
		/*
		 * 继续调用registerOrEscalateApcAsRequired方法
		 * 由于解析的<aop:aspectj-autoproxy/>标签，因此第一个参数是AnnotationAwareAspectJAutoProxyCreator.class
		 */
		return registerOrEscalateApcAsRequired(AnnotationAwareAspectJAutoProxyCreator.class, registry, source);
	}

	/**
	 * 强迫AutoProxyCreator使用基于类的代理，也就是CGLIB代理
	 * @param registry 注册表
	 */
	public static void forceAutoProxyCreatorToUseClassProxying(BeanDefinitionRegistry registry) {
		//如果包含名为"org.springframework.aop.config.internalAutoProxyCreator"的bean定义
		if (registry.containsBeanDefinition(AUTO_PROXY_CREATOR_BEAN_NAME)) {
			//那么获取该bean定义
			BeanDefinition definition = registry.getBeanDefinition(AUTO_PROXY_CREATOR_BEAN_NAME);
			//添加属性proxyTargetClass设置值为true，表示强制使用CGLIB代理
			definition.getPropertyValues().add("proxyTargetClass", Boolean.TRUE);
		}
	}

	/**
	 * 强迫AutoProxyCreator暴露代理对象
	 * @param registry 注册表
	 */
	public static void forceAutoProxyCreatorToExposeProxy(BeanDefinitionRegistry registry) {
		//如果包含名为"org.springframework.aop.config.internalAutoProxyCreator"的bean定义
		if (registry.containsBeanDefinition(AUTO_PROXY_CREATOR_BEAN_NAME)) {
			//那么获取该bean定义
			BeanDefinition definition = registry.getBeanDefinition(AUTO_PROXY_CREATOR_BEAN_NAME);
			//添加属性exposeProxy，设置值为true，表示强制暴露代理对象
			definition.getPropertyValues().add("exposeProxy", Boolean.TRUE);
		}
	}

	/**
	 * 注册或者修改自动代理创建者的bean定义
	 * @param cls  自动代理创建者的class，用于比较优先级或者创建bean定义
	 * @param registry 注册表
	 * @param source 数据源
	 * @return
	 */
	@Nullable
	private static BeanDefinition registerOrEscalateApcAsRequired(
			Class<?> cls, BeanDefinitionRegistry registry, @Nullable Object source) {

		Assert.notNull(registry, "BeanDefinitionRegistry must not be null");

		//  如果 `org.springframework.aop.config.internalAutoProxyCreator` 已注册
		if (registry.containsBeanDefinition(AUTO_PROXY_CREATOR_BEAN_NAME)) {
			// 获取对应的 BeanDefinition 对象
			BeanDefinition apcDefinition = registry.getBeanDefinition(AUTO_PROXY_CREATOR_BEAN_NAME);
			// 如果已注册的 `internalAutoProxyCreator` 和入参的 Class 不相等，说明可能是继承关系
			if (!cls.getName().equals(apcDefinition.getBeanClassName())) {
				// 获取已注册的 `internalAutoProxyCreator` 的优先级，实际上就是存储在APC_PRIORITY_LIST集合的索引位置
				int currentPriority = findPriorityForClass(apcDefinition.getBeanClassName());
				//  获取需要注册的 `internalAutoProxyCreator` 的优先级，实际上就是存储在APC_PRIORITY_LIST集合的索引位置
				int requiredPriority = findPriorityForClass(cls);
				// InfrastructureAdvisorAutoProxyCreator < AspectJAwareAdvisorAutoProxyCreator < AnnotationAwareAspectJAutoProxyCreator
				// 三者都是 AbstractAutoProxyCreator 自动代理对象的子类
				//如果bean定义中的自动代理创建者的类型优先级 小于 当前参数传递的自动代理创建者的类型优先级
				if (currentPriority < requiredPriority) {
					//那么bean定义的beanClass属性设置为使用 当前参数传递的自动代理创建者的类型的className，即升级bean定义
					apcDefinition.setBeanClassName(cls.getName());
				}
			}
			//  因为已注册，则返回 `null`
			return null;
		}

		// 没有注册，则创建一个 RootBeanDefinition 对象进行注册
		RootBeanDefinition beanDefinition = new RootBeanDefinition(cls);
		//  设置来源
		beanDefinition.setSource(source);
		// 设置为最高优先级
		beanDefinition.getPropertyValues().add("order", Ordered.HIGHEST_PRECEDENCE);
		//  设置角色为**ROLE_INFRASTRUCTURE**，表示是 Spring 框架内部的 Bean
		beanDefinition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
		// 注册自动代理的 Bean，名称为 `org.springframework.aop.config.internalAutoProxyCreator`
		registry.registerBeanDefinition(AUTO_PROXY_CREATOR_BEAN_NAME, beanDefinition);
		// 返回刚注册的 RootBeanDefinition 对象
		return beanDefinition;
	}

	private static int findPriorityForClass(Class<?> clazz) {
		return APC_PRIORITY_LIST.indexOf(clazz);
	}

	/**
	 * 返回当前类型的自动代理创建者的优先级，实际上就是存储的索引位置
	 * @param className
	 * @return
	 */
	private static int findPriorityForClass(@Nullable String className) {
		for (int i = 0; i < APC_PRIORITY_LIST.size(); i++) {
			Class<?> clazz = APC_PRIORITY_LIST.get(i);
			if (clazz.getName().equals(className)) {
				//返回索引
				return i;
			}
		}
		//没找到就抛出异常
		throw new IllegalArgumentException(
				"Class name [" + className + "] is not a known auto-proxy creator class");
	}

}
