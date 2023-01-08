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

import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.QualifierAnnotationAutowireCandidateResolver;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Complete implementation of the
 * {@link org.springframework.beans.factory.support.AutowireCandidateResolver} strategy
 * interface, providing support for qualifier annotations as well as for lazy resolution
 * driven by the {@link Lazy} annotation in the {@code context.annotation} package.
 * <p>{@link org.springframework.beans.factory.support.AutowireCandidateResolver}策略接口的
 * 完整实现，提供对限定符注释以及由context.annoation包中的@Lazy注解驱动的延迟解析支持</p>
 * @author Juergen Hoeller
 * @since 4.0
 */
public class ContextAnnotationAutowireCandidateResolver extends QualifierAnnotationAutowireCandidateResolver {

	/**
	 * 如有必要,获取惰性解析代理
	 * @param descriptor 目标方法参数或字段描述符
	 * @param beanName 包含注入点的bean名
	 * @return 实际依赖关系目标的惰性解决方案代理；如果要执行直接解决方案，则为null
	 */
	@Override
	@Nullable
	public Object getLazyResolutionProxyIfNecessary(DependencyDescriptor descriptor, @Nullable String beanName) {
		//如果desciptor指定了懒加载,就会建立延迟解析代理对象然后返回出去,否则返回null
		return (isLazy(descriptor) ? buildLazyResolutionProxy(descriptor, beanName) : null);
	}

	/**
	 * desciptor是否指定了懒加载:
	 * <ol>
	 *  <li>【<b>检查decriptord所封装的Field/MethodParamater对象有没有@Lazy注解</b>】:
	 *   <ol>
	 *    <li>遍历decriptord的所有注解,元素为ann:
	 *     <ol>
	 *      <li>从an中取出@Lazy对象【变量 lazy】</li>
	 *      <li>如果有lazy且lazy的值为true，就返回true,表示decriptor指定了懒加载</li>
	 *     </ol>
	 *    </li>
	 *   </ol>
	 *  </li>
	 *  <li>【<b>检查decriptord所封装的MethodParamater对象所属的Method上有没有@Lazy注解</b>】:
	 *   <ol>
	 *    <li>从descriptor中获取方法参数【变量 methodParam】</li>
	 *    <li>如果有方法参数
	 *     <ol>
	 *      <li>获取methodParam所属方法【变量 method】</li>
	 *      <li>如果method是构造函数 或者 method是无返回值方法:
	 *       <ol>
	 *        <li>从methodParam所属的Method对象的注解中获取@Lazy注解对象</li>
	 *        <li>如果有lazy且lazy的值为true，就返回true,表示decriptor指定了懒加载</li>
	 *       </ol>
	 *      </li>
	 *     </ol>
	 *    </li>
	 *   </ol>
	 *  </li>
	 *  <li>如果descriptor所包含的对象没有加上@Lazy注解或者@Lazy没有指定成懒加载，就返回false,
	 *  表示没有指定了懒加载</li>
	 * </ol>
	 */
	protected boolean isLazy(DependencyDescriptor descriptor) {
		//遍历decriptord的所有注解
		for (Annotation ann : descriptor.getAnnotations()) {
			//从an中取出@Lazy对象
			Lazy lazy = AnnotationUtils.getAnnotation(ann, Lazy.class);
			//如果有lazy且lazy的值为true，就返回true,表示decriptor指定了懒加载
			if (lazy != null && lazy.value()) {
				return true;
			}
		}
		//从descriptor中获取方法参数
		MethodParameter methodParam = descriptor.getMethodParameter();
		//如果有方法参数
		if (methodParam != null) {
			//获取methodParam所属方法
			Method method = methodParam.getMethod();
			//method==null表示构造函数
			//如果method是构造函数 或者 method是无返回值方法
			if (method == null || void.class == method.getReturnType()) {
				//从methodParam所属的Method对象的注解中获取@Lazy注解对象
				Lazy lazy = AnnotationUtils.getAnnotation(methodParam.getAnnotatedElement(), Lazy.class);
				//如果有lazy且lazy的值为true，就返回true,表示decriptor指定了懒加载
				if (lazy != null && lazy.value()) {
					return true;
				}
			}
		}
		//如果descriptor所包含的对象没有加上@Lazy注解或者@Lazy没有指定成懒加载，就返回false,表示没有指定了懒加载
		return false;
	}

