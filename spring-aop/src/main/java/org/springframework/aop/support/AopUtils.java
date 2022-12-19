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

package org.springframework.aop.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.aop.Advisor;
import org.springframework.aop.AopInvocationException;
import org.springframework.aop.IntroductionAdvisor;
import org.springframework.aop.IntroductionAwareMethodMatcher;
import org.springframework.aop.MethodMatcher;
import org.springframework.aop.Pointcut;
import org.springframework.aop.PointcutAdvisor;
import org.springframework.aop.SpringProxy;
import org.springframework.aop.TargetClassAware;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.MethodIntrospector;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Utility methods for AOP support code.
 *
 * <p>Mainly for internal use within Spring's AOP support.
 *
 * <p>See {@link org.springframework.aop.framework.AopProxyUtils} for a
 * collection of framework-specific AOP utility methods which depend
 * on internals of Spring's AOP framework implementation.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @see org.springframework.aop.framework.AopProxyUtils
 */
public abstract class AopUtils {

	/**
	 * Check whether the given object is a JDK dynamic proxy or a CGLIB proxy.
	 * <p>This method additionally checks if the given object is an instance
	 * of {@link SpringProxy}.
	 * @param object the object to check
	 * @see #isJdkDynamicProxy
	 * @see #isCglibProxy
	 */
	public static boolean isAopProxy(@Nullable Object object) {
		return (object instanceof SpringProxy && (Proxy.isProxyClass(object.getClass()) ||
				object.getClass().getName().contains(ClassUtils.CGLIB_CLASS_SEPARATOR)));
	}

	/**
	 * Check whether the given object is a JDK dynamic proxy.
	 * <p>This method goes beyond the implementation of
	 * {@link Proxy#isProxyClass(Class)} by additionally checking if the
	 * given object is an instance of {@link SpringProxy}.
	 * @param object the object to check
	 * @see java.lang.reflect.Proxy#isProxyClass
	 */
	public static boolean isJdkDynamicProxy(@Nullable Object object) {
		return (object instanceof SpringProxy && Proxy.isProxyClass(object.getClass()));
	}

	/**
	 * Check whether the given object is a CGLIB proxy.
	 * <p>This method goes beyond the implementation of
	 * {@link ClassUtils#isCglibProxy(Object)} by additionally checking if
	 * the given object is an instance of {@link SpringProxy}.
	 * @param object the object to check
	 * @see ClassUtils#isCglibProxy(Object)
	 */
	public static boolean isCglibProxy(@Nullable Object object) {
		return (object instanceof SpringProxy &&
				object.getClass().getName().contains(ClassUtils.CGLIB_CLASS_SEPARATOR));
	}

	/**
	 * Determine the target class of the given bean instance which might be an AOP proxy.
	 * <p>Returns the target class for an AOP proxy or the plain class otherwise.
	 * @param candidate the instance to check (might be an AOP proxy)
	 * @return the target class (or the plain class of the given object as fallback;
	 * never {@code null})
	 * @see org.springframework.aop.TargetClassAware#getTargetClass()
	 * @see org.springframework.aop.framework.AopProxyUtils#ultimateTargetClass(Object)
	 */
	public static Class<?> getTargetClass(Object candidate) {
		Assert.notNull(candidate, "Candidate object must not be null");
		Class<?> result = null;
		if (candidate instanceof TargetClassAware targetClassAware) {
			result = targetClassAware.getTargetClass();
		}
		if (result == null) {
			result = (isCglibProxy(candidate) ? candidate.getClass().getSuperclass() : candidate.getClass());
		}
		return result;
	}

