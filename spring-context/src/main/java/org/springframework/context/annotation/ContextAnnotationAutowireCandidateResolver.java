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

package org.springframework.context.annotation;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.QualifierAnnotationAutowireCandidateResolver;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Complete implementation of the
 * {@link org.springframework.beans.factory.support.AutowireCandidateResolver} strategy
 * interface, providing support for qualifier annotations as well as for lazy resolution
 * driven by the {@link Lazy} annotation in the {@code context.annotation} package.
 *
 * @author Juergen Hoeller
 * @since 4.0
 */
public class ContextAnnotationAutowireCandidateResolver extends QualifierAnnotationAutowireCandidateResolver {

	@Override
	@Nullable
	public Object getLazyResolutionProxyIfNecessary(DependencyDescriptor descriptor, @Nullable String beanName) {
		return (isLazy(descriptor) ? buildLazyResolutionProxy(descriptor, beanName) : null);
	}

	@Override
	@Nullable
	public Class<?> getLazyResolutionProxyClass(DependencyDescriptor descriptor, @Nullable String beanName) {
		return (isLazy(descriptor) ? (Class<?>) buildLazyResolutionProxy(descriptor, beanName, true) : null);
	}

	/**
	 * @param descriptor 依赖项的描述符，包含MethodParameter的信息
	 * @return 是否设置了延迟加载
	 */
	protected boolean isLazy(DependencyDescriptor descriptor) {
		//获取、遍历descriptor中包装的字段，或者方法/构造器的对应参数上关联的注解
		for (Annotation ann : descriptor.getAnnotations()) {
			//是否含有@Lazy注解
			Lazy lazy = AnnotationUtils.getAnnotation(ann, Lazy.class);
			//如果具有@Lazy注解，并且设置为true，那么返回true
			if (lazy != null && lazy.value()) {
				return true;
			}
		}
		//获取包装的MethodParameter，即方法/构造器参数
		MethodParameter methodParam = descriptor.getMethodParameter();
		if (methodParam != null) {
			//获取方法，如果参数属于构造器那么返回null
			Method method = methodParam.getMethod();
			//如果method为null或者返回值为null
			//表示如果是构造器，或者是方法但是返回值为void，那么符合要求，可以进一步尝试
			if (method == null || void.class == method.getReturnType()) {
				//获取方法或者构造器上的@Lazy注解
				Lazy lazy = AnnotationUtils.getAnnotation(methodParam.getAnnotatedElement(), Lazy.class);
				//如果具有@Lazy注解，并且设置为true，那么返回true
				if (lazy != null && lazy.value()) {
					return true;
				}
			}
		}
		//返回false
		return false;
	}

	protected Object buildLazyResolutionProxy(DependencyDescriptor descriptor, @Nullable String beanName) {
		return buildLazyResolutionProxy(descriptor, beanName, false);
	}

	/**
	 * 构建延迟加载的代理对象
	 * @param descriptor 依赖项的描述符，包含MethodParameter的信息
	 * @param beanName
	 * @param classOnly
	 * @return
	 */
	private Object buildLazyResolutionProxy(
			final DependencyDescriptor descriptor, final @Nullable String beanName, boolean classOnly) {

		BeanFactory beanFactory = getBeanFactory();
		Assert.state(beanFactory instanceof DefaultListableBeanFactory,
				"BeanFactory needs to be a DefaultListableBeanFactory");
		final DefaultListableBeanFactory dlbf = (DefaultListableBeanFactory) beanFactory;
		//AOP的核心对象之一TargetSource，它表示"目标源"，包装了目标对象（被代理的对象）
		TargetSource ts = new TargetSource() {
			@Override
			public Class<?> getTargetClass() {
				return descriptor.getDependencyType();
			}
			@Override
			public boolean isStatic() {
				return false;
			}

			//获取目标对象，在代理中就是通过该方法获取目标对象并调用目标对象的方法的
			@Override
			public Object getTarget() {
				//同样调用beanFactory.doResolveDependency方法去解析依赖的对象，获取目标对象，这里返回的是真正的Spring bean对象
				Set<String> autowiredBeanNames = (beanName != null ? new LinkedHashSet<>(1) : null);
				Object target = dlbf.doResolveDependency(descriptor, beanName, autowiredBeanNames, null);
				if (target == null) {
					Class<?> type = getTargetClass();
					if (Map.class == type) {
						return Collections.emptyMap();
					}
					else if (List.class == type) {
						return Collections.emptyList();
					}
					else if (Set.class == type || Collection.class == type) {
						return Collections.emptySet();
					}
					throw new NoSuchBeanDefinitionException(descriptor.getResolvableType(),
							"Optional dependency not present for lazy injection point");
				}
				if (autowiredBeanNames != null) {
					for (String autowiredBeanName : autowiredBeanNames) {
						if (dlbf.containsBean(autowiredBeanName)) {
							dlbf.registerDependentBean(autowiredBeanName, beanName);
						}
					}
				}
				return target;
			}
			@Override
			public void releaseTarget(Object target) {
			}
		};

		//创建代理工厂
		ProxyFactory pf = new ProxyFactory();
		//设置目标源，从目标源中获取代理目标实例
		pf.setTargetSource(ts);
		//获取依赖类型
		Class<?> dependencyType = descriptor.getDependencyType();
		//如果是接口，那么加入到interfaces集合中，后面就可能会使用JDK动态代理
		if (dependencyType.isInterface()) {
			pf.addInterface(dependencyType);
		}
		//通过ProxyFactory生成代理对象，根据情况使用JDK代理或者CGLIB代理
		ClassLoader classLoader = dlbf.getBeanClassLoader();
		return (classOnly ? pf.getProxyClass(classLoader) : pf.getProxy(classLoader));
	}

}
