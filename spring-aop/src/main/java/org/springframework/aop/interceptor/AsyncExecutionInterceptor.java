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

package org.springframework.aop.interceptor;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.Ordered;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;

/**
 * AOP Alliance {@code MethodInterceptor} that processes method invocations
 * asynchronously, using a given {@link org.springframework.core.task.AsyncTaskExecutor}.
 * Typically used with the {@link org.springframework.scheduling.annotation.Async} annotation.
 *
 * <p>In terms of target method signatures, any parameter types are supported.
 * However, the return type is constrained to either {@code void} or
 * {@code java.util.concurrent.Future}. In the latter case, the Future handle
 * returned from the proxy will be an actual asynchronous Future that can be used
 * to track the result of the asynchronous method execution. However, since the
 * target method needs to implement the same signature, it will have to return
 * a temporary Future handle that just passes the return value through
 * (like Spring's {@link org.springframework.scheduling.annotation.AsyncResult}
 * or EJB's {@code jakarta.ejb.AsyncResult}).
 *
 * <p>When the return type is {@code java.util.concurrent.Future}, any exception thrown
 * during the execution can be accessed and managed by the caller. With {@code void}
 * return type however, such exceptions cannot be transmitted back. In that case an
 * {@link AsyncUncaughtExceptionHandler} can be registered to process such exceptions.
 *
 * <p>Note: the {@code AnnotationAsyncExecutionInterceptor} subclass is preferred
 * due to its support for executor qualification in conjunction with Spring's
 * {@code @Async} annotation.
 *
 * @author Juergen Hoeller
 * @author Chris Beams
 * @author Stephane Nicoll
 * @since 3.0
 * @see org.springframework.scheduling.annotation.Async
 * @see org.springframework.scheduling.annotation.AsyncAnnotationAdvisor
 * @see org.springframework.scheduling.annotation.AnnotationAsyncExecutionInterceptor
 */
public class AsyncExecutionInterceptor extends AsyncExecutionAspectSupport implements MethodInterceptor, Ordered {

	/**
	 * Create a new instance with a default {@link AsyncUncaughtExceptionHandler}.
	 * @param defaultExecutor the {@link Executor} (typically a Spring {@link AsyncTaskExecutor}
	 * or {@link java.util.concurrent.ExecutorService}) to delegate to; a local
	 * executor for this interceptor will be built otherwise
	 */
	public AsyncExecutionInterceptor(@Nullable Executor defaultExecutor) {
		super(defaultExecutor);
	}

	/**
	 * Create a new {@code AsyncExecutionInterceptor}.
	 * @param defaultExecutor the {@link Executor} (typically a Spring {@link AsyncTaskExecutor}
	 * or {@link java.util.concurrent.ExecutorService}) to delegate to; a local
	 * executor for this interceptor will be built otherwise
	 * @param exceptionHandler the {@link AsyncUncaughtExceptionHandler} to use
	 */
	public AsyncExecutionInterceptor(@Nullable Executor defaultExecutor, AsyncUncaughtExceptionHandler exceptionHandler) {
		super(defaultExecutor, exceptionHandler);
	}


	/**
	 * Intercept the given method invocation, submit the actual calling of the method to
	 * the correct task executor and return immediately to the caller.
	 * 截获给定的方法调用，将方法的实际调用提交到正确的任务执行器，并立即返回给调用方。
	 * @param invocation the method to intercept and make asynchronous
	 * 拦截的进行异步调用的方法
	 * @return {@link Future} if the original method returns {@code Future}; {@code null}
	 * otherwise.
	 * 如果原始方法返回"Future"，则返回Future，否则返回null
	 */
	@Override
	@Nullable
	public Object invoke(final MethodInvocation invocation) throws Throwable {
		Class<?> targetClass = (invocation.getThis() != null ? AopUtils.getTargetClass(invocation.getThis()) : null);
		//如果当前方法重写了父类的方法，则使用子类的，即最终被执行的方法
		Method specificMethod = ClassUtils.getMostSpecificMethod(invocation.getMethod(), targetClass);
		//查找原始方法而非编译器为我们生成的方法，即桥接方法，如果不是桥接方法，那么返回参数方法
		final Method userDeclaredMethod = BridgeMethodResolver.findBridgedMethod(specificMethod);
		//根据方法，确定一个要使用的执行器
		AsyncTaskExecutor executor = determineAsyncExecutor(userDeclaredMethod);
		//如果为null，直接抛出异常
		if (executor == null) {
			throw new IllegalStateException(
					"No executor specified and no default executor set on AsyncExecutionInterceptor either");
		}
		//创建线程任务
		Callable<Object> task = () -> {
			try {
				//任务内部就是invocation.proceed()，也就是向后执行其他拦截器或者目标方法的逻辑
				Object result = invocation.proceed();
				//如果返回结果属于Future，那么等待获取结果
				//如果不是Future，那么直接返回null，也就是说对于异步任务方法的返回值如果不是Feature类型，那么将始终获取null
				if (result instanceof Future) {
					return ((Future<?>) result).get();
				}
				//通过异常处理器来处理异常
			} catch (ExecutionException ex) {
				handleError(ex.getCause(), userDeclaredMethod, invocation.getArguments());
			} catch (Throwable ex) {
				handleError(ex, userDeclaredMethod, invocation.getArguments());
			}
			//返回null
			return null;
		};
		//使用所选执行器实际执行给定任务的委托
		return doSubmit(task, executor, invocation.getMethod().getReturnType());
	}

	/**
	 * Get the qualifier for a specific executor to use when executing the given
	 * method.
	 * <p>The default implementation of this method is effectively a no-op.
	 * <p>Subclasses may override this method to provide support for extracting
	 * qualifier information &mdash; for example, via an annotation on the given
	 * method.
	 * @return always {@code null}
	 * @since 3.1.2
	 * @see #determineAsyncExecutor(Method)
	 */
	@Override
	@Nullable
	protected String getExecutorQualifier(Method method) {
		return null;
	}

	/**
	 * This implementation searches for a unique {@link org.springframework.core.task.TaskExecutor}
	 * bean in the context, or for an {@link Executor} bean named "taskExecutor" otherwise.
	 * If neither of the two is resolvable (e.g. if no {@code BeanFactory} was configured at all),
	 * this implementation falls back to a newly created {@link SimpleAsyncTaskExecutor} instance
	 * for local use if no default could be found.
	 * @see #DEFAULT_TASK_EXECUTOR_BEAN_NAME
	 */
	@Override
	@Nullable
	protected Executor getDefaultExecutor(@Nullable BeanFactory beanFactory) {
		Executor defaultExecutor = super.getDefaultExecutor(beanFactory);
		return (defaultExecutor != null ? defaultExecutor : new SimpleAsyncTaskExecutor());
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}

}