	/**
	 * 建立延迟解析代理:
	 * <ol>
	 *  <li>如果bean工厂不是DefaultLisableBeanFactory的实例,抛出异常</li>
	 *  <li>将bean工厂强转为DefaultLisableBeanFactory对象【变量 beanFactory】</li>
	 *  <li>新建一个TargetSource对象，用于封装目标对象【变量 ts】:
	 *   <ol>
	 *    <li>getTargetClass():要返回的目标类型为descriptor的依赖类型</li>
	 *    <li>isStatic():所有对getTarget()的调用都不需要返回相同的对象</li>
	 *    <li>getTarget():
	 *     <ol>
	 *      <li>使用bean工厂解析出descriptor所指定的beanName的bean对象作为目标对象【变量 target】</li>
	 *      <li>如果目标对象【target】为null:
	 *       <ol>
	 *        <li>获取目标对象的类型【变量 type】</li>
	 *        <li>如果type是Mapp类型,返回空Map</li>
	 *        <li>如果type为List类型,返回空List</li>
	 *        <li>如果type为Set类型或者Collection类型</li>
	 *        <li>其他情况抛出无此类BeanDefinition异常：延迟注入点不存在可依赖项</li>
	 *       </ol>
	 *      </li>
	 *      <li>返回目标对象【target】</li>
	 *     </ol>
	 *    </li>
	 *    <li>releaseTarget():空实现</li>
	 *   </ol>
	 *  </li>
	 *  <li>新建一个代理工厂【{@link ProxyFactory}】对象【变量 pf】</li>
	 *  <li>设置Pf的目标对象为ts</li>
	 *  <li>获取desciptor所包装的对象的类型【变量 dependencyType】</li>
	 *  <li>如果dependencyType是接口,设置pf的接口为dependencyType</li>
	 *  <li>使用bean工厂的bean类加载器来使pf创建一下新的代理对象</li>
	 * </ol>
	 * @param descriptor 目标方法参数或字段描述符
	 * @param beanName 包含注入点的bean名
	 * @return 实际依赖关系目标的惰性解决方案代理；如果要执行直接解决方案，则为null
	 */
	protected Object buildLazyResolutionProxy(final DependencyDescriptor descriptor, final @Nullable String beanName) {
		//如果bean工厂不是DefaultLisableBeanFactory的实例,抛出异常
		Assert.state(getBeanFactory() instanceof DefaultListableBeanFactory,
				"BeanFactory needs to be a DefaultListableBeanFactory");
		//将bean工厂强转为DefaultLisableBeanFactory对象
		final DefaultListableBeanFactory beanFactory = (DefaultListableBeanFactory) getBeanFactory();
		//TargetSource：被代理的target(目标对象)实例的来源
		// 新建一个TargetSource对象，用于封装目标对象
		TargetSource ts = new TargetSource() {
			@Override
			public Class<?> getTargetClass() {
				//要返回的目标类型为descriptor的依赖类型
				return descriptor.getDependencyType();
			}
			@Override
			public boolean isStatic() {
				//所有对getTarget()的调用都不需要返回相同的对象
				return false;
			}
			@Override
			public Object getTarget() {
				//使用bean工厂解析出descriptor所指定的beanName的bean对象作为目标对象
				Object target = beanFactory.doResolveDependency(descriptor, beanName, null, null);
				//如果目标对象为null
				if (target == null) {
					//获取目标对象的类型
					Class<?> type = getTargetClass();
					//如果目标对象是Mapp类型
					if (Map.class == type) {
						//返回空Map
						return Collections.emptyMap();
					}
					//如果目标对象为List类型
					else if (List.class == type) {
						//返回空List
						return Collections.emptyList();
					}
					//如果目标对象为Set类型或者Collection类型
					else if (Set.class == type || Collection.class == type) {
						//返回空Set
						return Collections.emptySet();
					}
					//其他情况抛出无此类BeanDefinition异常：延迟注入点不存在可依赖项
					throw new NoSuchBeanDefinitionException(descriptor.getResolvableType(),
							"Optional dependency not present for lazy injection point");
				}
				//返回目标对象
				return target;
			}
			@Override
			public void releaseTarget(Object target) {
			}
		};
		//ProxyFactory:用于AOP代理的工厂,以编程方式使用,而不是通过bean工厂中的声明性设置.此类提供了
		// 	一种在自定义用户代码中获取和配置AOP代理实例的简单方法
		//新建一个代理工厂对象
		ProxyFactory pf = new ProxyFactory();
		//设置Pf的目标对象为ts
		pf.setTargetSource(ts);
		//获取desciptor所包装的对象的类型
		Class<?> dependencyType = descriptor.getDependencyType();
		//如果依赖类型是接口
		if (dependencyType.isInterface()) {
			//设置pf的接口为dependencyType
			pf.addInterface(dependencyType);
		}
		//使用bean工厂的bean类加载器来使pf创建一下新的代理对象
		return pf.getProxy(beanFactory.getBeanClassLoader());
	}

}