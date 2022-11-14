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

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.aopalliance.aop.Advice;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.DeclareParents;
import org.aspectj.lang.annotation.Pointcut;

import org.springframework.aop.Advisor;
import org.springframework.aop.MethodBeforeAdvice;
import org.springframework.aop.aspectj.AbstractAspectJAdvice;
import org.springframework.aop.aspectj.AspectJAfterAdvice;
import org.springframework.aop.aspectj.AspectJAfterReturningAdvice;
import org.springframework.aop.aspectj.AspectJAfterThrowingAdvice;
import org.springframework.aop.aspectj.AspectJAroundAdvice;
import org.springframework.aop.aspectj.AspectJExpressionPointcut;
import org.springframework.aop.aspectj.AspectJMethodBeforeAdvice;
import org.springframework.aop.aspectj.DeclareParentsAdvisor;
import org.springframework.aop.framework.AopConfigException;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConvertingComparator;
import org.springframework.lang.Nullable;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.ReflectionUtils.MethodFilter;
import org.springframework.util.StringUtils;
import org.springframework.util.comparator.InstanceComparator;

/**
 * Factory that can create Spring AOP Advisors given AspectJ classes from
 * classes honoring AspectJ's annotation syntax, using reflection to invoke the
 * corresponding advice methods.
 *
 * @author Rod Johnson
 * @author Adrian Colyer
 * @author Juergen Hoeller
 * @author Ramnivas Laddad
 * @author Phillip Webb
 * @author Sam Brannen
 * @since 2.0
 */
@SuppressWarnings("serial")
public class ReflectiveAspectJAdvisorFactory extends AbstractAspectJAdvisorFactory implements Serializable {

	// Exclude @Pointcut methods
	private static final MethodFilter adviceMethodFilter = ReflectionUtils.USER_DECLARED_METHODS
			.and(method -> (AnnotationUtils.getAnnotation(method, Pointcut.class) == null));

	private static final Comparator<Method> adviceMethodComparator;

	static {
		// Note: although @After is ordered before @AfterReturning and @AfterThrowing,
		// an @After advice method will actually be invoked after @AfterReturning and
		// @AfterThrowing methods due to the fact that AspectJAfterAdvice.invoke(MethodInvocation)
		// invokes proceed() in a `try` block and only invokes the @After advice method
		// in a corresponding `finally` block.

		//主要比较器，采用ConvertingComparator，先对传入的参数使用转换器进行转换，随后使用比较器进行比较
		Comparator<Method> adviceKindComparator = new ConvertingComparator<>(
				//实例比较器，order值就是InstanceComparator中定义的注解的索引，如果没有这些注解就是最大值5
				new InstanceComparator<>(
						Around.class, Before.class, After.class, AfterReturning.class, AfterThrowing.class),
				//实例转换器，将通知方法转换为一个AspectJAnnotation对象，该方法上标注的注解，就是对应对象的类型
				(Converter<Method, Annotation>) method -> {
					//查找方法的注解，找到某一个注解即停止查找，然后将当前方法封装为基于该注解的AspectJAnnotation，查找注解的顺序为：
					//Pointcut.class, Around.class, Before.class, After.class, AfterReturning.class, AfterThrowing.class
					AspectJAnnotation<?> ann = AbstractAspectJAdvisorFactory.findAspectJAnnotationOnMethod(method);
					return (ann != null ? ann.getAnnotation() : null);
				});
		//次要比较器，比较方法名
		Comparator<Method> methodNameComparator = new ConvertingComparator<>(Method::getName);
		adviceMethodComparator = adviceKindComparator.thenComparing(methodNameComparator);

	}


	@Nullable
	private final BeanFactory beanFactory;


	/**
	 * Create a new {@code ReflectiveAspectJAdvisorFactory}.
	 */
	public ReflectiveAspectJAdvisorFactory() {
		this(null);
	}