	/**
	 * Select an invocable method on the target type: either the given method itself
	 * if actually exposed on the target type, or otherwise a corresponding method
	 * on one of the target type's interfaces or on the target type itself.
	 * @param method the method to check
	 * @param targetType the target type to search methods on (typically an AOP proxy)
	 * @return a corresponding invocable method on the target type
	 * @throws IllegalStateException if the given method is not invocable on the given
	 * target type (typically due to a proxy mismatch)
	 * @since 4.3
	 * @see MethodIntrospector#selectInvocableMethod(Method, Class)
	 */
	public static Method selectInvocableMethod(Method method, @Nullable Class<?> targetType) {
		if (targetType == null) {
			return method;
		}
		Method methodToUse = MethodIntrospector.selectInvocableMethod(method, targetType);
		if (Modifier.isPrivate(methodToUse.getModifiers()) && !Modifier.isStatic(methodToUse.getModifiers()) &&
				SpringProxy.class.isAssignableFrom(targetType)) {
			throw new IllegalStateException(String.format(
					"Need to invoke method '%s' found on proxy for target class '%s' but cannot " +
					"be delegated to target bean. Switch its visibility to package or protected.",
					method.getName(), method.getDeclaringClass().getSimpleName()));
		}
		return methodToUse;
	}

	/**
	 * Determine whether the given method is an "equals" method.
	 * @see java.lang.Object#equals
	 */
	public static boolean isEqualsMethod(@Nullable Method method) {
		return ReflectionUtils.isEqualsMethod(method);
	}

	/**
	 * Determine whether the given method is a "hashCode" method.
	 * @see java.lang.Object#hashCode
	 */
	public static boolean isHashCodeMethod(@Nullable Method method) {
		return ReflectionUtils.isHashCodeMethod(method);
	}

	/**
	 * Determine whether the given method is a "toString" method.
	 * @see java.lang.Object#toString()
	 */
	public static boolean isToStringMethod(@Nullable Method method) {
		return ReflectionUtils.isToStringMethod(method);
	}

	/**
	 * Determine whether the given method is a "finalize" method.
	 * @see java.lang.Object#finalize()
	 */
	public static boolean isFinalizeMethod(@Nullable Method method) {
		return (method != null && method.getName().equals("finalize") &&
				method.getParameterCount() == 0);
	}

	/**
	 * Given a method, which may come from an interface, and a target class used
	 * in the current AOP invocation, find the corresponding target method if there
	 * is one. E.g. the method may be {@code IFoo.bar()} and the target class
	 * may be {@code DefaultFoo}. In this case, the method may be
	 * {@code DefaultFoo.bar()}. This enables attributes on that method to be found.
	 * <p><b>NOTE:</b> In contrast to {@link org.springframework.util.ClassUtils#getMostSpecificMethod},
	 * this method resolves bridge methods in order to retrieve attributes from
	 * the <i>original</i> method definition.
	 * @param method the method to be invoked, which may come from an interface
	 * @param targetClass the target class for the current invocation.
	 * May be {@code null} or may not even implement the method.
	 * @return the specific target method, or the original method if the
	 * {@code targetClass} doesn't implement it or is {@code null}
	 * @see org.springframework.util.ClassUtils#getMostSpecificMethod
	 */
	public static Method getMostSpecificMethod(Method method, @Nullable Class<?> targetClass) {
		Class<?> specificTargetClass = (targetClass != null ? ClassUtils.getUserClass(targetClass) : null);
		Method resolvedMethod = ClassUtils.getMostSpecificMethod(method, specificTargetClass);
		// If we are dealing with method with generic parameters, find the original method.
		return BridgeMethodResolver.findBridgedMethod(resolvedMethod);
	}

	/**
	 * Can the given pointcut apply at all on the given class?
	 * <p>This is an important test as it can be used to optimize
	 * out a pointcut for a class.
	 * @param pc the static or dynamic pointcut to check
	 * @param targetClass the class to test
	 * @return whether the pointcut can apply on any method
	 */
	public static boolean canApply(Pointcut pc, Class<?> targetClass) {
		return canApply(pc, targetClass, false);
	}

