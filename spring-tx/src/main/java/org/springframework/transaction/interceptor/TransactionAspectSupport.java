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

package org.springframework.transaction.interceptor;

import java.lang.reflect.Method;
import java.util.Properties;
import java.util.concurrent.ConcurrentMap;

import io.vavr.control.Try;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.reactive.AwaitKt;
import kotlinx.coroutines.reactive.ReactiveFlowKt;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Publisher;
import org.springframework.transaction.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.BeanFactoryAnnotationUtils;
import org.springframework.core.CoroutinesUtils;
import org.springframework.core.KotlinDetector;
import org.springframework.core.MethodParameter;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.ReactiveAdapter;
import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.lang.Nullable;
import org.springframework.transaction.reactive.TransactionContextManager;
import org.springframework.transaction.support.CallbackPreferringPlatformTransactionManager;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ConcurrentReferenceHashMap;
import org.springframework.util.StringUtils;

/**
 * Base class for transactional aspects, such as the {@link TransactionInterceptor}
 * or an AspectJ aspect.
 *
 * <p>This enables the underlying Spring transaction infrastructure to be used easily
 * to implement an aspect for any aspect system.
 *
 * <p>Subclasses are responsible for calling methods in this class in the correct order.
 *
 * <p>If no transaction name has been specified in the {@link TransactionAttribute},
 * the exposed name will be the {@code fully-qualified class name + "." + method name}
 * (by default).
 *
 * <p>Uses the <b>Strategy</b> design pattern. A {@link PlatformTransactionManager} or
 * {@link ReactiveTransactionManager} implementation will perform the actual transaction
 * management, and a {@link TransactionAttributeSource} (e.g. annotation-based) is used
 * for determining transaction definitions for a particular class or method.
 *
 * <p>A transaction aspect is serializable if its {@code TransactionManager} and
 * {@code TransactionAttributeSource} are serializable.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Stéphane Nicoll
 * @author Sam Brannen
 * @author Mark Paluch
 * @author Sebastien Deleuze
 * @since 1.1
 * @see PlatformTransactionManager
 * @see ReactiveTransactionManager
 * @see #setTransactionManager
 * @see #setTransactionAttributes
 * @see #setTransactionAttributeSource
 */
public abstract class TransactionAspectSupport implements BeanFactoryAware, InitializingBean {

	// NOTE: This class must not implement Serializable because it serves as base
	// class for AspectJ aspects (which are not allowed to implement Serializable)!


	/**
	 * Key to use to store the default transaction manager.
	 */
	private static final Object DEFAULT_TRANSACTION_MANAGER_KEY = new Object();

	private static final String COROUTINES_FLOW_CLASS_NAME = "kotlinx.coroutines.flow.Flow";

	/**
	 * Vavr library present on the classpath?
	 */
	private static final boolean vavrPresent = ClassUtils.isPresent(
			"io.vavr.control.Try", TransactionAspectSupport.class.getClassLoader());

	/**
	 * Reactive Streams API present on the classpath?
	 */
	private static final boolean reactiveStreamsPresent =
			ClassUtils.isPresent("org.reactivestreams.Publisher", TransactionAspectSupport.class.getClassLoader());

	/**
	 * Holder to support the {@code currentTransactionStatus()} method,
	 * and to support communication between different cooperating advices
	 * (e.g. before and after advice) if the aspect involves more than a
	 * single method (as will be the case for around advice).
	 * 一个ThreadLocal 持有器，用于持有事务执行的相关上下文信息 TransactionInfo,
	 */
	private static final ThreadLocal<TransactionInfo> transactionInfoHolder =
			new NamedThreadLocal<>("Current aspect-driven transaction");


	/**
	 * 供子类用于获取当前 TransactionInfo 的方法。通常仅用于子类需要多个方法
	 * 协调完成事务管理的情形，比如到AspectJ before/after advice 是不同方法
	 * 但是要管理同一个事务的情景。而对于 around advice，比如符合AOP联盟
	 * 规范的这种MethodInterceptor，能在整个切面方法整个生命周期过程中持有
	 * 对 TransactionInfo 的引用，所以不需要该机制。
	 * 这里需要注意的是，Spring 事务管理是线程绑定的，而非跨线程的。从上面
	 * transactionInfoHolder 变量的定义可以看出来。
	 * Subclasses can use this to return the current TransactionInfo.
	 * Only subclasses that cannot handle all operations in one method,
	 * such as an AspectJ aspect involving distinct before and after advice,
	 * need to use this mechanism to get at the current TransactionInfo.
	 * An around advice such as an AOP Alliance MethodInterceptor can hold a
	 * reference to the TransactionInfo throughout the aspect method.
	 * <p>A TransactionInfo will be returned even if no transaction was created.
	 * The {@code TransactionInfo.hasTransaction()} method can be used to query this.
	 * <p>To find out about specific transaction characteristics, consider using
	 * TransactionSynchronizationManager's {@code isSynchronizationActive()}
	 * and/or {@code isActualTransactionActive()} methods.
	 * @return the TransactionInfo bound to this thread, or {@code null} if none
	 * @see TransactionInfo#hasTransaction()
	 * @see org.springframework.transaction.support.TransactionSynchronizationManager#isSynchronizationActive()
	 * @see org.springframework.transaction.support.TransactionSynchronizationManager#isActualTransactionActive()
	 */
	@Nullable
	protected static TransactionInfo currentTransactionInfo() throws NoTransactionException {
		return transactionInfoHolder.get();
	}

	/**
	 * Return the transaction status of the current method invocation.
	 * Mainly intended for code that wants to set the current transaction
	 * rollback-only but not throw an application exception.
	 * @throws NoTransactionException if the transaction info cannot be found,
	 * because the method was invoked outside an AOP invocation context
	 */
	public static TransactionStatus currentTransactionStatus() throws NoTransactionException {
		TransactionInfo info = currentTransactionInfo();
		if (info == null || info.transactionStatus == null) {
			throw new NoTransactionException("No transaction aspect-managed TransactionStatus in scope");
		}
		return info.transactionStatus;
	}


	protected final Log logger = LogFactory.getLog(getClass());