	/**
	 * Create a new {@code ReflectiveAspectJAdvisorFactory}, propagating the given
	 * {@link BeanFactory} to the created {@link AspectJExpressionPointcut} instances,
	 * for bean pointcut handling as well as consistent {@link ClassLoader} resolution.
	 * @param beanFactory the BeanFactory to propagate (may be {@code null}}
	 * @since 4.3.6
	 * @see AspectJExpressionPointcut#setBeanFactory
	 * @see org.springframework.beans.factory.config.ConfigurableBeanFactory#getBeanClassLoader()
	 */
	public ReflectiveAspectJAdvisorFactory(@Nullable BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}


	/**
	 * 为指定切面类上的所有具有通知注解的方法和具有引介注解的字段生成Advisor
	 * 返回找到的全部通知器集合
	 *
	 * @param aspectInstanceFactory 切面实例工厂
	 * @return 解析的Advisor通知器集合
	 */
	@Override
	public List<Advisor> getAdvisors(MetadataAwareAspectInstanceFactory aspectInstanceFactory) {
		//获取切面类的class
		Class<?> aspectClass = aspectInstanceFactory.getAspectMetadata().getAspectClass();
		//获取切面类的name，就是beanName
		String aspectName = aspectInstanceFactory.getAspectMetadata().getAspectName();
		//校验切面类，后面还会校验几次
		validate(aspectClass);

		// We need to wrap the MetadataAwareAspectInstanceFactory with a decorator
		// so that it will only instantiate once.
		//使用装饰器包装当前的aspectInstanceFactory对象，使得内部的切面类实例只被创建一次
		//因为aspectInstanceFactory创建的切面类实例将被缓存起来
		MetadataAwareAspectInstanceFactory lazySingletonAspectInstanceFactory =
				new LazySingletonAspectInstanceFactoryDecorator(aspectInstanceFactory);
		//找到的通知器集合
		List<Advisor> advisors = new ArrayList<>();
		/*
		 * getAdvisorMethods获取当前切面类中的全部方法
		 * 排除桥接方法、合成方法、具有@Pointcut注解的方法，所以说普通方法也会被加进来
		 * 因此还需要继续筛选和处理
		 */
		for (Method method : getAdvisorMethods(aspectClass)) {
			// Prior to Spring Framework 5.2.7, advisors.size() was supplied as the declarationOrderInAspect
			// to getAdvisor(...) to represent the "current position" in the declared methods list.
			// However, since Java 7 the "current position" is not valid since the JDK no longer
			// returns declared methods in the order in which they are declared in the source code.
			// Thus, we now hard code the declarationOrderInAspect to 0 for all advice methods
			// discovered via reflection in order to support reliable advice ordering across JVM launches.
			// Specifically, a value of 0 aligns with the default value used in
			// AspectJPrecedenceComparator.getAspectDeclarationOrder(Advisor).

			//处理方法，尝试转换为Advisor通知器
			//Spring 5.2.7之前，advisors.size()作为第三个参数，以便确定位置。但是Java7开始，JDK不再按在源代码中声明的方法的顺序返回声明的方法
			//因此，对于通过反射发现的所有通知方法，我们现在将第三个参数declarationOrderInAspect通过硬编码设置为0，所有的通知的declarationOrder都是0
			//返回的通知器实际类型为InstantiationModelAwarePointcutAdvisorImpl，属于PointcutAdvisor切入点通知器
			Advisor advisor = getAdvisor(method, lazySingletonAspectInstanceFactory, 0, aspectName);
			if (advisor != null) {
				//切入点通知器加入通知器集合
				advisors.add(advisor);
			}
		}

		// If it's a per target aspect, emit the dummy instantiating aspect.
		//如果通知器集合不为空，并且属于延迟初始化的切面类，那么在通知器列表头部加入一个SyntheticInstantiationAdvisor同步实例通知器
		if (!advisors.isEmpty() && lazySingletonAspectInstanceFactory.getAspectMetadata().isLazilyInstantiated()) {
			Advisor instantiationAdvisor = new SyntheticInstantiationAdvisor(lazySingletonAspectInstanceFactory);
			advisors.add(0, instantiationAdvisor);
		}

		// Find introduction fields.
		/*
		 * 查找并解析引介增强字段，即@DeclareParents注解
		 */
		//遍历全部字段
		for (Field field : aspectClass.getDeclaredFields()) {
			//从该字段获取引介增强的通知器
			//返回的通知器实际类型为DeclareParentsAdvisor，属于IntroductionAdvisor引介通知器
			Advisor advisor = getDeclareParentsAdvisor(field);
			if (advisor != null) {
				//引介通知器加入通知器集合
				advisors.add(advisor);
			}
		}
		//返回通知器集合
		return advisors;
	}

