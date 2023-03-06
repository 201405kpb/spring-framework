/*
 * Copyright 2002-2023 the original author or authors.
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

package org.springframework.context.event;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.framework.autoproxy.AutoProxyUtils;
import org.springframework.aop.scope.ScopedObject;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;

/**
 * Registers {@link EventListener} methods as individual {@link ApplicationListener} instances.
 * Implements {@link BeanFactoryPostProcessor} (as of 5.1) primarily for early retrieval,
 * avoiding AOP checks for this processor bean and its {@link EventListenerFactory} delegates.
 *
 * @author Stephane Nicoll
 * @author Juergen Hoeller
 * @author Sebastien Deleuze
 * @since 4.2
 * @see EventListenerFactory
 * @see DefaultEventListenerFactory
 */
public class EventListenerMethodProcessor
		implements SmartInitializingSingleton, ApplicationContextAware, BeanFactoryPostProcessor {

	protected final Log logger = LogFactory.getLog(getClass());

	@Nullable
	private ConfigurableApplicationContext applicationContext;

	@Nullable
	private ConfigurableListableBeanFactory beanFactory;

	/**
	 * 真正用于包装@EventListener方法为一个ApplicationListener的EventListenerFactory
	 */
	@Nullable
	private List<EventListenerFactory> eventListenerFactories;
	/**
	 * 用于解析SPEL表达式的评估器
	 */
	private final EventExpressionEvaluator evaluator = new EventExpressionEvaluator();

	/**
	 * 类型检查的缓存
	 */
	private final Set<Class<?>> nonAnnotatedClasses = Collections.newSetFromMap(new ConcurrentHashMap<>(64));


	/**
	 * 该回调方法在EventListenerMethodProcessor本身被实例化之后回调，用于设置applicationContext
	 */
	@Override
	public void setApplicationContext(ApplicationContext applicationContext) {
		Assert.isTrue(applicationContext instanceof ConfigurableApplicationContext,
				"ApplicationContext does not implement ConfigurableApplicationContext");
		this.applicationContext = (ConfigurableApplicationContext) applicationContext;
	}

	/**
	 * 该回调方法是在注册BeanPostProcessor以及实例化普通Bean之前完成的
	 * <p>
	 * 该方法用于查找并初始化所有的EventListenerFactory类型的bean
	 * EventListenerFactory可用于解析对应的@EventListener注解方法，并返回一个ApplicationListener
	 */
	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
		//查找并初始化所有EventListenerFactory类型的bean，key为beanName，value为bean实例
		Map<String, EventListenerFactory> beans = beanFactory.getBeansOfType(EventListenerFactory.class, false, false);
		//获取所有的EventListenerFactory
		List<EventListenerFactory> factories = new ArrayList<>(beans.values());
		//通过AnnotationAwareOrderComparator对其进行排序，因此支持Ordered、PriorityOrdered接口，以及@Order、@Priority注解的排序
		AnnotationAwareOrderComparator.sort(factories);
		//保存起来
		this.eventListenerFactories = factories;
	}


	/**
	 * 该回调方法在所有非延迟初始化的单例bean初始化完毕之后才会进行回调，属于一个非常晚期的回调了
	 */
	@Override
	public void afterSingletonsInstantiated() {
		ConfigurableListableBeanFactory beanFactory = this.beanFactory;
		Assert.state(this.beanFactory != null, "No ConfigurableListableBeanFactory set");
		//匹配的类型是Object，因此会获取Spring管理的所有的beanName。看起来不是很优雅的样子……
		String[] beanNames = beanFactory.getBeanNamesForType(Object.class);
		//遍历
		for (String beanName : beanNames) {
			//确定是否是作用域代理
			if (!ScopedProxyUtils.isScopedTarget(beanName)) {
				Class<?> type = null;
				try {
					//获取bean的原始的类型（可能被代理了）
					type = AutoProxyUtils.determineTargetClass(beanFactory, beanName);
				} catch (Throwable ex) {
					// An unresolvable bean type, probably from a lazy bean - let's ignore it.
					if (logger.isDebugEnabled()) {
						logger.debug("Could not resolve target class for bean with name '" + beanName + "'", ex);
					}
				}
				if (type != null) {
					//作用域对象的兼容处理
					if (ScopedObject.class.isAssignableFrom(type)) {
						try {
							Class<?> targetClass = AutoProxyUtils.determineTargetClass(
									beanFactory, ScopedProxyUtils.getTargetBeanName(beanName));
							if (targetClass != null) {
								type = targetClass;
							}
						} catch (Throwable ex) {
							// An invalid scoped proxy arrangement - let's ignore it.
							if (logger.isDebugEnabled()) {
								logger.debug("Could not resolve target bean for scoped proxy '" + beanName + "'", ex);
							}
						}
					}
					try {
						/*
						 * 最关键的方法，真正的处理bean中的@EventListener方法
						 */
						processBean(beanName, type);
					} catch (Throwable ex) {
						throw new BeanInitializationException("Failed to process @EventListener " +
								"annotation on bean with name '" + beanName + "'", ex);
					}
				}
			}
		}
	}

	/**
	 * 处理bean中的@EventListener方法，一个方法封装为一个ApplicationListenerMethodAdapter对象
	 */
	private void processBean(final String beanName, final Class<?> targetType) {
		//如果nonAnnotatedClasses缓存中不包含当前类型，并且给定类是承载指定EventListener注解的候选项
		//并且不是spring容器的内部bean，如果类路径以"org.springframework."开头，并且没有被@Component标注，那么就是spring容器内部的类
		//以上条件满足，当前class可以被用来查找@EventListener注解方法
		if (!this.nonAnnotatedClasses.contains(targetType) &&
				AnnotationUtils.isCandidateClass(targetType, EventListener.class) &&
				!isSpringContainerClass(targetType)) {

			Map<Method, EventListener> annotatedMethods = null;
			try {
				//查找当前class内部的具有@EventListener注解的方法
				annotatedMethods = MethodIntrospector.selectMethods(targetType,
						(MethodIntrospector.MetadataLookup<EventListener>) method ->
								AnnotatedElementUtils.findMergedAnnotation(method, EventListener.class));
			} catch (Throwable ex) {
				// An unresolvable type in a method signature, probably from a lazy bean - let's ignore it.
				if (logger.isDebugEnabled()) {
					logger.debug("Could not resolve methods for bean with name '" + beanName + "'", ex);
				}
			}
			//如果此类没有@EventListener注解的方法，那么记入nonAnnotatedClasses缓存，下一次预检此类型则直接跳过
			if (CollectionUtils.isEmpty(annotatedMethods)) {
				this.nonAnnotatedClasses.add(targetType);
				if (logger.isTraceEnabled()) {
					logger.trace("No @EventListener annotations found on bean class: " + targetType.getName());
				}
				//如果此类拥有@EventListener注解的方法
			} else {
				// Non-empty set of methods
				ConfigurableApplicationContext context = this.applicationContext;
				Assert.state(context != null, "No ApplicationContext set");
				//获取此前所有的EventListenerFactory
				List<EventListenerFactory> factories = this.eventListenerFactories;
				Assert.state(factories != null, "EventListenerFactory List not initialized");
				//遍历所有具有该注解的方法
				for (Method method : annotatedMethods.keySet()) {
					//遍历EventListenerFactory
					for (EventListenerFactory factory : factories) {
						//如果此factory支持解析当前方法
						if (factory.supportsMethod(method)) {
							//根据对象的类型（可能是代理对象类型），解析当前方法成为一个可执行方法
							//如果方法是私有的，并且方法不是静态的，并且当前类型是一个Spring通用的代理类型，那么将会抛出异常
							//普通Spring AOP、事务、@Async等创建的代理都是SpringProxy类型，而@Configuration代理就不是SpringProxy类型
							//如果当前类型是JDK代理，并且方法不是从从代理的接口中实现的，那么同样会抛出异常
							Method methodToUse = AopUtils.selectInvocableMethod(method, context.getType(beanName));
							//调用factory#createApplicationListener方法根据给定的method创建一个ApplicationListener
							//默认情况下这里的ApplicationListener实际上是一个ApplicationListenerMethodAdapter，这是适配器模式
							ApplicationListener<?> applicationListener =
									factory.createApplicationListener(beanName, targetType, methodToUse);
							//如果属于ApplicationListenerMethodAdapter，那么还要进行初始化
							//主要是设置一个EventExpressionEvaluator，用于解析@EventListener中的condition属性的 SpEL 表达式
							if (applicationListener instanceof ApplicationListenerMethodAdapter alma) {
								alma.init(context, this.evaluator);
							}
							//将方法转换成的ApplicationListenerMethodAdapter同样添加到applicationEventMulticaster内部的
							//defaultRetriever的applicationListeners集合中
							context.addApplicationListener(applicationListener);
							//如果有一个factory能够解析成功，那么不在应用后续的factory，结束循环，解析结束
							break;
						}
					}
				}
				if (logger.isDebugEnabled()) {
					logger.debug(annotatedMethods.size() + " @EventListener methods processed on bean '" +
							beanName + "': " + annotatedMethods);
				}
			}
		}
	}

	/**
	 * Determine whether the given class is an {@code org.springframework}
	 * bean class that is not annotated as a user or test {@link Component}...
	 * which indicates that there is no {@link EventListener} to be found there.
	 * @since 5.1
	 */
	private static boolean isSpringContainerClass(Class<?> clazz) {
		return (clazz.getName().startsWith("org.springframework.") &&
				!AnnotatedElementUtils.isAnnotated(ClassUtils.getUserClass(clazz), Component.class));
	}

}