	/**
	 * Can the given pointcut apply at all on the given class?
	 * 判断给定的切入点能否在给定的类上适用？
	 * <p>This is an important test as it can be used to optimize
	 * out a pointcut for a class.
	 * @param pc the static or dynamic pointcut to check 要检查的方法切入点，常见就是AspectJExpressionPointcut
	 * @param targetClass the class to test 目标的beanClass
	 * @param hasIntroductions whether the advisor chain
	 * for this bean includes any introductions 此类的Advisor链是否已包含introductions引介增强
	 * @return whether the pointcut can apply on any method 给定的advisor是否可以增强给定的class
	 */
	public static boolean canApply(Pointcut pc, Class<?> targetClass, boolean hasIntroductions) {
		Assert.notNull(pc, "Pointcut must not be null");
		/*
		 * 1 匹配目标类的类路径是否满足切入点的execution表达式
		 */
		//如果目标的beanClass的路径不包含在当前切入点的类路径中，也就是execution表达式的类，路径那么直接返回false
		if (!pc.getClassFilter().matches(targetClass)) {
			return false;
		}
		/*
		 * 2 匹配目标类的方法是否至少有一个满足切入点的execution表达式
		 */
		//到这一步，表示目标的beanClass的路径包含在当前切入点的类路径中，下面进一步匹配方法

		//获取当前切入点的方法匹配器，准备匹配方法，也就是execution表达式中定义的方法
		MethodMatcher methodMatcher = pc.getMethodMatcher();
		//如果匹配器是匹配所有方法，那么直接返回true
		if (methodMatcher == MethodMatcher.TRUE) {
			// No need to iterate the methods if we're matching any method anyway...
			return true;
		}

		IntroductionAwareMethodMatcher introductionAwareMethodMatcher = null;
		if (methodMatcher instanceof IntroductionAwareMethodMatcher iamm) {
			introductionAwareMethodMatcher = iamm;
		}
		//需要匹配方法的class集合
		Set<Class<?>> classes = new LinkedHashSet<>();
		//如果是普通类，那么加上当类自己的class
		if (!Proxy.isProxyClass(targetClass)) {
			classes.add(ClassUtils.getUserClass(targetClass));
		}
		//加上当前类的所有的接口的class
		classes.addAll(ClassUtils.getAllInterfacesForClassAsSet(targetClass));
		//遍历集合
		for (Class<?> clazz : classes) {
			//获取所有方法
			Method[] methods = ReflectionUtils.getAllDeclaredMethods(clazz);
			//一次匹配
			for (Method method : methods) {
				if (introductionAwareMethodMatcher != null ?
						introductionAwareMethodMatcher.matches(method, targetClass, hasIntroductions) :
						methodMatcher.matches(method, targetClass)) {
					//如果匹配到任何一个，那么返回ture
					return true;
				}
			}
		}
		//最终没有匹配任何一个方法，那么返回false
		return false;
	}


	/**
	 * Can the given advisor apply at all on the given class?
	 * This is an important test as it can be used to optimize
	 * out an advisor for a class.
	 * @param advisor the advisor to check
	 * @param targetClass class we're testing
	 * @return whether the pointcut can apply on any method
	 */
	public static boolean canApply(Advisor advisor, Class<?> targetClass) {
		return canApply(advisor, targetClass, false);
	}

	/**
	 * Can the given advisor apply at all on the given class?
	 * <p>This is an important test as it can be used to optimize out an advisor for a class.
	 * This version also takes into account introductions (for IntroductionAwareMethodMatchers).
	 * @param advisor the advisor to check
	 * @param targetClass class we're testing
	 * @param hasIntroductions whether the advisor chain for this bean includes
	 * any introductions
	 * @return whether the pointcut can apply on any method
	 */
	public static boolean canApply(Advisor advisor, Class<?> targetClass, boolean hasIntroductions) {
		if (advisor instanceof IntroductionAdvisor ia) {
			return ia.getClassFilter().matches(targetClass);
		}
		else if (advisor instanceof PointcutAdvisor pca) {
			return canApply(pca.getPointcut(), targetClass, hasIntroductions);
		}
		else {
			// It doesn't have a pointcut so we assume it applies.
			return true;
		}
	}