	/**
	 * 获取当前切面类中的全部通知方法
	 * 排除桥接方法、合成方法、具有@Pointcut注解的方法，所以说普通方法也会被加进来
	 *
	 * @param aspectClass 切面类类型
	 * @return 当前切面类中的全部通知方法
	 */
	private List<Method> getAdvisorMethods(Class<?> aspectClass) {
		final List<Method> methods = new ArrayList<>();
		/*
		 * 循环过滤所有的方法（不包括构造器）
		 * 第一个参数：要查找的类
		 * 第二个参数：方法回调
		 * 第三个参数：方法过滤器，这里是USER_DECLARED_METHODS，即排除桥接方法和合成方法
		 */
		ReflectionUtils.doWithMethods(aspectClass, method -> {
			// Exclude pointcuts
			//如果当前方法不是桥接方法和合成方法，并且没有@Pointcut注解
			//那么算作通知方法，所以说普通方法也会被加进来
			if (AnnotationUtils.getAnnotation(method, Pointcut.class) == null) {
				methods.add(method);
			}
		}, ReflectionUtils.USER_DECLARED_METHODS);
		//通过比较器，排序
		if (methods.size() > 1) {
			methods.sort(adviceMethodComparator);
		}
		return methods;
	}


	/**
	 * Build a {@link org.springframework.aop.aspectj.DeclareParentsAdvisor}
	 * for the given introduction field.
	 * 根据给定的字段创建一个DeclareParentsAdvisor引介增强通知器
	 * <p>Resulting Advisors will need to be evaluated for targets.
	 * @param introductionField the field to introspect
	 * @return the Advisor instance, or {@code null} if not an Advisor
	 * 如果不是引介增强字段则返回null
	 */
	@Nullable
	private Advisor getDeclareParentsAdvisor(Field introductionField) {
		//获取字段上的@DeclareParents注解
		DeclareParents declareParents = introductionField.getAnnotation(DeclareParents.class);
		//如果没有@DeclareParents注解，说明是普通字段，返回null
		if (declareParents == null) {
			// Not an introduction field
			return null;
		}
		//如果注解的defaultImpl属性值为默认值（默认值就是DeclareParents.class）
		//那么抛出异常："'defaultImpl' attribute must be set on DeclareParents"
		if (DeclareParents.class == declareParents.defaultImpl()) {
			throw new IllegalStateException("'defaultImpl' attribute must be set on DeclareParents");
		}
		//解析@DeclareParents注解，返回一个新建的DeclareParentsAdvisor通知器
		return new DeclareParentsAdvisor(
				introductionField.getType(), declareParents.value(), declareParents.defaultImpl());
	}

	/**
	 * <p>
	 * 根据给定方法尝试转换为通知器
	 *
	 * @param candidateAdviceMethod    候选通知方法
	 * @param aspectInstanceFactory    切面类实例工厂
	 * @param declarationOrderInAspect 生命顺序，目前Spring 5.2.8版本都是固定0
	 * @param aspectName               切面名，就是beanName
	 * @return 如果该方法不是 AspectJ 通知方法，则返回null
	 */
	@Override
	@Nullable
	public Advisor getAdvisor(Method candidateAdviceMethod, MetadataAwareAspectInstanceFactory aspectInstanceFactory,
							  int declarationOrderInAspect, String aspectName) {
		//再次校验
		validate(aspectInstanceFactory.getAspectMetadata().getAspectClass());
		/*
		 * 获取当前通知方法对应的切入点实例，封装了当前通知的切入点表达式的信息
		 */
		AspectJExpressionPointcut expressionPointcut = getPointcut(
				candidateAdviceMethod, aspectInstanceFactory.getAspectMetadata().getAspectClass());
		//如果没有切入点表达式，那么直接返回null，对于普通方法，将在这里返回null
		if (expressionPointcut == null) {
			return null;
		}
		/*
		 * 新建一个InstantiationModelAwarePointcutAdvisorImpl类型的通知器返回
		 */
		return new InstantiationModelAwarePointcutAdvisorImpl(expressionPointcut, candidateAdviceMethod,
				this, aspectInstanceFactory, declarationOrderInAspect, aspectName);
	}

