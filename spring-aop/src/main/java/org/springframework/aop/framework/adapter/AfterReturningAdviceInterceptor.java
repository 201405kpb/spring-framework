/*
 * Copyright 2002-2020 the original author or authors.
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

package org.springframework.aop.framework.adapter;

import java.io.Serializable;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

import org.springframework.aop.AfterAdvice;
import org.springframework.aop.AfterReturningAdvice;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Interceptor to wrap an {@link org.springframework.aop.AfterReturningAdvice}.
 * Used internally by the AOP framework; application developers should not need
 * to use this class directly.
 *
 * @author Rod Johnson
 * @see MethodBeforeAdviceInterceptor
 * @see ThrowsAdviceInterceptor
 */
@SuppressWarnings("serial")
public class AfterReturningAdviceInterceptor implements MethodInterceptor, AfterAdvice, Serializable {

	private final AfterReturningAdvice advice;


	/**
	 * Create a new AfterReturningAdviceInterceptor for the given advice.
	 * @param advice the AfterReturningAdvice to wrap
	 */
	public AfterReturningAdviceInterceptor(AfterReturningAdvice advice) {
		Assert.notNull(advice, "Advice must not be null");
		this.advice = advice;
	}


	/**
	 * 原理很简单，在目标方法调用成功之后才调用后置通知方法即可
	 * @param mi the method invocation joinpoint 当前ReflectiveMethodInvocation对象
	 * @return 调用下一次proceed方法的返回结果
	 * @throws Throwable
	 */
	@Override
	@Nullable
	public Object invoke(MethodInvocation mi) throws Throwable {
		/*
		 * 首先就继续调用mi.proceed()
		 * 这里的mi就是当前的ReflectiveMethodInvocation对象，也就是递归调用的逻辑
		 * 下一次调用，将会调用下一个拦截器的invoke方法（如果存在）
		 * 当最后一个拦截器执行完毕时，才会通过invokeJoinpoint()反射执行被代理的方法（目标方法）
		 * 然后开始返回
		 */
		Object retVal = mi.proceed();
		/*
		 * 当递归方法返回时，说明目标方法已被执行，这是开始执行后置方法
		 */
		this.advice.afterReturning(retVal, mi.getMethod(), mi.getArguments(), mi.getThis());
		//返回默认就是目标方法的返回值
		return retVal;
	}

}