	@Nullable
	private final ReactiveAdapterRegistry reactiveAdapterRegistry;

	// 事务管理器bean的名称
	@Nullable
	private String transactionManagerBeanName;

	// 事务管理器bean对象本身
	@Nullable
	private TransactionManager transactionManager;

	// 用于获取事务属性的来源对象
	@Nullable
	private TransactionAttributeSource transactionAttributeSource;

	// bean容器自身
	@Nullable
	private BeanFactory beanFactory;


	// 事务管理器缓存，对于非缺省事务管理器，key是事务管理器bean定义上的@Qualifier.value ，对于缺省事务管理器，key是一个对象，由 DEFAULT_TRANSACTION_MANAGER_KEY 定义
	private final ConcurrentMap<Object, TransactionManager> transactionManagerCache =
			new ConcurrentReferenceHashMap<>(4);

	private final ConcurrentMap<Method, ReactiveTransactionSupport> transactionSupportCache =
			new ConcurrentReferenceHashMap<>(1024);


	protected TransactionAspectSupport() {
		if (reactiveStreamsPresent) {
			this.reactiveAdapterRegistry = ReactiveAdapterRegistry.getSharedInstance();
		}
		else {
			this.reactiveAdapterRegistry = null;
		}
	}


	/**
	 * Specify the name of the default transaction manager bean.
	 * <p>This can either point to a traditional {@link PlatformTransactionManager} or a
	 * {@link ReactiveTransactionManager} for reactive transaction management.
	 */
	public void setTransactionManagerBeanName(@Nullable String transactionManagerBeanName) {
		this.transactionManagerBeanName = transactionManagerBeanName;
	}

	/**
	 * Return the name of the default transaction manager bean.
	 */
	@Nullable
	protected final String getTransactionManagerBeanName() {
		return this.transactionManagerBeanName;
	}

	/**
	 * Specify the <em>default</em> transaction manager to use to drive transactions.
	 * <p>This can either be a traditional {@link PlatformTransactionManager} or a
	 * {@link ReactiveTransactionManager} for reactive transaction management.
	 * <p>The default transaction manager will be used if a <em>qualifier</em>
	 * has not been declared for a given transaction or if an explicit name for the
	 * default transaction manager bean has not been specified.
	 * @see #setTransactionManagerBeanName
	 */
	public void setTransactionManager(@Nullable TransactionManager transactionManager) {
		this.transactionManager = transactionManager;
	}

	/**
	 * Return the default transaction manager, or {@code null} if unknown.
	 * <p>This can either be a traditional {@link PlatformTransactionManager} or a
	 * {@link ReactiveTransactionManager} for reactive transaction management.
	 */
	@Nullable
	public TransactionManager getTransactionManager() {
		return this.transactionManager;
	}

	/**
	 * Set properties with method names as keys and transaction attribute
	 * descriptors (parsed via TransactionAttributeEditor) as values:
	 * e.g. key = "myMethod", value = "PROPAGATION_REQUIRED,readOnly".
	 * <p>Note: Method names are always applied to the target class,
	 * no matter if defined in an interface or the class itself.
	 * <p>Internally, a NameMatchTransactionAttributeSource will be
	 * created from the given properties.
	 * @see #setTransactionAttributeSource
	 * @see TransactionAttributeEditor
	 * @see NameMatchTransactionAttributeSource
	 */
	public void setTransactionAttributes(Properties transactionAttributes) {
		NameMatchTransactionAttributeSource tas = new NameMatchTransactionAttributeSource();
		tas.setProperties(transactionAttributes);
		this.transactionAttributeSource = tas;
	}

	/**
	 * Set multiple transaction attribute sources which are used to find transaction
	 * attributes. Will build a CompositeTransactionAttributeSource for the given sources.
	 * @see CompositeTransactionAttributeSource
	 * @see MethodMapTransactionAttributeSource
	 * @see NameMatchTransactionAttributeSource
	 * @see org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
	 */
	public void setTransactionAttributeSources(TransactionAttributeSource... transactionAttributeSources) {
		this.transactionAttributeSource = new CompositeTransactionAttributeSource(transactionAttributeSources);
	}

	/**
	 * Set the transaction attribute source which is used to find transaction
	 * attributes. If specifying a String property value, a PropertyEditor
	 * will create a MethodMapTransactionAttributeSource from the value.
	 * @see TransactionAttributeSourceEditor
	 * @see MethodMapTransactionAttributeSource
	 * @see NameMatchTransactionAttributeSource
	 * @see org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
	 */
	public void setTransactionAttributeSource(@Nullable TransactionAttributeSource transactionAttributeSource) {
		this.transactionAttributeSource = transactionAttributeSource;
	}

	/**
	 * Return the transaction attribute source.
	 */
	@Nullable
	public TransactionAttributeSource getTransactionAttributeSource() {
		return this.transactionAttributeSource;
	}