	/**
	 * 获取当前通知方法对应的切入点表达式对象
	 *
	 * @param candidateAdviceMethod 候选通知方法
	 * @param candidateAspectClass  候选切面类类型
	 * @return AspectJExpressionPointcut
	 */
	@Nullable
	private AspectJExpressionPointcut getPointcut(Method candidateAdviceMethod, Class<?> candidateAspectClass) {
		//查找当前方法上的通知，获取AspectJAnnotation对象
		//这里的查找是按顺序的短路查找，顺序为：Pointcut.class, Around.class, Before.class, After.class, AfterReturning.class, AfterThrowing.class
		//如果找到一个通知注解，就立马封装后返回，如果有其他通知注解则被丢弃
		AspectJAnnotation<?> aspectJAnnotation =
				AbstractAspectJAdvisorFactory.findAspectJAnnotationOnMethod(candidateAdviceMethod);
		//如果没有通知注解，则返回null
		if (aspectJAnnotation == null) {
			return null;
		}
		//新建一个AspectJExpressionPointcut实例
		AspectJExpressionPointcut ajexp =
				new AspectJExpressionPointcut(candidateAspectClass, new String[0], new Class<?>[0]);
		//设置expression表达式的值，也就是上面的通知注解的pointcut或者value属性的值
		ajexp.setExpression(aspectJAnnotation.getPointcutExpression());
		//设置beanFactory信息
		if (this.beanFactory != null) {
			ajexp.setBeanFactory(this.beanFactory);
		}
		return ajexp;
	}


