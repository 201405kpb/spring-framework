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

package org.springframework.aop.aspectj.annotation;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.reflect.Method;

import org.aopalliance.aop.Advice;
import org.aspectj.lang.reflect.PerClauseKind;

import org.springframework.aop.Pointcut;
import org.springframework.aop.aspectj.AspectJExpressionPointcut;
import org.springframework.aop.aspectj.AspectJPrecedenceInformation;
import org.springframework.aop.aspectj.InstantiationModelAwarePointcutAdvisor;
import org.springframework.aop.aspectj.annotation.AbstractAspectJAdvisorFactory.AspectJAnnotation;
import org.springframework.aop.support.DynamicMethodMatcherPointcut;
import org.springframework.aop.support.Pointcuts;
import org.springframework.lang.Nullable;

/**
 * Internal implementation of AspectJPointcutAdvisor.
 * Note that there will be one instance of this advisor for each target method.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @since 2.0
 */
@SuppressWarnings("serial")
final class InstantiationModelAwarePointcutAdvisorImpl
		implements InstantiationModelAwarePointcutAdvisor, AspectJPrecedenceInformation, Serializable {

	private static final Advice EMPTY_ADVICE = new Advice() {};


	/**
	 * 切入点
	 */
	private final AspectJExpressionPointcut declaredPointcut;

	/**
	 * 切面类class
	 */
	private final Class<?> declaringClass;

	/**
	 * 通知方法名
	 */
	private final String methodName;

	/**
	 * 参数类型数组
	 */
	private final Class<?>[] parameterTypes;

	/**
	 * 通知方法
	 */
	private transient Method aspectJAdviceMethod;

	/**
	 * 当前ReflectiveAspectJAdvisorFactory工厂对象
	 */
	private final AspectJAdvisorFactory aspectJAdvisorFactory;

	/**
	 * 切面类实例工厂，用于获取切面类实例单例
	 */
	private final MetadataAwareAspectInstanceFactory aspectInstanceFactory;

	/**
	 *  声明的顺序，目前Spring 5.2.8版本都是固定0
	 */
	private final int declarationOrder;

	/**
	 * 切面名，就是beanName
	 */
	private final String aspectName;

	/**
	 * 切入点
	 */
	private final Pointcut pointcut;

	/**
	 * 是否配置了懒加载，默认false
	 */
	private final boolean lazy;

	@Nullable
	private Advice instantiatedAdvice;

	/**
	 * 是否为 BeforeAdvice
	 */
	@Nullable
	private Boolean isBeforeAdvice;

	/**
	 * 是否为 AfterAdvice
	 */
	@Nullable
	private Boolean isAfterAdvice;


	public InstantiationModelAwarePointcutAdvisorImpl(AspectJExpressionPointcut declaredPointcut,
													  Method aspectJAdviceMethod, AspectJAdvisorFactory aspectJAdvisorFactory,
													  MetadataAwareAspectInstanceFactory aspectInstanceFactory, int declarationOrder, String aspectName) {

		// AspectJExpressionPointcut 对象
		this.declaredPointcut = declaredPointcut;
		// Advice 所在的 Class 对象
		this.declaringClass = aspectJAdviceMethod.getDeclaringClass();
		// Advice 对应的方法名称
		this.methodName = aspectJAdviceMethod.getName();
		// Advice 对应的方法参数类型
		this.parameterTypes = aspectJAdviceMethod.getParameterTypes();
		// Advice 对应的方法对象
		this.aspectJAdviceMethod = aspectJAdviceMethod;
		// Advisor 工厂，用于解析 @AspectJ 注解的 Bean 中的 Advisor
		this.aspectJAdvisorFactory = aspectJAdvisorFactory;
		// 元数据实例构建工厂
		this.aspectInstanceFactory = aspectInstanceFactory;
		// 定义的顺序
		this.declarationOrder = declarationOrder;
		// Advice 所在 Bean 的名称
		this.aspectName = aspectName;

		// 如果需要延迟初始化，则不立即初始化 Advice 对象
		if (aspectInstanceFactory.getAspectMetadata().isLazilyInstantiated()) {
			// Static part of the pointcut is a lazy type.
			Pointcut preInstantiationPointcut = Pointcuts.union(
					aspectInstanceFactory.getAspectMetadata().getPerClausePointcut(), this.declaredPointcut);

			// Make it dynamic: must mutate from pre-instantiation to post-instantiation state.
			// If it's not a dynamic pointcut, it may be optimized out
			// by the Spring AOP infrastructure after the first evaluation.
			this.pointcut = new PerTargetInstantiationModelPointcut(
					this.declaredPointcut, preInstantiationPointcut, aspectInstanceFactory);
			this.lazy = true;
		}
		// 否则，初始化 Advice 对象
		else {
			// A singleton aspect.
			// AspectJExpressionPointcut 对象
			this.pointcut = this.declaredPointcut;
			this.lazy = false;
			// 根据 AspectJExpressionPointcut 初始化一个 Advice 对象
			// `@Around`：AspectJAroundAdvice，实现了 MethodInterceptor
			// `@Before`：AspectJMethodBeforeAdvice
			// `@After`：AspectJAfterAdvice，实现了 MethodInterceptor
			// `@AfterReturning`： AspectJAfterAdvice
			// `@AfterThrowing`：AspectJAfterThrowingAdvice，实现了 MethodInterceptor
			this.instantiatedAdvice = instantiateAdvice(this.declaredPointcut);
		}
	}


	/**
	 * The pointcut for Spring AOP to use.
	 * Actual behaviour of the pointcut will change depending on the state of the advice.
	 */
	@Override
	public Pointcut getPointcut() {
		return this.pointcut;
	}

	@Override
	public boolean isLazy() {
		return this.lazy;
	}

	@Override
	public synchronized boolean isAdviceInstantiated() {
		return (this.instantiatedAdvice != null);
	}

	/**
	 * Lazily instantiate advice if necessary.
	 */
	@Override
	public synchronized Advice getAdvice() {
		if (this.instantiatedAdvice == null) {
			this.instantiatedAdvice = instantiateAdvice(this.declaredPointcut);
		}
		return this.instantiatedAdvice;
	}

	private Advice instantiateAdvice(AspectJExpressionPointcut pointcut) {
		Advice advice = this.aspectJAdvisorFactory.getAdvice(this.aspectJAdviceMethod, pointcut,
				this.aspectInstanceFactory, this.declarationOrder, this.aspectName);
		return (advice != null ? advice : EMPTY_ADVICE);
	}

	/**
	 * This is only of interest for Spring AOP: AspectJ instantiation semantics
	 * are much richer. In AspectJ terminology, all a return of {@code true}
	 * means here is that the aspect is not a SINGLETON.
	 */
	@Override
	public boolean isPerInstance() {
		return (getAspectMetadata().getAjType().getPerClause().getKind() != PerClauseKind.SINGLETON);
	}

	/**
	 * Return the AspectJ AspectMetadata for this advisor.
	 */
	public AspectMetadata getAspectMetadata() {
		return this.aspectInstanceFactory.getAspectMetadata();
	}

	public MetadataAwareAspectInstanceFactory getAspectInstanceFactory() {
		return this.aspectInstanceFactory;
	}

	public AspectJExpressionPointcut getDeclaredPointcut() {
		return this.declaredPointcut;
	}

	@Override
	public int getOrder() {
		return this.aspectInstanceFactory.getOrder();
	}

	@Override
	public String getAspectName() {
		return this.aspectName;
	}

	@Override
	public int getDeclarationOrder() {
		return this.declarationOrder;
	}

	@Override
	public boolean isBeforeAdvice() {
		if (this.isBeforeAdvice == null) {
			determineAdviceType();
		}
		return this.isBeforeAdvice;
	}

	@Override
	public boolean isAfterAdvice() {
		if (this.isAfterAdvice == null) {
			determineAdviceType();
		}
		return this.isAfterAdvice;
	}

	/**
	 * Duplicates some logic from getAdvice, but importantly does not force
	 * creation of the advice.
	 */
	private void determineAdviceType() {
		AspectJAnnotation<?> aspectJAnnotation =
				AbstractAspectJAdvisorFactory.findAspectJAnnotationOnMethod(this.aspectJAdviceMethod);
		if (aspectJAnnotation == null) {
			this.isBeforeAdvice = false;
			this.isAfterAdvice = false;
		}
		else {
			switch (aspectJAnnotation.getAnnotationType()) {
				case AtPointcut, AtAround -> {
					this.isBeforeAdvice = false;
					this.isAfterAdvice = false;
				}
				case AtBefore -> {
					this.isBeforeAdvice = true;
					this.isAfterAdvice = false;
				}
				case AtAfter, AtAfterReturning, AtAfterThrowing -> {
					this.isBeforeAdvice = false;
					this.isAfterAdvice = true;
				}
			}
		}
	}


	private void readObject(ObjectInputStream inputStream) throws IOException, ClassNotFoundException {
		inputStream.defaultReadObject();
		try {
			this.aspectJAdviceMethod = this.declaringClass.getMethod(this.methodName, this.parameterTypes);
		}
		catch (NoSuchMethodException ex) {
			throw new IllegalStateException("Failed to find advice method on deserialization", ex);
		}
	}

	@Override
	public String toString() {
		return "InstantiationModelAwarePointcutAdvisor: expression [" + getDeclaredPointcut().getExpression() +
				"]; advice method [" + this.aspectJAdviceMethod + "]; perClauseKind=" +
				this.aspectInstanceFactory.getAspectMetadata().getAjType().getPerClause().getKind();
	}


	/**
	 * Pointcut implementation that changes its behaviour when the advice is instantiated.
	 * Note that this is a <i>dynamic</i> pointcut; otherwise it might be optimized out
	 * if it does not at first match statically.
	 */
	private static final class PerTargetInstantiationModelPointcut extends DynamicMethodMatcherPointcut {

		private final AspectJExpressionPointcut declaredPointcut;

		private final Pointcut preInstantiationPointcut;

		@Nullable
		private LazySingletonAspectInstanceFactoryDecorator aspectInstanceFactory;

		public PerTargetInstantiationModelPointcut(AspectJExpressionPointcut declaredPointcut,
				Pointcut preInstantiationPointcut, MetadataAwareAspectInstanceFactory aspectInstanceFactory) {

			this.declaredPointcut = declaredPointcut;
			this.preInstantiationPointcut = preInstantiationPointcut;
			if (aspectInstanceFactory instanceof LazySingletonAspectInstanceFactoryDecorator lazyFactory) {
				this.aspectInstanceFactory = lazyFactory;
			}
		}

		@Override
		public boolean matches(Method method, Class<?> targetClass) {
			// We're either instantiated and matching on declared pointcut,
			// or uninstantiated matching on either pointcut...
			return (isAspectMaterialized() && this.declaredPointcut.matches(method, targetClass)) ||
					this.preInstantiationPointcut.getMethodMatcher().matches(method, targetClass);
		}

		@Override
		public boolean matches(Method method, Class<?> targetClass, Object... args) {
			// This can match only on declared pointcut.
			return (isAspectMaterialized() && this.declaredPointcut.matches(method, targetClass));
		}

		private boolean isAspectMaterialized() {
			return (this.aspectInstanceFactory == null || this.aspectInstanceFactory.isMaterialized());
		}
	}

}
