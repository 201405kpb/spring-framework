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

package org.springframework.aop.interceptor;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.annotation.BeanFactoryAnnotationUtils;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.EmbeddedValueResolver;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.lang.Nullable;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.StringValueResolver;
import org.springframework.util.function.SingletonSupplier;

/**
 * Base class for asynchronous method execution aspects, such as
 * {@code org.springframework.scheduling.annotation.AnnotationAsyncExecutionInterceptor}
 * or {@code org.springframework.scheduling.aspectj.AnnotationAsyncExecutionAspect}.
 *
 * <p>Provides support for <i>executor qualification</i> on a method-by-method basis.
 * {@code AsyncExecutionAspectSupport} objects must be constructed with a default {@code
 * Executor}, but each individual method may further qualify a specific {@code Executor}
 * bean to be used when executing it, e.g. through an annotation attribute.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Stephane Nicoll
 * @author He Bo
 * @since 3.1.2
 */
public abstract class AsyncExecutionAspectSupport implements BeanFactoryAware {

	/**
	 * The default name of the {@link TaskExecutor} bean to pick up: "taskExecutor".
	 * <p>Note that the initial lookup happens by type; this is just the fallback
	 * in case of multiple executor beans found in the context.
	 *
	 * @since 4.2.6
	 */
	public static final String DEFAULT_TASK_EXECUTOR_BEAN_NAME = "taskExecutor";


	protected final Log logger = LogFactory.getLog(getClass());

	private final Map<Method, AsyncTaskExecutor> executors = new ConcurrentHashMap<>(16);

	private SingletonSupplier<Executor> defaultExecutor;

	private SingletonSupplier<AsyncUncaughtExceptionHandler> exceptionHandler;

	@Nullable
	private BeanFactory beanFactory;

	@Nullable
	private StringValueResolver embeddedValueResolver;

	/**
	 * Create a new instance with a default {@link AsyncUncaughtExceptionHandler}.
	 *
	 * @param defaultExecutor the {@code Executor} (typically a Spring {@code AsyncTaskExecutor}
	 *                        or {@link java.util.concurrent.ExecutorService}) to delegate to, unless a more specific
	 *                        executor has been requested via a qualifier on the async method, in which case the
	 *                        executor will be looked up at invocation time against the enclosing bean factory
	 */
	public AsyncExecutionAspectSupport(@Nullable Executor defaultExecutor) {
		this.defaultExecutor = new SingletonSupplier<>(defaultExecutor, () -> getDefaultExecutor(this.beanFactory));
		this.exceptionHandler = SingletonSupplier.of(SimpleAsyncUncaughtExceptionHandler::new);
	}

	/**
	 * Create a new {@link AsyncExecutionAspectSupport} with the given exception handler.
	 *
	 * @param defaultExecutor  the {@code Executor} (typically a Spring {@code AsyncTaskExecutor}
	 *                         or {@link java.util.concurrent.ExecutorService}) to delegate to, unless a more specific
	 *                         executor has been requested via a qualifier on the async method, in which case the
	 *                         executor will be looked up at invocation time against the enclosing bean factory
	 * @param exceptionHandler the {@link AsyncUncaughtExceptionHandler} to use
	 */
	public AsyncExecutionAspectSupport(@Nullable Executor defaultExecutor, AsyncUncaughtExceptionHandler exceptionHandler) {
		this.defaultExecutor = new SingletonSupplier<>(defaultExecutor, () -> getDefaultExecutor(this.beanFactory));
		this.exceptionHandler = SingletonSupplier.of(exceptionHandler);
	}


	/**
	 * Configure this aspect with the given executor and exception handler suppliers,
	 * applying the corresponding default if a supplier is not resolvable.
	 * 配置此切面的执行器和异常处理器，如果没有指定，则应用相应的默认值。
	 *
	 * @since 5.1
	 */
	public void configure(@Nullable Supplier<Executor> defaultExecutor,
						  @Nullable Supplier<AsyncUncaughtExceptionHandler> exceptionHandler) {

		//默认执行器，其内部保存了通过配置指定的执行器以及找到的默认执行器
		this.defaultExecutor = new SingletonSupplier<>(defaultExecutor, () -> getDefaultExecutor(this.beanFactory));
		//异常处理器，其内部保存了通过配置指定的处理器以及一个SimpleAsyncUncaughtExceptionHandler类型的处理器
		this.exceptionHandler = new SingletonSupplier<>(exceptionHandler, SimpleAsyncUncaughtExceptionHandler::new);
	}