	/**
	 * ReflectiveAspectJAdvisorFactory的方法
	 * <p>
	 * 为给定的AspectJ 通知方法构建一个Advice通知实例
	 * <p>
	 * 通知实例用于执行通知方法的回调或者适配成拦截器
	 *
	 * @param candidateAdviceMethod 通知方法
	 * @param expressionPointcut    切入点
	 * @param aspectInstanceFactory 切面类实例工厂，用于获取切面类实例单例
	 * @param declarationOrder      声明的顺序，目前Spring 5.2.8版本都是固定0
	 * @param aspectName            切面名，就是beanName
	 * @return 一个Advice
	 */
	@Override
	@Nullable
	public Advice getAdvice(Method candidateAdviceMethod, AspectJExpressionPointcut expressionPointcut,
							MetadataAwareAspectInstanceFactory aspectInstanceFactory, int declarationOrder, String aspectName) {
		//获取切面类类型
		Class<?> candidateAspectClass = aspectInstanceFactory.getAspectMetadata().getAspectClass();
		//校验切面类
		validate(candidateAspectClass);
		//查找一个AspectJ 注解
		AspectJAnnotation<?> aspectJAnnotation =
				AbstractAspectJAdvisorFactory.findAspectJAnnotationOnMethod(candidateAdviceMethod);
		//如果没找到就返回null
		if (aspectJAnnotation == null) {
			return null;
		}

		// If we get here, we know we have an AspectJ method.
		// Check that it's an AspectJ-annotated class
		//如果当前类不是被@AspectJ注解标注的切面类，那么抛出异常
		if (!isAspect(candidateAspectClass)) {
			throw new AopConfigException("Advice must be declared inside an aspect type: " +
					"Offending method '" + candidateAdviceMethod + "' in class [" +
					candidateAspectClass.getName() + "]");
		}

		if (logger.isDebugEnabled()) {
			logger.debug("Found AspectJ method: " + candidateAdviceMethod);
		}

		/*
		 * 获取AspectJ注解的类型，匹配枚举类型并创建对应类型的通知，一共有六种
		 */
		AbstractAspectJAdvice springAdvice;
		switch (aspectJAnnotation.getAnnotationType()) {
			//如果是切入点注解，即@Pointcut
			case AtPointcut:
				if (logger.isDebugEnabled()) {
					logger.debug("Processing pointcut '" + candidateAdviceMethod.getName() + "'");
				}
				//那么直接返回null，因为这里需要的是通知注解
				return null;
			//如果是环绕通知注解，即@Around
			case AtAround:
				//那么新建一个AspectJAroundAdvice类型的通知
				springAdvice = new AspectJAroundAdvice(
						candidateAdviceMethod, expressionPointcut, aspectInstanceFactory);
				break;
			//如果是前置通知注解，即@Before
			case AtBefore:
				//那么新建一个AspectJMethodBeforeAdvice类型的通知
				springAdvice = new AspectJMethodBeforeAdvice(
						candidateAdviceMethod, expressionPointcut, aspectInstanceFactory);
				break;
			//如果是最终通知注解，即@After
			case AtAfter:
				//那么新建一个AspectJAfterAdvice类型的通知
				springAdvice = new AspectJAfterAdvice(
						candidateAdviceMethod, expressionPointcut, aspectInstanceFactory);
				break;
			//如果是后置通知注解，即@AfterReturning
			case AtAfterReturning:
				//那么新建一个AspectJAfterReturningAdvice类型的通知
				springAdvice = new AspectJAfterReturningAdvice(
						candidateAdviceMethod, expressionPointcut, aspectInstanceFactory);
				//获取@AfterReturning注解
				AfterReturning afterReturningAnnotation = (AfterReturning) aspectJAnnotation.getAnnotation();
				//如果注解设置了returning属性，表示需要传递方法返回值参数，那么设置后置通知的returningName属性
				if (StringUtils.hasText(afterReturningAnnotation.returning())) {
					springAdvice.setReturningName(afterReturningAnnotation.returning());
				}
				break;
			//如果是异常通知注解，即@AfterThrowing
			case AtAfterThrowing:
				//那么新建一个AspectJAfterThrowingAdvice类型的通知
				springAdvice = new AspectJAfterThrowingAdvice(
						candidateAdviceMethod, expressionPointcut, aspectInstanceFactory);
				//获取@AfterThrowing注解
				AfterThrowing afterThrowingAnnotation = (AfterThrowing) aspectJAnnotation.getAnnotation();
				//如果注解设置了throwing属性，表示需要传递方法异常参数，那么设置异常通知的throwingName属性
				if (StringUtils.hasText(afterThrowingAnnotation.throwing())) {
					springAdvice.setThrowingName(afterThrowingAnnotation.throwing());
				}
				break;
			//其他情况，抛出异常
			default:
				throw new UnsupportedOperationException(
						"Unsupported advice type on method: " + candidateAdviceMethod);
		}
		/*
		 * 配置通知
		 */
		// Now to configure the advice...
		//设置aspectName，即切面名
		springAdvice.setAspectName(aspectName);
		//设置declarationOrder，默认都是0
		springAdvice.setDeclarationOrder(declarationOrder);
		//通过参数名称发现器获取传递的参数
		//实际上就是获取通知注解上的argNames属性值，并且根据","进行拆分
		String[] argNames = this.parameterNameDiscoverer.getParameterNames(candidateAdviceMethod);
		if (argNames != null) {
			//设置给argumentNames属性，并且可能会补充第一个参数名（如果第一个参数是JoinPoint或者ProceedingJoinPoint或者JoinPoint.StaticPart）
			springAdvice.setArgumentNamesFromStringArray(argNames);
		}
		//辅助参数绑定，后面执行invoke拦截器的时候就不会再绑定了
		springAdvice.calculateArgumentBindings();
		//返回通知
		return springAdvice;
	}


	/**
	 * Synthetic advisor that instantiates the aspect.
	 * Triggered by per-clause pointcut on non-singleton aspect.
	 * The advice has no effect.
	 */
	@SuppressWarnings("serial")
	protected static class SyntheticInstantiationAdvisor extends DefaultPointcutAdvisor {

		public SyntheticInstantiationAdvisor(final MetadataAwareAspectInstanceFactory aif) {
			super(aif.getAspectMetadata().getPerClausePointcut(), (MethodBeforeAdvice)
					(method, args, target) -> aif.getAspectInstance());
		}
	}

}
