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

package org.springframework.scheduling.annotation;

import java.lang.annotation.Annotation;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import org.aopalliance.aop.Advice;

import org.springframework.aop.Pointcut;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.aop.support.AbstractPointcutAdvisor;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.function.SingletonSupplier;

/**
 * Advisor that activates asynchronous method execution through the {@link Async}
 * annotation. This annotation can be used at the method and type level in
 * implementation classes as well as in service interfaces.
 *
 * <p>This advisor detects the EJB 3.1 {@code jakarta.ejb.Asynchronous}
 * annotation as well, treating it exactly like Spring's own {@code Async}.
 * Furthermore, a custom async annotation type may get specified through the
 * {@link #setAsyncAnnotationType "asyncAnnotationType"} property.
 *
 * @author Juergen Hoeller
 * @since 3.0
 * @see Async
 * @see AnnotationAsyncExecutionInterceptor
 */
@SuppressWarnings("serial")
public class AsyncAnnotationAdvisor extends AbstractPointcutAdvisor implements BeanFactoryAware {

	private final Advice advice;

	private Pointcut pointcut;


	/**
	 * Create a new {@code AsyncAnnotationAdvisor} for bean-style configuration.
	 */
	public AsyncAnnotationAdvisor() {
		this(null, (Supplier<AsyncUncaughtExceptionHandler>) null);
	}

	/**
	 * Create a new {@code AsyncAnnotationAdvisor} for the given task executor.
	 * @param executor the task executor to use for asynchronous methods
	 * (can be {@code null} to trigger default executor resolution)
	 * @param exceptionHandler the {@link AsyncUncaughtExceptionHandler} to use to
	 * handle unexpected exception thrown by asynchronous method executions
	 * @see AnnotationAsyncExecutionInterceptor#getDefaultExecutor(BeanFactory)
	 */
	public AsyncAnnotationAdvisor(
			@Nullable Executor executor, @Nullable AsyncUncaughtExceptionHandler exceptionHandler) {

		this(SingletonSupplier.ofNullable(executor), SingletonSupplier.ofNullable(exceptionHandler));
	}

	/**
	 * Create a new {@code AsyncAnnotationAdvisor} for the given task executor.
	 * @param executor the task executor to use for asynchronous methods
	 * (can be {@code null} to trigger default executor resolution)
	 * @param exceptionHandler the {@link AsyncUncaughtExceptionHandler} to use to
	 * handle unexpected exception thrown by asynchronous method executions
	 * @since 5.1
	 * @see AnnotationAsyncExecutionInterceptor#getDefaultExecutor(BeanFactory)
	 */
	@SuppressWarnings("unchecked")
	public AsyncAnnotationAdvisor(
			@Nullable Supplier<Executor> executor, @Nullable Supplier<AsyncUncaughtExceptionHandler> exceptionHandler) {
		//表示异步任务注解的集合
		Set<Class<? extends Annotation>> asyncAnnotationTypes = new LinkedHashSet<>(2);
		//添加@Async
		asyncAnnotationTypes.add(Async.class);

		ClassLoader classLoader = AsyncAnnotationAdvisor.class.getClassLoader();
		try {
			//添加@javax.ejb.Asynchronous，如果存在EJB依赖
			asyncAnnotationTypes.add((Class<? extends Annotation>)
					ClassUtils.forName("jakarta.ejb.Asynchronous", classLoader));
		}
		catch (ClassNotFoundException ex) {
			// If EJB API not present, simply ignore.
		}
		try {
			asyncAnnotationTypes.add((Class<? extends Annotation>)
					ClassUtils.forName("jakarta.enterprise.concurrent.Asynchronous", classLoader));
		}
		catch (ClassNotFoundException ex) {
			// If Jakarta Concurrent API not present, simply ignore.
		}

		//构建通知
		this.advice = buildAdvice(executor, exceptionHandler);
		//构建切入点
		this.pointcut = buildPointcut(asyncAnnotationTypes);
	}


	/**
	 * Set the 'async' annotation type.
	 * <p>The default async annotation type is the {@link Async} annotation, as well
	 * as the EJB 3.1 {@code jakarta.ejb.Asynchronous} annotation (if present).
	 * <p>This setter property exists so that developers can provide their own
	 * (non-Spring-specific) annotation type to indicate that a method is to
	 * be executed asynchronously.
	 * 在setBeanFactory的回调方法中会调用该方法，用于手动设置匹配的异步任务注解
	 * 如果手动设置异步任务注解，那么不会查找默认的异步任务注解，比如@Async
	 * @param asyncAnnotationType the desired annotation type
	 */
	public void setAsyncAnnotationType(Class<? extends Annotation> asyncAnnotationType) {
		Assert.notNull(asyncAnnotationType, "'asyncAnnotationType' must not be null");
		Set<Class<? extends Annotation>> asyncAnnotationTypes = new HashSet<>();
		asyncAnnotationTypes.add(asyncAnnotationType);
		this.pointcut = buildPointcut(asyncAnnotationTypes);
	}

	/**
	 * Set the {@code BeanFactory} to be used when looking up executors by qualifier.
	 */
	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		if (this.advice instanceof BeanFactoryAware beanFactoryAware) {
			beanFactoryAware.setBeanFactory(beanFactory);
		}
	}


	@Override
	public Advice getAdvice() {
		return this.advice;
	}

	@Override
	public Pointcut getPointcut() {
		return this.pointcut;
	}


	/**
	 * 构建通知
	 *
	 * @param executor         指定的执行器
	 * @param exceptionHandler 指定的异常处理器
	 * @return 一个拦截器通知
	 */
	protected Advice buildAdvice(
			@Nullable Supplier<Executor> executor, @Nullable Supplier<AsyncUncaughtExceptionHandler> exceptionHandler) {
		//创建一个AnnotationAsyncExecutionInterceptor拦截器，Spring AOP最终是依赖拦截器链来实现代理的功能
		AnnotationAsyncExecutionInterceptor interceptor = new AnnotationAsyncExecutionInterceptor(null);
		//配置此切面的执行器和异常处理器，如果没有指定，则应用相应的默认值。
		interceptor.configure(executor, exceptionHandler);
		return interceptor;
	}

	/**
	 * Calculate a pointcut for the given async annotation types, if any.
	 * 构建切入点
	 * @param asyncAnnotationTypes the async annotation types to introspect
	 * @return the applicable Pointcut object, or {@code null} if none
	 */
	protected Pointcut buildPointcut(Set<Class<? extends Annotation>> asyncAnnotationTypes) {
		ComposablePointcut result = null;
		//遍历注解集合
		for (Class<? extends Annotation> asyncAnnotationType : asyncAnnotationTypes) {
			//设置匹配器，检查类上的注解，同时也检查超类和接口以及注解的元注解
			Pointcut cpc = new AnnotationMatchingPointcut(asyncAnnotationType, true);
			//设置匹配器，检查方法上的注解，同时也检查超类和接口的方法以及方法上的注解的元注解
			Pointcut mpc = new AnnotationMatchingPointcut(null, asyncAnnotationType, true);
			if (result == null) {
				result = new ComposablePointcut(cpc);
			}
			else {
				result.union(cpc);
			}
			result = result.union(mpc);
		}
		return (result != null ? result : Pointcut.TRUE);
	}

}