	/**
	 * Supply the executor to be used when executing async methods.
	 *
	 * @param defaultExecutor the {@code Executor} (typically a Spring {@code AsyncTaskExecutor}
	 *                        or {@link java.util.concurrent.ExecutorService}) to delegate to, unless a more specific
	 *                        executor has been requested via a qualifier on the async method, in which case the
	 *                        executor will be looked up at invocation time against the enclosing bean factory
	 * @see #getExecutorQualifier(Method)
	 * @see #setBeanFactory(BeanFactory)
	 * @see #getDefaultExecutor(BeanFactory)
	 */
	public void setExecutor(Executor defaultExecutor) {
		this.defaultExecutor = SingletonSupplier.of(defaultExecutor);
	}

	/**
	 * Supply the {@link AsyncUncaughtExceptionHandler} to use to handle exceptions
	 * thrown by invoking asynchronous methods with a {@code void} return type.
	 */
	public void setExceptionHandler(AsyncUncaughtExceptionHandler exceptionHandler) {
		this.exceptionHandler = SingletonSupplier.of(exceptionHandler);
	}

	/**
	 * Set the {@link BeanFactory} to be used when looking up executors by qualifier
	 * or when relying on the default executor lookup algorithm.
	 *
	 * @see #findQualifiedExecutor(BeanFactory, String)
	 * @see #getDefaultExecutor(BeanFactory)
	 */
	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
		if (beanFactory instanceof ConfigurableBeanFactory configurableBeanFactory) {
			this.embeddedValueResolver = new EmbeddedValueResolver(configurableBeanFactory);
		}
	}


	/**
	 * Determine the specific executor to use when executing the given method.
	 * 确定执行给定方法时要使用的特定执行器，最好返回AsyncListenableTaskExecutor的实现。
	 *
	 * @return the executor to use (or {@code null}, but just if no default executor is available)
	 * 要使用的执行器（或null，只要没有默认执行器可用）
	 */
	@Nullable
	protected AsyncTaskExecutor determineAsyncExecutor(Method method) {
		AsyncTaskExecutor executor = this.executors.get(method);
		//尝试直接重缓存获取该方法的执行器
		if (executor == null) {
			Executor targetExecutor;
			/*
			 * 返回执行器的限定符或 bean 名称，以执行给定的异步方法时使用，通常以注解属性的形式指定。
			 * 返回空字符串或 null 表示未指定特定执行器，应使用默认执行器。
			 */
			String qualifier = getExecutorQualifier(method);
			if (this.embeddedValueResolver != null && StringUtils.hasLength(qualifier)) {
				qualifier = this.embeddedValueResolver.resolveStringValue(qualifier);
			}
			//如果值不为null或者""
			if (StringUtils.hasLength(qualifier)) {
				//那么根据限定符或者beanName在beanFactory中查找执行器
				targetExecutor = findQualifiedExecutor(this.beanFactory, qualifier);
			} else {
				//获取执行器
				//首先获取指定的自定义执行器，如果没有则返回此前通过getDefaultExecutor方法查找的默认执行器
				targetExecutor = this.defaultExecutor.get();
			}
			//如果执行器为null，则返回null
			if (targetExecutor == null) {
				return null;
			}
			//如果执行器属于AsyncListenableTaskExecutor类型，则直接转型，否则创建一个适配器来包装执行器
			executor = (targetExecutor instanceof AsyncTaskExecutor ?
					(AsyncTaskExecutor) targetExecutor : new TaskExecutorAdapter(targetExecutor));
			//当前方法和对应的执行器存入缓存
			this.executors.put(method, executor);
		}
		return executor;
	}

	/**
	 * Return the qualifier or bean name of the executor to be used when executing the
	 * given async method, typically specified in the form of an annotation attribute.
	 * <p>Returning an empty string or {@code null} indicates that no specific executor has
	 * been specified and that the {@linkplain #setExecutor(Executor) default executor}
	 * should be used.
	 *
	 * @param method the method to inspect for executor qualifier metadata
	 * @return the qualifier if specified, otherwise empty String or {@code null}
	 * @see #determineAsyncExecutor(Method)
	 * @see #findQualifiedExecutor(BeanFactory, String)
	 */
	@Nullable
	protected abstract String getExecutorQualifier(Method method);

	/**
	 * Retrieve a target executor for the given qualifier.
	 *
	 * @param qualifier the qualifier to resolve
	 * @return the target executor, or {@code null} if none available
	 * @see #getExecutorQualifier(Method)
	 * @since 4.2.6
	 */
	@Nullable
	protected Executor findQualifiedExecutor(@Nullable BeanFactory beanFactory, String qualifier) {
		if (beanFactory == null) {
			throw new IllegalStateException("BeanFactory must be set on " + getClass().getSimpleName() +
					" to access qualified executor '" + qualifier + "'");
		}
		return BeanFactoryAnnotationUtils.qualifiedBeanOfType(beanFactory, Executor.class, qualifier);
	}

	/**
	 * Retrieve or build a default executor for this advice instance.
	 * <p>An executor returned from here will be cached for further use.
	 * <p>The default implementation searches for a unique {@link TaskExecutor} bean
	 * in the context, or for an {@link Executor} bean named "taskExecutor" otherwise.
	 * If neither of the two is resolvable, this implementation will return {@code null}.
	 * 默认实现在上下文中搜索唯一的TaskExecutor 类型的bean，或者搜索名为"taskExecutor"的Executor类型的bean。
	 * 如果两者都无法解析，则此实现将返回 null。
	 *
	 * @param beanFactory the BeanFactory to use for a default executor lookup
	 *                    用于默认执行器查找的 BeanFactory
	 * @return the default executor, or {@code null} if none available
	 * 默认执行器，或null（如果没有找到可用执行器）
	 * @see #findQualifiedExecutor(BeanFactory, String)
	 * @see #DEFAULT_TASK_EXECUTOR_BEAN_NAME
	 * @since 4.2.6
	 */
	@Nullable
	protected Executor getDefaultExecutor(@Nullable BeanFactory beanFactory) {
		if (beanFactory != null) {
			try {
				// Search for TaskExecutor bean... not plain Executor since that would
				// match with ScheduledExecutorService as well, which is unusable for
				// our purposes here. TaskExecutor is more clearly designed for it.
				// 搜索唯一的一个TaskExecutor类型的bean并且初始化
				return beanFactory.getBean(TaskExecutor.class);
			} catch (NoUniqueBeanDefinitionException ex) {
				//如果存在多个TaskExecutor类型的bean定义
				logger.debug("Could not find unique TaskExecutor bean. " +
						"Continuing search for an Executor bean named 'taskExecutor'", ex);
				try {
					// 继续尝试搜索名为"taskExecutor"的Executor类型的bean
					return beanFactory.getBean(DEFAULT_TASK_EXECUTOR_BEAN_NAME, Executor.class);
				} catch (NoSuchBeanDefinitionException ex2) {
					//如果还是没有该名称和该类型的bean定义
					if (logger.isInfoEnabled()) {
						logger.info("More than one TaskExecutor bean found within the context, and none is named " +
								"'taskExecutor'. Mark one of them as primary or name it 'taskExecutor' (possibly " +
								"as an alias) in order to use it for async processing: " + ex.getBeanNamesFound());
					}
				}
			} catch (NoSuchBeanDefinitionException ex) {
				//如果不存在TaskExecutor类型的bean定义
				logger.debug("Could not find default TaskExecutor bean. " +
						"Continuing search for an Executor bean named 'taskExecutor'", ex);
				try {
					// 继续尝试搜索名为"taskExecutor"的Executor类型的bean
					return beanFactory.getBean(DEFAULT_TASK_EXECUTOR_BEAN_NAME, Executor.class);
				} catch (NoSuchBeanDefinitionException ex2) {
					logger.info("No task executor bean found for async processing: " +
							"no bean of type TaskExecutor and no bean named 'taskExecutor' either");
				}
				// Giving up -> either using local default executor or none at all...
			}
		}
		return null;
	}


	/**
	 * Delegate for actually executing the given task with the chosen executor.
	 * 使用所选执行器实际执行给定的任务委托。
	 * <p>异步方法支持三种返回值类型（排除void），其他类型的返回值将最终返回null</p>
	 *
	 * @param task       the task to execute 要执行的任务
	 * @param executor   the chosen executor 所选执行器
	 * @param returnType the declared return type (potentially a {@link Future} variant) 声明的返回类型（可能是Feature的各种子类）
	 * @return the execution result (potentially a corresponding {@link Future} handle) 执行结果（可能是相应的Future对象）
	 */
	@Nullable
	@SuppressWarnings("deprecation")
	protected Object doSubmit(Callable<Object> task, AsyncTaskExecutor executor, Class<?> returnType) {
		/*
		 * 首先判断如果返回值类型是CompletableFuture及其子类，那么最终会默认返回一个Spring为我们创建的CompletableFuture对象；
		 */
		if (CompletableFuture.class.isAssignableFrom(returnType)) {
			return executor.submitCompletable(task);
		}
		/*
		 * 其次判断如果返回值类型是ListenableFuture及其子类，那么最终会默认返回一个Spring为我们创建的ListenableFutureTask对象。
		 */
		else if (org.springframework.util.concurrent.ListenableFuture.class.isAssignableFrom(returnType)) {
			return ((org.springframework.core.task.AsyncListenableTaskExecutor) executor).submitListenable(task);
		}
		/*
		 * 随后判断如果异步方法返回值类型是Future及其子类，那么最终会默认返回一个Spring为我们创建的FutureTask对象；
		 */
		else if (Future.class.isAssignableFrom(returnType)) {
			return executor.submit(task);
		}
		/*最后，如果以上判断都不满足，即如果异步方法指定了返回其它类型，那么最终将返回一个null。
		  最终返回的结果对象，和我们在方法中返回的对象不是同一个。*/
		else if (void.class == returnType) {
			executor.submit(task);
			return null;
		} else {
			throw new IllegalArgumentException(
					"Invalid return type for async method (only Future and void supported): " + returnType);
		}
	}

	/**
	 * Handles a fatal error thrown while asynchronously invoking the specified
	 * {@link Method}.
	 * 处理异步调用指定方法时引发的致命错误。
	 * <p>If the return type of the method is a {@link Future} object, the original
	 * exception can be propagated by just throwing it at the higher level. However,
	 * for all other cases, the exception will not be transmitted back to the client.
	 * In that later case, the current {@link AsyncUncaughtExceptionHandler} will be
	 * used to manage such exception.
	 * 如果方法的返回类型是 Future 对象，则只需在较高级别引发原始异常，就可以传播原始异常。
	 * 但是，对于所有其他情况，异常不会传输回客户端，而是采用AsyncUncaughtExceptionHandler来管理此类异常。
	 *
	 * @param ex     the exception to handle 要处理的异常
	 * @param method the method that was invoked 调用的方法
	 * @param params the parameters used to invoke the method 用于调用方法的参数
	 */
	protected void handleError(Throwable ex, Method method, Object... params) throws Exception {
		if (Future.class.isAssignableFrom(method.getReturnType())) {
			ReflectionUtils.rethrowException(ex);
		} else {
			//获取异常处理器并且执行调用
			try {
				this.exceptionHandler.obtain().handleUncaughtException(ex, method, params);
			} catch (Throwable ex2) {
				//如果异常处理器抛出异常，那么该异常仍然不会被抛出，仅仅是记录日志！
				logger.warn("Exception handler for async method '" + method.toGenericString() +
						"' threw unexpected exception itself", ex2);
			}
		}
	}

}
