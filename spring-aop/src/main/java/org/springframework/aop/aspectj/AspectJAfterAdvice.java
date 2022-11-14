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
 * Spring AOP advice wrapping an AspectJ after advice method.
 *
 * @author Rod Johnson
 * @since 2.0
 */
@SuppressWarnings("serial")
public class AspectJAfterAdvice extends AbstractAspectJAdvice
		implements MethodInterceptor, AfterAdvice, Serializable {

	public AspectJAfterAdvice(
			Method aspectJBeforeAdviceMethod, AspectJExpressionPointcut pointcut, AspectInstanceFactory aif) {

		super(aspectJBeforeAdviceMethod, pointcut, aif);
	}


	/**
	 * 使用try finally块，将mi.proceed()置于try块中，将最终通知方法的调用置于finally块中
	 * 当无论目标方法或者前面的通知方法调用成功还是抛出异常，都会执行最终通知，因为它在finally块中
	 * 并且最终通知在前置通知、目标方法、后置/异常通知执行之后才会执行
	 *
	 * @param mi 当前ReflectiveMethodInvocation对象
	 * @return 调用下一次proceed方法的返回结果
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
		//在前置通知、目标方法、后置/异常通知执行之后才会执行最终通知，因为它在finally块中
		finally {
			//内部调用的invokeAdviceMethod方法，最终会调用invokeAdviceMethodWithGivenArgs方法
			invokeAdviceMethod(getJoinPointMatch(), null, null);
		}
	}

	@Override
	public boolean isBeforeAdvice() {
		return false;
	}

	@Override
	public boolean isAfterAdvice() {
		return true;
	}

}
