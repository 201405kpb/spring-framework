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

import org.springframework.aop.BeforeAdvice;
import org.springframework.aop.MethodBeforeAdvice;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Interceptor to wrap a {@link MethodBeforeAdvice}.
 * <p>Used internally by the AOP framework; application developers should not
 * need to use this class directly.
 *
 * @author Rod Johnson
 * @see AfterReturningAdviceInterceptor
 * @see ThrowsAdviceInterceptor
 */
@SuppressWarnings("serial")
public class MethodBeforeAdviceInterceptor implements MethodInterceptor, BeforeAdvice, Serializable {

	private final MethodBeforeAdvice advice;


	/**
	 * Create a new MethodBeforeAdviceInterceptor for the given advice.
	 * @param advice the MethodBeforeAdvice to wrap
	 */
	public MethodBeforeAdviceInterceptor(MethodBeforeAdvice advice) {
		Assert.notNull(advice, "Advice must not be null");
		this.advice = advice;
	}

	/**
	 * MethodBeforeAdviceInterceptor前置通知拦截器的方法
	 * <p>
	 * 原理很简单，在目标方法调用之前调用前置通知方法即可
	 *
	 * @param mi 当前ReflectiveMethodInvocation对象
	 * @return 调用下一次proceed方法的返回结果
	 */
	@Override
	public Object invoke(MethodInvocation mi) throws Throwable {
		//通过当前通知实例调用前置通知方法，此时目标方法未被执行
		this.advice.before(mi.getMethod(), mi.getArguments(), mi.getThis());
		/*
		 * 继续调用mi.proceed()
		 * 这里的mi就是当前的ReflectiveMethodInvocation对象，也就是递归调用的逻辑
		 * 下一次调用，将会调用下一个拦截器的invoke方法（如果存在）
		 * 当最后一个拦截器执行完毕时，才会通过invokeJoinpoint()反射执行被代理的方法（目标方法）
		 * 然后开始返回
		 */
		return mi.proceed();
	}

}
