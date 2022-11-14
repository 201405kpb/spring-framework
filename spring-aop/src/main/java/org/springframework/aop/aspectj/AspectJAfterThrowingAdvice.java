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

package org.springframework.aop.aspectj;

import java.io.Serializable;
import java.lang.reflect.Method;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

import org.springframework.aop.AfterAdvice;
import org.springframework.lang.Nullable;

/**
 * Spring AOP advice wrapping an AspectJ after-throwing advice method.
 *
 * @author Rod Johnson
 * @since 2.0
 */
@SuppressWarnings("serial")
public class AspectJAfterThrowingAdvice extends AbstractAspectJAdvice
		implements MethodInterceptor, AfterAdvice, Serializable {

	public AspectJAfterThrowingAdvice(
			Method aspectJBeforeAdviceMethod, AspectJExpressionPointcut pointcut, AspectInstanceFactory aif) {

		super(aspectJBeforeAdviceMethod, pointcut, aif);
	}


	@Override
	public boolean isBeforeAdvice() {
		return false;
	}

	@Override
	public boolean isAfterAdvice() {
		return true;
	}

	@Override
	public void setThrowingName(String name) {
		setThrowingNameNoCheck(name);
	}

	/**
	 * 使用try catch块，将mi.proceed()置于try块中，将异常通知方法的调用置于catch块中
	 * 当目标方法或者前面的通知方法调用抛出异常时，就可能会执行异常通知
	 * 原理很简单，在目标方法调用抛出异常之后才调用异常通知方法即可
	 * @param mi the method invocation joinpoint 当前ReflectiveMethodInvocation对象
	 * @return 调用下一次proceed方法的返回结果
	 * @throws Throwable
	 */
	@Override
	public Object invoke(MethodInvocation mi) throws Throwable {
		try {
			/*
			 * 首先就继续调用mi.proceed()
			 * 这里的mi就是当前的ReflectiveMethodInvocation对象，也就是递归调用的逻辑
			 * 下一次调用，将会调用下一个拦截器的invoke方法（如果存在）
			 * 当最后一个拦截器执行完毕时，才会通过invokeJoinpoint()反射执行被代理的方法（目标方法）
			 * 然后开始返回
			 */
			return mi.proceed();
		}
		//当目标方法或者前面的通知方法调用抛出异常时，异常会被捕获
		//就可能会执行异常通知
		catch (Throwable ex) {
			//如果抛出的异常类型和当前通知方法参数需要的异常类型匹配，那么可以调用当前通知方法，否则不会调用
			if (shouldInvokeOnThrowing(ex)) {
				//内部调用的invokeAdviceMethod方法，最终会调用invokeAdviceMethodWithGivenArgs方法
				invokeAdviceMethod(getJoinPointMatch(), null, ex);
			}
			//继续抛出异常
			throw ex;
		}
	}

	/**
	 * In AspectJ semantics, after throwing advice that specifies a throwing clause
	 * is only invoked if the thrown exception is a subtype of the given throwing type.
	 */
	private boolean shouldInvokeOnThrowing(Throwable ex) {
		return getDiscoveredThrowingType().isAssignableFrom(ex.getClass());
	}

}