	/**
	 * Determine the sublist of the {@code candidateAdvisors} list
	 * that is applicable to the given class.
	 * 确定适用于给定类的候选Advisors列表的子列表
	 *
	 * @param candidateAdvisors the Advisors to evaluate 候选的Advisors
	 * @param clazz the target class  目标的beanClass
	 * @return sublist of Advisors that can apply to an object of the given class
	 * (may be the incoming List as-is) 应用于给定类对象的Advisors子列表，也可能就是传入列表
	 */
	public static List<Advisor> findAdvisorsThatCanApply(List<Advisor> candidateAdvisors, Class<?> clazz) {
		//如果候选Advisors列表示空的，那么直接返回这个空列表
		if (candidateAdvisors.isEmpty()) {
			return candidateAdvisors;
		}
		//合格的Advisors列表
		List<Advisor> eligibleAdvisors = new ArrayList<>();
		/*
		 * 处理引介增强，即<aop:declare-parents/>标签
		 * 子类AnnotationAwareAspectJAutoProxyCreator还会处理@DeclareParents注解
		 */
		//遍历候选列表
		for (Advisor candidate : candidateAdvisors) {
			//如果属于IntroductionAdvisor并且可以增强这个类，这里的canApply方法最终调用下面三个参数的canApply方法，第三个参数为false
			if (candidate instanceof IntroductionAdvisor && canApply(candidate, clazz)) {
				//当前Advisor加入eligibleAdvisors集合
				eligibleAdvisors.add(candidate);
			}
		}
		//是否存在引介增强
		boolean hasIntroductions = !eligibleAdvisors.isEmpty();
		//再次遍历候选列表
		for (Advisor candidate : candidateAdvisors) {
			//如果属于引介增强，表示已经处理过了，跳过
			if (candidate instanceof IntroductionAdvisor) {
				// already processed
				continue;
			}
			//对其他的Advisor同样调用canApply方法判断，第三个参数为此前判断的是否存在引介增强，一般都是false
			if (canApply(candidate, clazz, hasIntroductions)) {
				//当前Advisor加入eligibleAdvisors集合
				eligibleAdvisors.add(candidate);
			}
		}
		return eligibleAdvisors;
	}

	/**
	 * Invoke the given target via reflection, as part of an AOP method invocation.
	 * 作为 AOP 方法调用的一部分，通过反射调用给定目标的原始方法
	 * @param target the target object 被代理目标对象
	 * @param method the method to invoke 调用的方法
	 * @param args the arguments for the method 方法的参数
	 * @return the invocation result, if any 调用结果，可能为null
	 * @throws Throwable if thrown by the target method
	 * @throws org.springframework.aop.AopInvocationException in case of a reflection error
	 */
	@Nullable
	public static Object invokeJoinpointUsingReflection(@Nullable Object target, Method method, Object[] args)
			throws Throwable {

		// Use reflection to invoke the method.
		try {
			//设置方法的可访问属性，method.setAccessible(true)
			ReflectionUtils.makeAccessible(method);
			//反射调用目标对象的原始方法并获取返回值
			return method.invoke(target, args);
		}
		catch (InvocationTargetException ex) {
			// Invoked method threw a checked exception.
			// We must rethrow it. The client won't see the interceptor.
			throw ex.getTargetException();
		}
		catch (IllegalArgumentException ex) {
			throw new AopInvocationException("AOP configuration seems to be invalid: tried calling method [" +
					method + "] on target [" + target + "]", ex);
		}
		catch (IllegalAccessException ex) {
			throw new AopInvocationException("Could not access method [" + method + "]", ex);
		}
	}

}
