/*
 * Copyright 2002-2021 the original author or authors.
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

package org.springframework.aop.aspectj;

import java.io.Serializable;
import java.lang.reflect.Method;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.weaver.tools.JoinPointMatch;

import org.springframework.aop.ProxyMethodInvocation;
import org.springframework.lang.Nullable;

/**
 * Spring AOP around advice (MethodInterceptor) that wraps
 * an AspectJ advice method. Exposes ProceedingJoinPoint.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @since 2.0
 */
@SuppressWarnings("serial")
public class AspectJAroundAdvice extends AbstractAspectJAdvice implements MethodInterceptor, Serializable {

	public AspectJAroundAdvice(
			Method aspectJAroundAdviceMethod, AspectJExpressionPointcut pointcut, AspectInstanceFactory aif) {

		super(aspectJAroundAdviceMethod, pointcut, aif);
	}


	@Override
	public boolean isBeforeAdvice() {
		return false;
	}

	@Override
	public boolean isAfterAdvice() {
		return false;
	}

	@Override
	protected boolean supportsProceedingJoinPoint() {
		return true;
	}

	/**
	 * 它和其他通知的较大区别就是，invoke方法内部并没有调用MethodInvocation的proceed()方法
	 * 而是将MethodInvocation包装成为一个ProceedingJoinPoint，作为环绕通知的参数给使用者
	 * 然后使用者在通知方法中可以调用ProceedingJoinPoint的proceed()方法，其内部还是调用被包装的
	 * MethodInvocation的proceed()方法，这样就将递归调用正常延续了下去
	 *
	 * @param mi 当前ReflectiveMethodInvocation对象
	 * @return 调用环绕通知的返回结果
	 */
	@Override
	public Object invoke(MethodInvocation mi) throws Throwable {
		//如果不属于ProxyMethodInvocation，那么抛出异常
		//ReflectiveMethodInvocation属于该类型
		if (!(mi instanceof ProxyMethodInvocation pmi)) {
			throw new IllegalStateException("MethodInvocation is not a Spring ProxyMethodInvocation: " + mi);
		}
		//将当前MethodInvocation封装成为一个ProceedingJoinPoint连接点
		//ProceedingJoinPoint类型的连接点只能是环绕通知被使用，因为该连接点可以调用proceed()方法
		//其内部还是调用被包装的MethodInvocation的proceed()方法，这样就将递归调用正常延续了下去
		ProceedingJoinPoint pjp = lazyGetProceedingJoinPoint(pmi);
		//获取当前jpMatch
		JoinPointMatch jpm = getJoinPointMatch(pmi);
		//调用invokeAdviceMethod方法，最终会调用invokeAdviceMethodWithGivenArgs方法
		return invokeAdviceMethod(pjp, jpm, null, null);
	}

	/**
	 * Return the ProceedingJoinPoint for the current invocation,
	 * instantiating it lazily if it hasn't been bound to the thread already.
	 * @param rmi the current Spring AOP ReflectiveMethodInvocation,
	 * which we'll use for attribute binding
	 * 当前的ReflectiveMethodInvocation，将用于参数绑定
	 * @return the ProceedingJoinPoint to make available to advice methods
	 * 环绕通知的ProceedingJoinPoint参数
	 */
	protected ProceedingJoinPoint lazyGetProceedingJoinPoint(ProxyMethodInvocation rmi) {
		//实际类型为MethodInvocationProceedingJoinPoint
		return new MethodInvocationProceedingJoinPoint(rmi);
	}

}