	/**
	 * Set the BeanFactory to use for retrieving {@code TransactionManager} beans.
	 */
	@Override
	public void setBeanFactory(@Nullable BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	/**
	 * Return the BeanFactory to use for retrieving {@code TransactionManager} beans.
	 */
	@Nullable
	protected final BeanFactory getBeanFactory() {
		return this.beanFactory;
	}

	/**
	 * Check that required properties were set.
	 * 接口 InitializingBean 定义的初始化方法，这里的实现,主要是确保属性 transactionManger, transactionAttributeSource 被设置,不为 null
	 */
	@Override
	public void afterPropertiesSet() {
		if (getTransactionManager() == null && this.beanFactory == null) {
			throw new IllegalStateException(
					"Set the 'transactionManager' property or make sure to run within a BeanFactory " +
					"containing a TransactionManager bean!");
		}
		if (getTransactionAttributeSource() == null) {
			throw new IllegalStateException(
					"Either 'transactionAttributeSource' or 'transactionAttributes' is required: " +
					"If there are no transactional methods, then don't use a transaction aspect.");
		}
	}


	/**
	 * General delegate for around-advice-based subclasses, delegating to several other template
	 * methods on this class. Able to handle {@link CallbackPreferringPlatformTransactionManager}
	 * as well as regular {@link PlatformTransactionManager} implementations and
	 * {@link ReactiveTransactionManager} implementations for reactive return types.
	 * 用于根据各种配置以及事务管理器实现事务处理
	 * @param method the Method being invoked 正在调用的目标方法
	 * @param targetClass the target class that we're invoking the method on 正在调用的方法的目标类
	 * @param invocation the callback to use for proceeding with the target invocation 方法调用服务，用于向后执行AOP调用
	 * @return the return value of the method, if any 该方法的返回值
	 * @throws Throwable propagated from the target invocation 从目标调用传播的异常
	 */
	@Nullable
	protected Object invokeWithinTransaction(Method method, @Nullable Class<?> targetClass,
											 final InvocationCallback invocation) throws Throwable {
		/*
		 * 1 获取事务属性源，也就是transactionAttributeSource属性，这个属性源在创建bean定义的时候就被设置。
		 *
		 * 基于注解的属性源是AnnotationTransactionAttributeSource
		 * 基于XML标签的属性源是NameMatchTransactionAttributeSource
		 */
		TransactionAttributeSource tas = getTransactionAttributeSource();
		/*
		 * 2 调用属性源的getTransactionAttribute方法获取适用于该方法的事务属性，如果TransactionAttribute为null，则该方法为非事务方法。
		 *
		 * 对于AnnotationTransactionAttributeSource，它的getTransactionAttribute
		 * 方法我们在此前的BeanFactoryTransactionAttributeSourceAdvisor部分就讲过。
		 * 对于NameMatchTransactionAttributeSource，它的getTransactionAttribute就是通过方法名进行匹配。
		 */
		final TransactionAttribute txAttr = (tas != null ? tas.getTransactionAttribute(method, targetClass) : null);

		/*
		 * 3 确定给定事务属性所使用的特定事务管理器。
		 */
		final TransactionManager tm = determineTransactionManager(txAttr);
		/*这里用于支持Spring5的响应式编程……*/
		if (this.reactiveAdapterRegistry != null && tm instanceof ReactiveTransactionManager) {
			boolean isSuspendingFunction = KotlinDetector.isSuspendingFunction(method);
			boolean hasSuspendingFlowReturnType = isSuspendingFunction &&
					COROUTINES_FLOW_CLASS_NAME.equals(new MethodParameter(method, -1).getParameterType().getName());
			if (isSuspendingFunction && !(invocation instanceof CoroutinesInvocationCallback)) {
				throw new IllegalStateException("Coroutines invocation not supported: " + method);
			}
			CoroutinesInvocationCallback corInv = (isSuspendingFunction ? (CoroutinesInvocationCallback) invocation : null);

			ReactiveTransactionSupport txSupport = this.transactionSupportCache.computeIfAbsent(method, key -> {
				Class<?> reactiveType =
						(isSuspendingFunction ? (hasSuspendingFlowReturnType ? Flux.class : Mono.class) : method.getReturnType());
				ReactiveAdapter adapter = this.reactiveAdapterRegistry.getAdapter(reactiveType);
				if (adapter == null) {
					throw new IllegalStateException("Cannot apply reactive transaction to non-reactive return type: " +
							method.getReturnType());
				}
				return new ReactiveTransactionSupport(adapter);
			});

			InvocationCallback callback = invocation;
			if (corInv != null) {
				callback = () -> KotlinDelegate.invokeSuspendingFunction(method, corInv);
			}
			Object result = txSupport.invokeWithinTransaction(method, targetClass, callback, txAttr, (ReactiveTransactionManager) tm);
			if (corInv != null) {
				Publisher<?> pr = (Publisher<?>) result;
				return (hasSuspendingFlowReturnType ? KotlinDelegate.asFlow(pr) :
						KotlinDelegate.awaitSingleOrNull(pr, corInv.getContinuation()));
			}
			return result;
		}
		//强制转换为PlatformTransactionManager，如果事务管理器不是PlatformTransactionManager类型，将会抛出异常
		PlatformTransactionManager ptm = asPlatformTransactionManager(tm);
		//获取方法的信息，也就是此前设置的DefaultTransactionAttribute中的descriptor，或者方法的全路径类名，主要用于记录日志
		final String joinpointIdentification = methodIdentification(method, targetClass, txAttr);
		/*
		 * 如果事务属性为null，或者获取事务管理器不是回调事务管理器，那么走下面的逻辑
		 * 这是最常见的标准声明式事务的逻辑，比如DataSourceTransactionManager
		 */
		if (txAttr == null || !(ptm instanceof CallbackPreferringPlatformTransactionManager)) {
			/*
			 * 5 根据给定的事务属性、事务管理器、方法连接点描述字符串(全限定方法名)信息创建一个事务信息对象
			 * 将会解析各种事务属性，应用不同的流程，比如创建新事物、加入事务、抛出异常等
			 */
			TransactionInfo txInfo = createTransactionIfNecessary(ptm, txAttr, joinpointIdentification);
			//
			Object retVal;
			try {
				/*
				 * 5 invocation参数传递的是一个lambda表达式，传递的是一个方法调用invocation::proceed
				 * 因此这里的proceedWithInvocation实际上是执行invocation的proceed方法
				 * 即这里将会继续向后调用链中的下一个拦截器，最后还会导致目标方法的调用（执行业务逻辑），然后会倒序返回
				 */
				retVal = invocation.proceedWithInvocation();
			} catch (Throwable ex) {
				/*
				 * 6 处理执行过程中抛出的异常，可能会有事务的回滚或者提交
				 */
				completeTransactionAfterThrowing(txInfo, ex);
				//抛出异常
				throw ex;
			} finally {
				/*
				 * 7 清除当前绑定的事务信息、恢复老的事务信息绑定
				 * 无论正常还是异常完成，都会走这一步
				 */
				cleanupTransactionInfo(txInfo);
			}
			//正常完成之后的逻辑

			//类路径上存在Vavr库时的处理，这个需要引入外部依赖，因此一般都是不存在的
			if (retVal != null && vavrPresent && VavrDelegate.isVavrTry(retVal)) {
				// Set rollback-only in case of Vavr failure matching our rollback rules...
				TransactionStatus status = txInfo.getTransactionStatus();
				if (status != null && txAttr != null) {
					retVal = VavrDelegate.evaluateTryFailure(retVal, txAttr, status);
				}
			}
			/*
			 * 7 目标方法成功执行之后，返回结果之前提交事务
			 */
			commitTransactionAfterReturning(txInfo);
			/*
			 * 8 事务正常处理完毕，返回执行结果
			 */
			return retVal;
		}
		/*
		 * 如果txAttr不为null并且事务管理器属于CallbackPreferringPlatformTransactionManager，走下面的逻辑
		 * 只有使用WebSphereUowTransactionManager时才会走这个逻辑，不常见，甚至几乎见不到
		 */
		else {
			Object result;
			final ThrowableHolder throwableHolder = new ThrowableHolder();

			// It's a CallbackPreferringPlatformTransactionManager: pass a TransactionCallback in.
			try {
				result = ((CallbackPreferringPlatformTransactionManager) ptm).execute(txAttr, status -> {
					TransactionInfo txInfo = prepareTransactionInfo(ptm, txAttr, joinpointIdentification, status);
					try {
						Object retVal = invocation.proceedWithInvocation();
						if (retVal != null && vavrPresent && VavrDelegate.isVavrTry(retVal)) {
							// Set rollback-only in case of Vavr failure matching our rollback rules...
							retVal = VavrDelegate.evaluateTryFailure(retVal, txAttr, status);
						}
						return retVal;
					} catch (Throwable ex) {
						if (txAttr.rollbackOn(ex)) {
							// A RuntimeException: will lead to a rollback.
							if (ex instanceof RuntimeException) {
								throw (RuntimeException) ex;
							} else {
								throw new ThrowableHolderException(ex);
							}
						} else {
							// A normal return value: will lead to a commit.
							throwableHolder.throwable = ex;
							return null;
						}
					} finally {
						cleanupTransactionInfo(txInfo);
					}
				});
			} catch (ThrowableHolderException ex) {
				throw ex.getCause();
			} catch (TransactionSystemException ex2) {
				if (throwableHolder.throwable != null) {
					logger.error("Application exception overridden by commit exception", throwableHolder.throwable);
					ex2.initApplicationException(throwableHolder.throwable);
				}
				throw ex2;
			} catch (Throwable ex2) {
				if (throwableHolder.throwable != null) {
					logger.error("Application exception overridden by commit exception", throwableHolder.throwable);
				}
				throw ex2;
			}

			// Check result state: It might indicate a Throwable to rethrow.
			if (throwableHolder.throwable != null) {
				throw throwableHolder.throwable;
			}
			return result;
		}

	}

	/**
	 * Clear the transaction manager cache.
	 */
	protected void clearTransactionManagerCache() {
		this.transactionManagerCache.clear();
		this.beanFactory = null;
	}

	/**
	 * Determine the specific transaction manager to use for the given transaction.
	 * 确定给定事务属性所使用的特定事务管理器
	 */
	@Nullable
	protected TransactionManager determineTransactionManager(@Nullable TransactionAttribute txAttr) {
		// 如果没有事务属性，那么直接获取手动设置的事务管理器，可能返回null
		//编程式事务也会调用该方法，并且没有事务属性
		if (txAttr == null || this.beanFactory == null) {
			return getTransactionManager();
		}
		//获取限定符，也就是每一个@Transactional注解的value属性或者transactionManager属性
		String qualifier = txAttr.getQualifier();
		//如果设置了该属性，那么通过限定符从容器中查找指定的事务管理器
		//一般都是注解设置的，基于XML的<tx:method/>标签没有配置该属性
		if (StringUtils.hasText(qualifier)) {
			return determineQualifiedTransactionManager(this.beanFactory, qualifier);
		}
		//否则，如果设置了transactionManagerBeanName属性，那么通过限定符查找指定的事务管理器
		//一般都是基于XML的<tx:annotation-driven/>标签的transaction-manager属性
		else if (StringUtils.hasText(this.transactionManagerBeanName)) {
			return determineQualifiedTransactionManager(this.beanFactory, this.transactionManagerBeanName);
		}
		//否则，将会查找事务管理器，这个就是现在最常见的逻辑
		else {
			//获取手动设置的事务管理器
			TransactionManager defaultTransactionManager = getTransactionManager();
			//如果没有，那么获取默认的事务管理器
			if (defaultTransactionManager == null) {
				//从缓存中通过默认key尝试获取默认的事务管理器
				defaultTransactionManager = this.transactionManagerCache.get(DEFAULT_TRANSACTION_MANAGER_KEY);
				//如果为null
				if (defaultTransactionManager == null) {
					//那么尝试从容器中查找TransactionManager类型的bean，并且作为默认事务管理器
					//这就是基于注解配置的最常见的逻辑，该方法如果找不到事务管理器或者找到多个事务管理器，那么将抛出异常
					defaultTransactionManager = this.beanFactory.getBean(TransactionManager.class);
					//找到了之后存入缓存，key就是默认key
					this.transactionManagerCache.putIfAbsent(
							DEFAULT_TRANSACTION_MANAGER_KEY, defaultTransactionManager);
				}
			}
			//返回事务管理器
			return defaultTransactionManager;
		}
	}

	/**
	 * 根据限定符查找指定的事务管理器
	 */
	private TransactionManager determineQualifiedTransactionManager(BeanFactory beanFactory, String qualifier) {
		//首先从缓存中通过qualifier作为key尝试获取事务管理器
		TransactionManager txManager = this.transactionManagerCache.get(qualifier);
		//如果为null
		if (txManager == null) {
			//那么尝试从容器中查找TransactionManager类型且名为qualifier的bean，并且作为该限定符的事务管理器
			//这就是指定通过@Transactional注解指定事务管理器配置，该方法如果找不到事务管理器，那么将抛出异常
			txManager = BeanFactoryAnnotationUtils.qualifiedBeanOfType(
					beanFactory, TransactionManager.class, qualifier);
			//找到了之后存入缓存，key就是默认qualifier
			this.transactionManagerCache.putIfAbsent(qualifier, txManager);
		}
		return txManager;
	}


	@Nullable
	private PlatformTransactionManager asPlatformTransactionManager(@Nullable Object transactionManager) {
		if (transactionManager == null || transactionManager instanceof PlatformTransactionManager) {
			return (PlatformTransactionManager) transactionManager;
		}
		else {
			throw new IllegalStateException(
					"Specified transaction manager is not a PlatformTransactionManager: " + transactionManager);
		}
	}

	private String methodIdentification(Method method, @Nullable Class<?> targetClass,
			@Nullable TransactionAttribute txAttr) {

		String methodIdentification = methodIdentification(method, targetClass);
		if (methodIdentification == null) {
			if (txAttr instanceof DefaultTransactionAttribute) {
				methodIdentification = ((DefaultTransactionAttribute) txAttr).getDescriptor();
			}
			if (methodIdentification == null) {
				methodIdentification = ClassUtils.getQualifiedMethodName(method, targetClass);
			}
		}
		return methodIdentification;
	}

	/**
	 * Convenience method to return a String representation of this Method
	 * for use in logging. Can be overridden in subclasses to provide a
	 * different identifier for the given method.
	 * <p>The default implementation returns {@code null}, indicating the
	 * use of {@link DefaultTransactionAttribute#getDescriptor()} instead,
	 * ending up as {@link ClassUtils#getQualifiedMethodName(Method, Class)}.
	 * @param method the method we're interested in
	 * @param targetClass the class that the method is being invoked on
	 * @return a String representation identifying this method
	 * @see org.springframework.util.ClassUtils#getQualifiedMethodName
	 */
	@Nullable
	protected String methodIdentification(Method method, @Nullable Class<?> targetClass) {
		return null;
	}

	/**
	 * Create a transaction if necessary based on the given TransactionAttribute.
	 * <p>Allows callers to perform custom TransactionAttribute lookups through
	 * the TransactionAttributeSource.
	 * 如有必要，根据给定的TransactionAttribute创建一个事务。
	 * @param txAttr the TransactionAttribute (may be {@code null})
	 * TransactionAttribute，可能为null
	 * @param joinpointIdentification the fully qualified method name
	 * (used for monitoring and logging purposes)
	 * 完全限定的方法名称，用于记录日志
	 * @return a TransactionInfo object, whether a transaction was created.
	 * The {@code hasTransaction()} method on TransactionInfo can be used to
	 * tell if there was a transaction created.
	 * 一个TransactionInfo对象，无论是否创建了一个事务。TransactionInfo上的hasTransaction()方法可用于判断是否创建了事务。
	 * @see #getTransactionAttributeSource()
	 */
	@SuppressWarnings("serial")
	protected TransactionInfo createTransactionIfNecessary(@Nullable PlatformTransactionManager tm,
														   @Nullable TransactionAttribute txAttr, final String joinpointIdentification) {

		/*如果未指定事务名称，则将方法标识（方法全限定名）用作事务名称。*/
		if (txAttr != null && txAttr.getName() == null) {
			//创建一个DelegatingTransactionAttribute，getName将返回方法全限定名
			txAttr = new DelegatingTransactionAttribute(txAttr) {
				@Override
				public String getName() {
					return joinpointIdentification;
				}
			};
		}
		TransactionStatus status = null;
		if (txAttr != null) {
			if (tm != null) {
				/*
				 * 通过事务属性从事务管理器中获取一个事务状态，实际上TransactionStatus就可以看作一个事务
				 * 该方法非常重要，这里就表示为当前方法获取了一个事务
				 */
				status = tm.getTransaction(txAttr);
			} else {
				if (logger.isDebugEnabled()) {
					logger.debug("Skipping transactional joinpoint [" + joinpointIdentification +
							"] because no transaction manager has been configured");
				}
			}
		}
		//准备一个TransactionInfo
		return prepareTransactionInfo(tm, txAttr, joinpointIdentification, status);
	}


	/**
	 * Prepare a TransactionInfo for the given attribute and status object.
	 * 为给定的属性和状态对象准备一个TransactionInfo
	 * @param txAttr the TransactionAttribute (may be {@code null})
	 *  TransactionAttribute，可能为null
	 * @param joinpointIdentification the fully qualified method name
	 * (used for monitoring and logging purposes)
	 * 完全限定的方法名称，用于记录日志
	 * @param status the TransactionStatus for the current transaction
	 * 当前事务的TransactionStatus
	 * @return the prepared TransactionInfo object
	 * 准备好的TransactionInfo对象
	 */
	protected TransactionInfo prepareTransactionInfo(@Nullable PlatformTransactionManager tm,
			@Nullable TransactionAttribute txAttr, String joinpointIdentification,
			@Nullable TransactionStatus status) {

		/*
		 * 根据给定的事务属性、事务管理器、方法连接点描述字符串(全限定方法名)信息创建一个事务信息对象TransactionInfo
		 */
		TransactionInfo txInfo = new TransactionInfo(tm, txAttr, joinpointIdentification);
		if (txAttr != null) {
			// We need a transaction for this method...
			if (logger.isTraceEnabled()) {
				logger.trace("Getting transaction for [" + txInfo.getJoinpointIdentification() + "]");
			}
			// The transaction manager will flag an error if an incompatible tx already exists.
			//那么设置事务状态，这里就表示为当前方法创建了一个事务
			txInfo.newTransactionStatus(status);
		}
		// 如果事务属性为null，那么表示当前方法必须要创建事务
		else {
			// The TransactionInfo.hasTransaction() method will return false. We created it only
			// to preserve the integrity of the ThreadLocal stack maintained in this class.
			if (logger.isTraceEnabled()) {
				logger.trace("No need to create transaction for [" + joinpointIdentification +
						"]: This method is not transactional.");
			}
		}

		// We always bind the TransactionInfo to the thread, even if we didn't create
		// a new transaction here. This guarantees that the TransactionInfo stack
		// will be managed correctly even if no transaction was created by this aspect.
		//始终将最新的TransactionInfo绑定到当前线程，即使我们没有在此处创建新的事务也是如此。
		//也就是将当前线程的最新事务栈设置为当前对象存入transactionInfoHolder中
		//这保证即使此方面未创建任何事务，也将正确管理TransactionInfo堆栈。
		txInfo.bindToThread();
		return txInfo;
	}

	/**
	 * Execute after successful completion of call, but not after an exception was handled.
	 * Do nothing if we didn't create a transaction.
	 * @param txInfo information about the current transaction
	 */
	protected void commitTransactionAfterReturning(@Nullable TransactionInfo txInfo) {
		if (txInfo != null && txInfo.getTransactionStatus() != null) {
			if (logger.isTraceEnabled()) {
				logger.trace("Completing transaction for [" + txInfo.getJoinpointIdentification() + "]");
			}
			txInfo.getTransactionManager().commit(txInfo.getTransactionStatus());
		}
	}

	/**
	 * Handle a throwable, completing the transaction.
	 * We may commit or roll back, depending on the configuration.
	 * @param txInfo information about the current transaction
	 * @param ex throwable encountered
	 */
	protected void completeTransactionAfterThrowing(@Nullable TransactionInfo txInfo, Throwable ex) {
		if (txInfo != null && txInfo.getTransactionStatus() != null) {
			if (logger.isTraceEnabled()) {
				logger.trace("Completing transaction for [" + txInfo.getJoinpointIdentification() +
						"] after exception: " + ex);
			}
			if (txInfo.transactionAttribute != null && txInfo.transactionAttribute.rollbackOn(ex)) {
				try {
					txInfo.getTransactionManager().rollback(txInfo.getTransactionStatus());
				}
				catch (TransactionSystemException ex2) {
					logger.error("Application exception overridden by rollback exception", ex);
					ex2.initApplicationException(ex);
					throw ex2;
				}
				catch (RuntimeException | Error ex2) {
					logger.error("Application exception overridden by rollback exception", ex);
					throw ex2;
				}
			}
			else {
				// We don't roll back on this exception.
				// Will still roll back if TransactionStatus.isRollbackOnly() is true.
				try {
					txInfo.getTransactionManager().commit(txInfo.getTransactionStatus());
				}
				catch (TransactionSystemException ex2) {
					logger.error("Application exception overridden by commit exception", ex);
					ex2.initApplicationException(ex);
					throw ex2;
				}
				catch (RuntimeException | Error ex2) {
					logger.error("Application exception overridden by commit exception", ex);
					throw ex2;
				}
			}
		}
	}

	/**
	 * Reset the TransactionInfo ThreadLocal.
	 * 重置TransactionInfo的ThreadLocal
	 * <p>Call this in all cases: exception or normal return!
	 * 无论是方法正常还是异常完成，都会调用该方法
	 * @param txInfo information about the current transaction (may be {@code null})
	 */
	protected void cleanupTransactionInfo(@Nullable TransactionAspectSupport.TransactionInfo txInfo) {
		if (txInfo != null) {
			//使用堆栈还原旧的事务TransactionInfo。如果未设置，则为null。
			txInfo.restoreThreadLocalStatus();
		}
	}



	/**
	 * Opaque object used to hold transaction information. Subclasses
	 * must pass it back to methods on this class, but not see its internals.
	 */
	protected static final class TransactionInfo {

		@Nullable
		private final PlatformTransactionManager transactionManager;

		@Nullable
		private final TransactionAttribute transactionAttribute;

		private final String joinpointIdentification;

		@Nullable
		private TransactionStatus transactionStatus;

		@Nullable
		private TransactionInfo oldTransactionInfo;

		public TransactionInfo(@Nullable PlatformTransactionManager transactionManager,
				@Nullable TransactionAttribute transactionAttribute, String joinpointIdentification) {

			this.transactionManager = transactionManager;
			this.transactionAttribute = transactionAttribute;
			this.joinpointIdentification = joinpointIdentification;
		}

		public PlatformTransactionManager getTransactionManager() {
			Assert.state(this.transactionManager != null, "No PlatformTransactionManager set");
			return this.transactionManager;
		}

		@Nullable
		public TransactionAttribute getTransactionAttribute() {
			return this.transactionAttribute;
		}

		/**
		 * Return a String representation of this joinpoint (usually a Method call)
		 * for use in logging.
		 */
		public String getJoinpointIdentification() {
			return this.joinpointIdentification;
		}

		public void newTransactionStatus(@Nullable TransactionStatus status) {
			this.transactionStatus = status;
		}

		@Nullable
		public TransactionStatus getTransactionStatus() {
			return this.transactionStatus;
		}

		/**
		 * Return whether a transaction was created by this aspect,
		 * or whether we just have a placeholder to keep ThreadLocal stack integrity.
		 */
		public boolean hasTransaction() {
			return (this.transactionStatus != null);
		}

		private void bindToThread() {
			// Expose current TransactionStatus, preserving any existing TransactionStatus
			// for restoration after this transaction is complete.
			this.oldTransactionInfo = transactionInfoHolder.get();
			transactionInfoHolder.set(this);
		}

		private void restoreThreadLocalStatus() {
			// Use stack to restore old transaction TransactionInfo.
			// Will be null if none was set.
			//使用堆栈还原旧的事务TransactionInfo。
			//每一个当前的TransactionInfo都通过oldTransactionInfo属性保留了对前一个TransactionInfo的引用
			transactionInfoHolder.set(this.oldTransactionInfo);
		}

		@Override
		public String toString() {
			return (this.transactionAttribute != null ? this.transactionAttribute.toString() : "No transaction");
		}
	}


	/**
	 * Simple callback interface for proceeding with the target invocation.
	 * Concrete interceptors/aspects adapt this to their invocation mechanism.
	 * 简单的回调接口，用于进行目标调用。应用于具体的拦截器/切面使其适应其AOP调用机制。
	 */
	@FunctionalInterface
	protected interface InvocationCallback {

		/**
		 * 执行调用的逻辑
		 *
		 * @return 执行返回的结果
		 * @throws Throwable 中途抛出的异常
		 */
		@Nullable
		Object proceedWithInvocation() throws Throwable;
	}


	/**
	 * Coroutines-supporting extension of the callback interface.
	 */
	protected interface CoroutinesInvocationCallback extends InvocationCallback {

		Object getTarget();

		Object[] getArguments();

		default Object getContinuation() {
			Object[] args = getArguments();
			return args[args.length - 1];
		}
	}


	/**
	 * Internal holder class for a Throwable in a callback transaction model.
	 */
	private static class ThrowableHolder {

		@Nullable
		public Throwable throwable;
	}


	/**
	 * Internal holder class for a Throwable, used as a RuntimeException to be
	 * thrown from a TransactionCallback (and subsequently unwrapped again).
	 */
	@SuppressWarnings("serial")
	private static class ThrowableHolderException extends RuntimeException {

		public ThrowableHolderException(Throwable throwable) {
			super(throwable);
		}

		@Override
		public String toString() {
			return getCause().toString();
		}
	}


	/**
	 * Inner class to avoid a hard dependency on the Vavr library at runtime.
	 */
	private static class VavrDelegate {

		public static boolean isVavrTry(Object retVal) {
			return (retVal instanceof Try);
		}

		public static Object evaluateTryFailure(Object retVal, TransactionAttribute txAttr, TransactionStatus status) {
			return ((Try<?>) retVal).onFailure(ex -> {
				if (txAttr.rollbackOn(ex)) {
					status.setRollbackOnly();
				}
			});
		}
	}

	/**
	 * Inner class to avoid a hard dependency on Kotlin at runtime.
	 */
	private static class KotlinDelegate {

		private static Object asFlow(Publisher<?> publisher) {
			return ReactiveFlowKt.asFlow(publisher);
		}

		@SuppressWarnings({"unchecked", "deprecation"})
		@Nullable
		private static Object awaitSingleOrNull(Publisher<?> publisher, Object continuation) {
			return AwaitKt.awaitSingleOrNull(publisher, (Continuation<Object>) continuation);
		}

		public static Publisher<?> invokeSuspendingFunction(Method method, CoroutinesInvocationCallback callback) {
			CoroutineContext coroutineContext = ((Continuation<?>) callback.getContinuation()).getContext().minusKey(Job.Key);
			return CoroutinesUtils.invokeSuspendingFunction(coroutineContext, method, callback.getTarget(), callback.getArguments());
		}

	}


	/**
	 * Delegate for Reactor-based management of transactional methods with a
	 * reactive return type.
	 */
	private class ReactiveTransactionSupport {

		private final ReactiveAdapter adapter;

		public ReactiveTransactionSupport(ReactiveAdapter adapter) {
			this.adapter = adapter;
		}

		public Object invokeWithinTransaction(Method method, @Nullable Class<?> targetClass,
				InvocationCallback invocation, @Nullable TransactionAttribute txAttr, ReactiveTransactionManager rtm) {

			String joinpointIdentification = methodIdentification(method, targetClass, txAttr);

			// For Mono and suspending functions not returning kotlinx.coroutines.flow.Flow
			if (Mono.class.isAssignableFrom(method.getReturnType()) || (KotlinDetector.isSuspendingFunction(method) &&
					!COROUTINES_FLOW_CLASS_NAME.equals(new MethodParameter(method, -1).getParameterType().getName()))) {

				return TransactionContextManager.currentContext().flatMap(context ->
						createTransactionIfNecessary(rtm, txAttr, joinpointIdentification).flatMap(it -> {
							try {
								// Need re-wrapping until we get hold of the exception through usingWhen.
								return Mono.<Object, ReactiveTransactionInfo>usingWhen(
										Mono.just(it),
										txInfo -> {
											try {
												return (Mono<?>) invocation.proceedWithInvocation();
											}
											catch (Throwable ex) {
												return Mono.error(ex);
											}
										},
										this::commitTransactionAfterReturning,
										(txInfo, err) -> Mono.empty(),
										this::rollbackTransactionOnCancel)
										.onErrorResume(ex ->
												completeTransactionAfterThrowing(it, ex).then(Mono.error(ex)));
							}
							catch (Throwable ex) {
								// target invocation exception
								return completeTransactionAfterThrowing(it, ex).then(Mono.error(ex));
							}
						})).contextWrite(TransactionContextManager.getOrCreateContext())
						.contextWrite(TransactionContextManager.getOrCreateContextHolder());
			}

			// Any other reactive type, typically a Flux
			return this.adapter.fromPublisher(TransactionContextManager.currentContext().flatMapMany(context ->
					createTransactionIfNecessary(rtm, txAttr, joinpointIdentification).flatMapMany(it -> {
						try {
							// Need re-wrapping until we get hold of the exception through usingWhen.
							return Flux
									.usingWhen(
											Mono.just(it),
											txInfo -> {
												try {
													return this.adapter.toPublisher(invocation.proceedWithInvocation());
												}
												catch (Throwable ex) {
													return Mono.error(ex);
												}
											},
											this::commitTransactionAfterReturning,
											(txInfo, ex) -> Mono.empty(),
											this::rollbackTransactionOnCancel)
									.onErrorResume(ex ->
											completeTransactionAfterThrowing(it, ex).then(Mono.error(ex)));
						}
						catch (Throwable ex) {
							// target invocation exception
							return completeTransactionAfterThrowing(it, ex).then(Mono.error(ex));
						}
					})).contextWrite(TransactionContextManager.getOrCreateContext())
					.contextWrite(TransactionContextManager.getOrCreateContextHolder()));
		}

		@SuppressWarnings("serial")
		private Mono<ReactiveTransactionInfo> createTransactionIfNecessary(ReactiveTransactionManager tm,
				@Nullable TransactionAttribute txAttr, final String joinpointIdentification) {

			// If no name specified, apply method identification as transaction name.
			if (txAttr != null && txAttr.getName() == null) {
				txAttr = new DelegatingTransactionAttribute(txAttr) {
					@Override
					public String getName() {
						return joinpointIdentification;
					}
				};
			}

			final TransactionAttribute attrToUse = txAttr;
			Mono<ReactiveTransaction> tx = (attrToUse != null ? tm.getReactiveTransaction(attrToUse) : Mono.empty());
			return tx.map(it -> prepareTransactionInfo(tm, attrToUse, joinpointIdentification, it)).switchIfEmpty(
					Mono.defer(() -> Mono.just(prepareTransactionInfo(tm, attrToUse, joinpointIdentification, null))));
		}

		private ReactiveTransactionInfo prepareTransactionInfo(@Nullable ReactiveTransactionManager tm,
				@Nullable TransactionAttribute txAttr, String joinpointIdentification,
				@Nullable ReactiveTransaction transaction) {

			ReactiveTransactionInfo txInfo = new ReactiveTransactionInfo(tm, txAttr, joinpointIdentification);
			if (txAttr != null) {
				// We need a transaction for this method...
				if (logger.isTraceEnabled()) {
					logger.trace("Getting transaction for [" + txInfo.getJoinpointIdentification() + "]");
				}
				// The transaction manager will flag an error if an incompatible tx already exists.
				txInfo.newReactiveTransaction(transaction);
			}
			else {
				// The TransactionInfo.hasTransaction() method will return false. We created it only
				// to preserve the integrity of the ThreadLocal stack maintained in this class.
				if (logger.isTraceEnabled()) {
					logger.trace("Don't need to create transaction for [" + joinpointIdentification +
							"]: This method isn't transactional.");
				}
			}

			return txInfo;
		}

		/**
		 * 在成功完成方法调用之后执行。如果我们不创建事务，则不执行任何操作。
		 * @param txInfo
		 * @return
		 */
		private Mono<Void> commitTransactionAfterReturning(@Nullable ReactiveTransactionInfo txInfo) {
			if (txInfo != null && txInfo.getReactiveTransaction() != null) {
				if (logger.isTraceEnabled()) {
					logger.trace("Completing transaction for [" + txInfo.getJoinpointIdentification() + "]");
				}
				//通过事务管理器执行commit提交事务操作
				//但是如果TransactionStatus.isRollbackOnly()方法被设置为true，那么仍然会回滚
				return txInfo.getTransactionManager().commit(txInfo.getReactiveTransaction());
			}
			return Mono.empty();
		}

		private Mono<Void> rollbackTransactionOnCancel(@Nullable ReactiveTransactionInfo txInfo) {
			if (txInfo != null && txInfo.getReactiveTransaction() != null) {
				if (logger.isTraceEnabled()) {
					logger.trace("Rolling back transaction for [" + txInfo.getJoinpointIdentification() + "] after cancellation");
				}
				return txInfo.getTransactionManager().rollback(txInfo.getReactiveTransaction());
			}
			return Mono.empty();
		}

		/**
		 * 处理执行异常并完成事务。我们可能会提交或回滚，具体取决于配置。
		 *
		 * @param txInfo 有关当前事务的信息对象
		 * @param ex     抛出的异常
		 */
		private Mono<Void> completeTransactionAfterThrowing(@Nullable ReactiveTransactionInfo txInfo, Throwable ex) {
			//如果内部存在事务状态对象
			if (txInfo != null && txInfo.getReactiveTransaction() != null) {
				if (logger.isTraceEnabled()) {
					logger.trace("Completing transaction for [" + txInfo.getJoinpointIdentification() +
							"] after exception: " + ex);
				}
				/*
				 * 如果事务属性不为null，并且通过事物属性的rollbackOn方法判断出当前的异常需要进行回滚，那么就进行回滚
				 * 声明式事务一般都是这个逻辑
				 */
				if (txInfo.transactionAttribute != null && txInfo.transactionAttribute.rollbackOn(ex)) {
					/*
					 * 那么通过事务管理器执行rollback回滚操作
					 */
					return txInfo.getTransactionManager().rollback(txInfo.getReactiveTransaction()).onErrorMap(ex2 -> {
								logger.error("Application exception overridden by rollback exception", ex);
								if (ex2 instanceof TransactionSystemException) {
									((TransactionSystemException) ex2).initApplicationException(ex);
								}
								return ex2;
							}
					);
				}
				else {
					// We don't roll back on this exception.
					// Will still roll back if TransactionStatus.isRollbackOnly() is true.
					/*
					 * 到这一步，表示不会执行回滚，那么通过事务管理器执行commit提交操作
					 * 但是如果TransactionStatus.isRollbackOnly()方法被设置为true，那么仍然会回滚
					 */
					return txInfo.getTransactionManager().commit(txInfo.getReactiveTransaction()).onErrorMap(ex2 -> {
								logger.error("Application exception overridden by commit exception", ex);
								if (ex2 instanceof TransactionSystemException) {
									((TransactionSystemException) ex2).initApplicationException(ex);
								}
								return ex2;
							}
					);
				}
			}
			return Mono.empty();
		}
	}


	/**
	 * Opaque object used to hold transaction information for reactive methods.
	 */
	private static final class ReactiveTransactionInfo {

		@Nullable
		private final ReactiveTransactionManager transactionManager;

		@Nullable
		private final TransactionAttribute transactionAttribute;

		private final String joinpointIdentification;

		@Nullable
		private ReactiveTransaction reactiveTransaction;

		public ReactiveTransactionInfo(@Nullable ReactiveTransactionManager transactionManager,
				@Nullable TransactionAttribute transactionAttribute, String joinpointIdentification) {

			this.transactionManager = transactionManager;
			this.transactionAttribute = transactionAttribute;
			this.joinpointIdentification = joinpointIdentification;
		}

		public ReactiveTransactionManager getTransactionManager() {
			Assert.state(this.transactionManager != null, "No ReactiveTransactionManager set");
			return this.transactionManager;
		}

		@Nullable
		public TransactionAttribute getTransactionAttribute() {
			return this.transactionAttribute;
		}

		/**
		 * Return a String representation of this joinpoint (usually a Method call)
		 * for use in logging.
		 */
		public String getJoinpointIdentification() {
			return this.joinpointIdentification;
		}

		public void newReactiveTransaction(@Nullable ReactiveTransaction transaction) {
			this.reactiveTransaction = transaction;
		}

		@Nullable
		public ReactiveTransaction getReactiveTransaction() {
			return this.reactiveTransaction;
		}

		@Override
		public String toString() {
			return (this.transactionAttribute != null ? this.transactionAttribute.toString() : "No transaction");
		}
	}

}
